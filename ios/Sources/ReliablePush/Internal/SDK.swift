import Foundation

/// `Task.sleep` for `seconds` (negative counts as 0); throws when cancelled.
func sleep(_ seconds: TimeInterval) async throws {
    try await Task.sleep(nanoseconds: UInt64(max(seconds, 0) * 1_000_000_000))
}

/// Runs work that must finish before the app is suspended (`ProcessInfo.performExpiringActivity`). The tests inject
/// a fake. Contract (as ProcessInfo): `block(false)` runs the work synchronously and the activity lasts until it
/// returns; `block(true)` (possibly concurrently, possibly without a prior `false`) means "stop now".
protocol ExpiringActivityRunner: Sendable {
    func perform(reason: String, using block: @escaping @Sendable (Bool) -> Void)
}

struct ProcessInfoActivityRunner: ExpiringActivityRunner {
    func perform(reason: String, using block: @escaping @Sendable (Bool) -> Void) {
        ProcessInfo.processInfo.performExpiringActivity(withReason: reason, using: block)
    }
}

/// Everything the SDK touches outside its own memory, so tests never need a real App Group or Keychain.
/// A class (not a struct): shared by reference, which keeps the binary small.
final class SDKEnvironment: Sendable {
    let state: SharedState
    let queueDirectory: URL
    let secrets: any SecretStore
    let settings: any NotificationSettingsSource
    let sessionConfiguration: @Sendable () -> URLSessionConfiguration
    let now: @Sendable () -> Date
    let activity: any ExpiringActivityRunner
    /// Longest wait for the app's ID-token provider in `ensureRegistered` (Android parity: 30 s); tests shorten it.
    let providerTimeout: TimeInterval

    init(state: SharedState, queueDirectory: URL, secrets: any SecretStore, settings: any NotificationSettingsSource,
         sessionConfiguration: @escaping @Sendable () -> URLSessionConfiguration, now: @escaping @Sendable () -> Date,
         activity: any ExpiringActivityRunner, providerTimeout: TimeInterval = 30) {
        self.state = state
        self.queueDirectory = queueDirectory
        self.secrets = secrets
        self.settings = settings
        self.sessionConfiguration = sessionConfiguration
        self.now = now
        self.activity = activity
        self.providerTimeout = providerTimeout
    }

    /// The production environment of an App Group: its `UserDefaults` suite, the queue in
    /// `<container>/Library/Application Support/ReliablePush`, the Keychain in `options.keychainAccessGroup ?? appGroup`.
    static func appGroup(_ appGroup: String, options: ReliablePushOptions,
                         defaults: (String) -> UserDefaults? = { UserDefaults(suiteName: $0) },
                         container: (String) -> URL? = {
                             FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0)
                         }) throws(ReliablePushError) -> SDKEnvironment {
        guard let suite = defaults(appGroup), let root = container(appGroup) else { throw .appGroupUnavailable }
        return SDKEnvironment(
            state: SharedState(defaults: suite),
            queueDirectory: root.appendingPathComponent("Library/Application Support/ReliablePush", isDirectory: true),
            secrets: KeychainStore(accessGroup: options.keychainAccessGroup ?? appGroup),
            settings: SystemNotificationSettings(),
            sessionConfiguration: { .ephemeral },
            now: { Date() },
            activity: ProcessInfoActivityRunner())
    }
}

/// Composition root for one configuration (base URL + app id): API client, token store, queue, flush lock. Built by
/// `ReliablePush.configure` (app) and by `ReliablePushExtension` (NSE, from the stored config).
///
/// Process-wide state lives only here (`current`, the once-per-launch flush flag).
final class SDK: Sendable {
    static let userAgent = "reliable-push-ios/\(ReliablePush.version)"
    /// Upper bound of an app-side flush (R12: the flush holds `flush.lock` in the shared container across network
    /// I/O, and iOS kills a suspended app holding such a lock — 0xdead10cc).
    static let appFlushTimeout: TimeInterval = 25
    static let requestTimeout: TimeInterval = 10
    /// Longest wait for `flush.lock` before a logout or reconfigure clears anyway (an extension flush can hold it for
    /// up to 25 s, an earlier app flush for `appFlushTimeout`).
    static let lockWait: TimeInterval = 2

