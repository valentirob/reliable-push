import Foundation

/// Result of `EventSender.flush`.
enum FlushResult: Sendable {
    /// The queue is empty.
    case done
    /// Events remain; try again at the next launch / NSE run.
    case retryLater
    /// No usable registration (never registered, or a refresh was answered 401).
    case notRegistered
    /// Not attempted: another flush holds the lock, or a 429 `Retry-After` has not elapsed yet.
    case skipped
}

/// Sends queued events in batches of 50 to `POST /v1/events`; same outcome rules as the Android SDK.
///
/// Token: refreshed first when `needsRefresh` and the process-wide `RefreshGate` allows (a transient failure of that
/// early refresh is not fatal; the current token may still work). A 401 refreshes once more (gate permitting) and
/// retries the batch once; a refresh answered 401 clears the registration. Events are kept on every other failure.
/// An event the server leaves unacknowledged in two sent batches is dropped with a WARN (Android parity).
final class EventSender: Sendable {
    static let batchSize = 50
    static let maxRetryAfter = 3600

    private let api: DeviceAPI
    private let tokens: TokenStore
    private let state: SharedState
    private let queue: EventQueue
    private let flushLock: FileLock
    private let now: @Sendable () -> Date

    init(api: DeviceAPI, tokens: TokenStore, state: SharedState, queue: EventQueue, flushLock: FileLock,
         now: @escaping @Sendable () -> Date) {
        self.api = api
        self.tokens = tokens
        self.state = state
        self.queue = queue
        self.flushLock = flushLock
        self.now = now
    }

    func flush(deadline: Date?) async -> FlushResult {
        if let notBefore = state.retryNotBefore {
            // further away than any Retry-After we store: set under a wrong clock, so expired
            if notBefore.timeIntervalSince(now()) > TimeInterval(Self.maxRetryAfter) {
                state.retryNotBefore = nil
            } else if now() < notBefore {
                return .skipped
            }
        }
        guard let held = flushLock.tryLock() else { return .skipped }
        defer { held.release() }

        if queue.count == 0 { return .done }
        let deviceId: String
        var token: DeviceToken
        switch tokens.load() {
        case .notRegistered:
            SDKLog.warn("tracking.not_registered")
            return .notRegistered
        case .unavailable:
            return .retryLater
        case let .registered(id, t):
            deviceId = id
            token = t
        }

        if token.needsRefresh(now: now()) && RefreshGate.tryAcquire() {
            switch await refresh(deviceId: deviceId, token: token.raw) {
            case .refreshed(let t): token = t
            case .failed(.notRegistered): return .notRegistered
            case .rateLimited: return .retryLater
            case .failed: break
            }
        }

        var attempted = Set<String>()
        while true {
            if Task.isCancelled { return .retryLater }
            if let deadline, now() >= deadline { return queue.count > 0 ? .retryLater : .done }
            let batch = queue.peek(max: Self.batchSize, excluding: attempted)
            if batch.isEmpty { break }
            attempted.formUnion(batch.map(\.key))

            var result = await api.sendEvents(token: token.raw, events: batch)
            if case .http(401, _) = result {
                guard RefreshGate.tryAcquire() else {
                    SDKLog.warn("auth.failed", ["status": "401", "refresh": "exhausted"])
                    return .retryLater
                }
                switch await refresh(deviceId: deviceId, token: token.raw) {
                case .refreshed(let t): token = t
                case .failed(let r): return r
                case .rateLimited: return .retryLater
                }
                result = await api.sendEvents(token: token.raw, events: batch)
                if case .http(401, _) = result {
                    SDKLog.warn("auth.failed", ["status": "401", "refresh": "done"])
                    return .retryLater
                }
            }

            switch result {
            case .ok(let items):
                handle(batch: batch, items: items)
            case .networkError:
                return .retryLater
            case let .http(status, retryAfter):
                if (400...499).contains(status) && status != 401 && status != 408 && status != 429 {
                    queue.remove(keys: Set(batch.map(\.key)))
                    SDKLog.warn("tracking.event_dropped", ["count": "\(batch.count)", "status": "\(status)"])
                } else {
                    if status == 429 { rateLimited(retryAfter) }
                    return .retryLater
                }
            }
        }
        if queue.count > 0 { return .retryLater }
        state.retryNotBefore = nil
        return .done
    }

    private func handle(batch: [QueuedEvent], items: [EventOutcome]) {
        var byId: [String: String] = [:]
        for item in items { byId[item.trackingId] = item.outcome }
        var done = Set<String>()
        for e in batch {
            switch byId[e.trackingId] {
            case "ACCEPTED", "DUPLICATE", "LATE":
                done.insert(e.key)
                SDKLog.info("tracking.event_sent", ["trackingId": e.trackingId])
            case let outcome? where outcome == "NOT_FOUND" || outcome == "FORBIDDEN":
                done.insert(e.key)
                SDKLog.warn("tracking.event_dropped", ["count": "1", "outcome": outcome, "trackingId": e.trackingId])
            default:
                break // unmentioned or unknown outcome: counted as a miss below
            }
        }
        queue.remove(keys: done)
        let missed = Set(batch.map(\.key)).subtracting(done)
        let gone = queue.noteUnacknowledged(keys: missed)
        for e in batch where gone.contains(e.key) {
            SDKLog.warn("tracking.event_dropped", ["count": "1", "reason": "unacknowledged", "trackingId": e.trackingId])
        }
    }

    private func rateLimited(_ retryAfter: Int?) {
        SDKLog.warn("rate_limit.exceeded", ["retryAfter": retryAfter.map(String.init) ?? "none"])
        if let retryAfter {
            state.retryNotBefore = now().addingTimeInterval(TimeInterval(min(max(retryAfter, 0), Self.maxRetryAfter)))
        }
    }

    private enum RefreshOutcome {
        case refreshed(DeviceToken)
        case failed(FlushResult)
        case rateLimited
    }

    private func refresh(deviceId: String, token: String) async -> RefreshOutcome {
        switch await api.refresh(token: token) {
        case .ok(let issued):
            guard let parsed = DeviceToken.parse(issued.deviceToken),
                  tokens.save(Registration(deviceId: deviceId, deviceToken: issued.deviceToken, expiresAt: issued.expiresAt))
            else { return .failed(.retryLater) }
            SDKLog.info("token.refreshed", ["deviceId": deviceId])
            return .refreshed(parsed)
        case let .http(status, retryAfter):
            if status == 401 {
                tokens.clear()
                SDKLog.warn("auth.failed", ["status": "401", "refresh": "rejected"])
                return .failed(.notRegistered)
            }
            if status == 429 {
                rateLimited(retryAfter)
                return .rateLimited
            }
            return .failed(.retryLater)
        case .networkError:
            return .failed(.retryLater)
        }
    }
}
