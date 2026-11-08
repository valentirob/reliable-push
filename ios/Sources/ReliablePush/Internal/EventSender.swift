import Foundation

/// Result of `EventSender.flush`.
enum FlushResult: Sendable {
    /// The queue is empty.
    case done
    /// Events remain (also when the flush was cancelled); try again at the next launch / NSE run.
    case retryLater
    /// No registration (never registered, or logged out).
    case notRegistered
    /// Not attempted: another flush held the lock (until the deadline, if any), or a 429 `Retry-After` has not elapsed.
    case skipped
}

/// Sends queued events in batches of 50 to `POST /v1/events`; same outcome rules as the Android SDK.
///
/// Never renews the device token (spec 3.1): the stored token is sent as it is, also when expired (the backend accepts
/// it on this endpoint within a grace period). A 401 keeps the events queued and sets `renewalNeeded`, which the app's
/// `ensureRegistered` acts on. Events are kept on every other failure. An event the server leaves unacknowledged in two
/// sent batches is dropped with a WARN (Android parity).
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

    /// Sends at most `maxBatches` batches before `deadline`. `priority` (the extension's own event) leads the first
    /// batch, so a long queue cannot push it out of the extension's one send.
    func flush(deadline: Date?, maxBatches: Int = .max, priority: QueuedEvent? = nil) async -> FlushResult {
        if let notBefore = state.retryNotBefore {
            // further away than any Retry-After we store: set under a wrong clock, so expired
            if notBefore.timeIntervalSince(now()) > TimeInterval(Self.maxRetryAfter) {
                state.retryNotBefore = nil
            } else if now() < notBefore {
                return .skipped
            }
        }
        // With a deadline (every app and extension flush has one), wait for a lock held by the other process: its flush
        // may end before reaching an event queued during its last request (M7). Without one, skip at once.
        let acquired: FileLock.Held?
        if let deadline {
            acquired = await flushLock.lock(until: deadline, now: now)
        } else {
            acquired = flushLock.tryLock()
        }
        // cancelled (logout, an expired or refused activity): events stay queued, as for a cancel between batches
        guard let held = acquired else { return Task.isCancelled ? .retryLater : .skipped }
        defer { held.release() }

        if queue.count == 0 { return .done }
        let token: DeviceToken
        switch tokens.load() {
        case .notRegistered:
            SDKLog.warn("tracking.not_registered")
            return .notRegistered
        case .unavailable:
            return .retryLater
        case let .registered(_, t):
            token = t
        }

        var attempted = Set<String>()
        var sent = 0
        while sent < maxBatches {
            if Task.isCancelled { return .retryLater }
            if let deadline, now() >= deadline { return queue.count > 0 ? .retryLater : .done }
            let batch: [QueuedEvent]
            if sent == 0, let priority {
                batch = [priority] + queue.peek(max: Self.batchSize - 1, excluding: [priority.key])
            } else {
                batch = queue.peek(max: Self.batchSize, excluding: attempted)
            }
            if batch.isEmpty { break }
            attempted.formUnion(batch.map(\.key))
            sent += 1

            switch await api.sendEvents(token: token.raw, events: batch) {
            case .ok(let items):
                handle(batch: batch, items: items)
            case .networkError:
                return .retryLater
            case .http(401, _):
                // past the backend's grace period, or revoked: the app renews (ensureRegistered); the events wait
                tokens.markRenewalNeeded()
                SDKLog.warn("auth.failed", ["status": "401", "call": "events"])
                return .retryLater
            case let .http(status, retryAfter):
                if (400...499).contains(status) && status != 408 && status != 429 {
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
        for item in items { byId[item.trackingId.lowercased()] = item.outcome } // case-insensitive match
        var done = Set<String>()
        for e in batch {
            switch byId[e.trackingId.lowercased()] {
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
}