    let baseURL: URL
    let appId: String
    let env: SDKEnvironment
    let api: DeviceAPI
    let tokens: TokenStore
    let queue: EventQueue
    let flushLock: FileLock
    private let flushes = Locked(Flushes())
    /// The `ensureRegistered` run in progress, if any (a call during a run joins it), with its per-run id.
    private let ensureRun = Locked(EnsureRun())
    /// Bumped by logout and reconfigure: a registration that started before must not store its token afterwards.
    private let epoch = Locked(0)

    private struct EnsureRun: Sendable {
        var next = 0
        var current: (id: Int, task: Task<RegisterResult, Never>)?
    }

    /// The app-side flushes still running (all are cancelled by logout and reconfigure: M9), and the most recent one.
    private struct Flushes: Sendable {
        var next = 0
        var running: [Int: Task<FlushResult, Never>] = [:]
        var last: Task<FlushResult, Never>?
    }

    init(baseURL: URL, appId: String, env: SDKEnvironment) {
        self.baseURL = baseURL
        self.appId = appId
        self.env = env
        api = DeviceAPI(baseURL: baseURL, appId: appId, userAgent: Self.userAgent,
                        configuration: env.sessionConfiguration(), timeout: Self.requestTimeout)
        tokens = TokenStore(secrets: env.secrets, state: env.state, appId: appId)
        queue = EventQueue(directory: env.queueDirectory)
        flushLock = FileLock(url: env.queueDirectory.appendingPathComponent("flush.lock"))
    }

    /// The SDK of the stored configuration (the one the app's `configure` persisted), or nil.
    static func fromStoredConfig(_ env: SDKEnvironment) -> SDK? {
        guard let config = env.state.config, let url = try? BaseURL.validate(config.baseURL.absoluteString) else { return nil }
        return SDK(baseURL: url, appId: config.appId, env: env)
    }

    // MARK: Process-wide state

    private struct Process: Sendable {
        var current: SDK?
        var launchFlushStarted = false
    }

    private static let process = Locked(Process())

    static var current: SDK? { process.withLock { $0.current } }

    /// Installs the configuration. Same base URL and app id as the stored one: the stored registration is kept (and an
    /// in-process SDK for it is reused). Otherwise the old registration, queue and retry-not-before are cleared and
    /// the new config persisted. Starts the flush once per process.
    ///
    /// The clear (which may wait up to `lockWait` for `flush.lock`) runs outside the process lock but before this
    /// returns; the new SDK becomes `current` only after it, so nothing is queued for it and then wiped.
    static func configure(baseURL: URL, appId: String, env: SDKEnvironment) {
        let previous = current
        let sdk: SDK
        if let previous, previous.baseURL == baseURL, previous.appId == appId {
            sdk = previous
        } else {
            previous?.invalidate()
            sdk = SDK(baseURL: baseURL, appId: appId, env: env)
            sdk.applyConfig()
        }
        let startFlush = process.withLock { p -> Bool in
            p.current = sdk
            defer { p.launchFlushStarted = true }
            return !p.launchFlushStarted
        }
        if startFlush { sdk.startFlush() }
    }

    static func resetForTesting() { process.withLock { $0 = Process() } }

    /// Persists the config. A different stored one: clears its registration, the queue and the back-off, under
    /// `flush.lock` (waiting at most `lockWait`) so a flush still running here or in the extension cannot save a token
    /// afterwards.
    ///
    /// No stored config (fresh install or reinstall: the App Group defaults are gone, the Keychain item is not): clears
    /// this app id's Keychain record, which may belong to another user (R17, Android parity). Unless the Keychain is
    /// locked (M8): then this is a launch in the background before the first unlock after a reboot, when the App Group
    /// defaults also read as empty, so nothing is cleared or persisted; the next launch after the unlock does it.
    private func applyConfig() {
        let state = env.state
        if let stored = state.config {
            if stored.baseURL.absoluteString == baseURL.absoluteString && stored.appId == appId { return }
            flushLock.withLock(timeout: Self.lockWait) {
                TokenStore(secrets: env.secrets, state: state, appId: stored.appId).clear()
                tokens.clear()
                queue.clear()
                state.retryNotBefore = nil
            }
            SDKLog.info("sdk.reconfigured")
        } else {
            if env.secrets.read(account: appId) == .unavailable {
                SDKLog.info("sdk.reconfigured", ["reason": "storage_locked", "outcome": "deferred"])
                return
            }
            tokens.clear()
            SDKLog.info("sdk.reconfigured", ["reason": "no_stored_config"])
        }
        state.config = (baseURL, appId)
    }

    // MARK: Operations

    func register(idToken: String) async -> RegisterResult {
        var known: String?
        if case let .registered(id, _) = tokens.load() { known = id }
        return await register(idToken: idToken, knownDeviceId: known, epoch: epoch.withLock { $0 })
    }

    /// `POST /v1/devices`; the token is stored only if no logout or reconfigure happened since `epoch` was read.
    private func register(idToken: String, knownDeviceId known: String?, epoch started: Int) async -> RegisterResult {
        switch await api.register(idToken: idToken, deviceId: known) {
        case .ok(let reg):
            // not stored: a logout came in between, or the Keychain refused it
            guard save(reg, ifEpoch: started) == true else {
                SDKLog.warn("device.register_failed", ["reason": "token_not_stored"])
                return .failed(httpStatus: nil)
            }
            SDKLog.info("device.registered", ["deviceId": reg.deviceId])
            return .success(deviceId: reg.deviceId)
        case let .http(status, retryAfter):
            switch status {
            case 401: SDKLog.warn("auth.failed", ["status": "401", "call": "register"])
            case 429: SDKLog.warn("rate_limit.exceeded", ["retryAfter": retryAfter.map(String.init) ?? "none", "call": "register"])
            default: SDKLog.warn("device.register_failed", ["status": "\(status)"])
            }
            return .failed(httpStatus: status)
        case .networkError:
            SDKLog.warn("device.register_failed", ["reason": "network"])
            return .failed(httpStatus: nil)
        }
    }

    /// Saves `reg` unless a logout or reconfigure happened since `started`: nil then, else whether the store took it.
    /// Under the epoch lock, so a logout either comes after the save (and clears it) or prevents it.
    private func save(_ reg: Registration, ifEpoch started: Int) -> Bool? {
        epoch.withLock { current in current == started ? tokens.save(reg) : nil }
    }

    // MARK: ensureRegistered (spec 3.2)

    /// One run at a time: a call while a run is in progress joins it (and its provider is not called).
    func ensureRegistered(idTokenProvider: @escaping @Sendable () async -> String?) async -> RegisterResult {
        let run = ensureRun.withLock { r -> Task<RegisterResult, Never> in
            if let running = r.current { return running.task }
            let id = r.next
            r.next += 1
            let task = Task<RegisterResult, Never> {
                let result = await self.decideAndRegister(idTokenProvider)
                if case .success = result { self.startFlush() } // once per run, not per joined call
                // only its own entry: invalidate() may have detached this run and a newer one may be running
                self.ensureRun.withLock { if $0.current?.id == id { $0.current = nil } }
                return result
            }
            r.current = (id, task)
            return task
        }
        return await run.value
    }

    /// The local decision, in the order of spec 3.2: no token or an expired one → ID token; `renewalNeeded` or under
    /// 20 % left → refresh (a 401 → ID token); otherwise nothing to do.
    private func decideAndRegister(_ idTokenProvider: @escaping @Sendable () async -> String?) async -> RegisterResult {
        let started = epoch.withLock { $0 }
        var known: String?
        switch tokens.load() {
        case .unavailable:
            // Keychain locked or unreadable: registering again could replace a working registration
            SDKLog.warn("device.register_failed", ["reason": "storage_unavailable"])
            return .failed(httpStatus: nil)
        case .notRegistered:
            break
        case let .registered(deviceId, token):
            known = deviceId
            if !token.isExpired(now: env.now()) {
                if !tokens.renewalNeeded && !token.needsRefresh(now: env.now()) { return .alreadyRegistered }
                if let result = await refresh(deviceId: deviceId, token: token, epoch: started) { return result }
            }
        }
        guard let answer = await idToken(from: idTokenProvider) else {
            SDKLog.warn("device.register_skipped", ["reason": "provider_timeout"])
            return .notLoggedIn
        }
        guard let idToken = answer else {
            SDKLog.info("device.register_skipped", ["reason": "not_logged_in"])
            return .notLoggedIn
        }
        return await register(idToken: idToken, knownDeviceId: known, epoch: started)
    }

    /// The provider's answer (`.some(nil)`: not logged in), or nil after `providerTimeout` or when this run is
    /// cancelled. The provider runs in its own task, so one that ignores cancellation cannot hold the run; its late
    /// answer is ignored. Polled every 20 ms (as `FileLock`): a continuation-based race is much larger in binary size,
    /// and the SDK has a size budget (spec 9.2).
    private func idToken(from provider: @escaping @Sendable () async -> String?) async -> String?? {
        let answer = Locked<String??>(nil)
        let ask = Task { let t = await provider(); answer.withLock { $0 = .some(t) } }
        let end = Date().addingTimeInterval(env.providerTimeout)
        while answer.withLock({ $0 }) == nil && !Task.isCancelled && Date() < end { try? await sleep(0.02) }
        ask.cancel()
        return answer.withLock { $0 } // read once: a later answer lands in a box nobody reads
    }

    /// `POST /v1/devices/token:refresh`; nil when the backend answered 401 (the caller takes the ID-token path).
    private func refresh(deviceId: String, token: DeviceToken, epoch started: Int) async -> RegisterResult? {
        switch await api.refresh(token: token.raw) {
        case .ok(let issued):
            let reg = Registration(deviceId: deviceId, deviceToken: issued.deviceToken, expiresAt: issued.expiresAt)
            // not stored: a logout came in between, or the Keychain refused it
            guard save(reg, ifEpoch: started) == true else {
                SDKLog.warn("device.register_failed", ["reason": "token_not_stored", "call": "refresh"])
                return .failed(httpStatus: nil)
            }
            SDKLog.info("token.refreshed", ["deviceId": deviceId])
            return .success(deviceId: deviceId)
        case let .http(status, retryAfter):
            switch status {
            case 401:
                SDKLog.warn("auth.failed", ["status": "401", "call": "refresh"])
                return nil
            case 429:
                SDKLog.warn("rate_limit.exceeded", ["retryAfter": retryAfter.map(String.init) ?? "none", "call": "refresh"])
            default:
                SDKLog.warn("device.register_failed", ["status": "\(status)", "call": "refresh"])
            }
            return .failed(httpStatus: status)
        case .networkError:
            SDKLog.warn("device.register_failed", ["reason": "network", "call": "refresh"])
            return .failed(httpStatus: nil)
        }
    }

    // MARK: logout

    /// Stops this SDK's work for good (logout, reconfigure): no running registration may store a token afterwards, and
    /// every app-side flush is cancelled.
    func invalidate() {
        epoch.withLock { $0 += 1 }
        // detached: a call right after this starts a fresh run instead of joining the cancelled one
        ensureRun.withLock { r -> Task<RegisterResult, Never>? in
            defer { r.current = nil }
            return r.current?.task
        }?.cancel()
        cancelFlush()
    }

    /// Best-effort revoke, then local cleanup in any case. True when the backend confirmed or nothing was registered.
    /// Running registrations and app flushes are stopped first, and the cleanup runs under `flush.lock` (waiting at
    /// most `lockWait`, off the main thread), so no token is saved after the logout.
    func logout() async -> Bool {
        invalidate()
        var deviceId: String?
        let confirmed: Bool
        switch tokens.load() {
        case let .registered(id, token):
            deviceId = id
            if case .ok = await api.revoke(token: token.raw) { confirmed = true } else { confirmed = false }
        case .notRegistered:
            confirmed = true
        case .unavailable:
            deviceId = env.state.deviceId
            confirmed = false
        }
        flushLock.withLock(timeout: Self.lockWait) {
            // again: a registration started during the revoke must not store its token after this clear
            epoch.withLock { $0 += 1 }
            tokens.clear()
            queue.clear()
            env.state.retryNotBefore = nil
        }
        if confirmed {
            SDKLog.info("device.revoked", ["deviceId": deviceId ?? "none"])
        } else {
            SDKLog.warn("device.revoked", ["deviceId": deviceId ?? "none", "confirmed": "false"])
        }
        return confirmed
    }

    /// The registered device id from the Keychain record (I2: the `SharedState` cache may be a restored backup's), or
    /// the cached one while the Keychain is unavailable. Disk only.
    var deviceId: String? {
        switch tokens.load() {
        case .registered(let id, _): return id
        case .notRegistered: return nil
        case .unavailable: return env.state.deviceId
        }
    }

    /// Disk-only registration check: a readable token, or (Keychain locked) the cached device id.
    var isRegistered: Bool { deviceId != nil }

    /// Queues `type` for `trackingId` (disk only). False with WARN `tracking.not_registered` when not registered.
    func record(_ trackingId: String, _ type: EventType) -> Bool {
        guard isRegistered else {
            SDKLog.warn("tracking.not_registered", ["trackingId": trackingId, "type": type.rawValue])
            return false
        }
        enqueue(trackingId, type)
        return true
    }

    /// Queues `type` for `trackingId` (disk only); the caller has checked the registration. Returns the event when it
    /// is on disk, nil on an I/O error.
    @discardableResult
    func enqueue(_ trackingId: String, _ type: EventType) -> QueuedEvent? {
        let event = QueuedEvent(trackingId: trackingId, type: type, occurredAt: env.now())
        return queue.add(event) ? event : nil
    }

    /// Cancels every app-side flush still running (each unwinds and releases `flush.lock` if it holds it).
    func cancelFlush() {
        for task in flushes.withLock({ Array($0.running.values) }) { task.cancel() }
    }

    /// One flush bounded by `timeout`, by cancellation: a sleeping task cancels the flush at the deadline, which also
    /// cancels its in-flight request. Returns once the sender has unwound, so `flush.lock` is released. Cancelling the
    /// calling task has the same effect at once.
    func boundedFlush(timeout: TimeInterval, maxBatches: Int = .max, priority: QueuedEvent? = nil) async -> FlushResult {
        if Task.isCancelled { return .retryLater }
        let sender = EventSender(api: api, tokens: tokens, state: env.state, queue: queue, flushLock: flushLock, now: env.now)
        let deadline = env.now().addingTimeInterval(timeout)
        let flush = Task { await sender.flush(deadline: deadline, maxBatches: maxBatches, priority: priority) }
        let timer = Task {
            try? await sleep(timeout)
            flush.cancel()
        }
        let result = await withTaskCancellationHandler { await flush.value } onCancel: { flush.cancel() }
        timer.cancel()
        return result
    }

    /// App-side flush (R12): runs inside an expiring activity and is bounded by `timeout`. When the activity expires
    /// (or is refused), the flush task is cancelled so `flush.lock` is released before suspension; events stay queued.
    @discardableResult
    func startFlush(timeout: TimeInterval = SDK.appFlushTimeout) -> Task<FlushResult, Never> {
        let finished = DispatchSemaphore(value: 0)
        // created under the lock, so the task's own removal (same lock) always comes after its insertion
        let task = flushes.withLock { f -> Task<FlushResult, Never> in
            let id = f.next
            f.next += 1
            let task = Task<FlushResult, Never> {
                defer {
                    _ = self.flushes.withLock { $0.running.removeValue(forKey: id) }
                    finished.signal()
                }
                return await self.boundedFlush(timeout: timeout)
            }
            f.running[id] = task
            f.last = task
            return task
        }
        env.activity.perform(reason: "io.reliablepush.flush") { expired in
            if expired {
                task.cancel()
                return
            }
            // Keeps the activity (and the app awake) until the flush has unwound and released the lock.
            finished.wait()
        }
        return task
    }

    /// Test hook: the most recent app-side flush.
    var lastFlushForTesting: Task<FlushResult, Never>? { flushes.withLock { $0.last } }
}
