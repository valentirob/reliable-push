import Foundation

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

    init(state: SharedState, queueDirectory: URL, secrets: any SecretStore, settings: any NotificationSettingsSource,
         sessionConfiguration: @escaping @Sendable () -> URLSessionConfiguration, now: @escaping @Sendable () -> Date,
         activity: any ExpiringActivityRunner) {
        self.state = state
        self.queueDirectory = queueDirectory
        self.secrets = secrets
        self.settings = settings
        self.sessionConfiguration = sessionConfiguration
        self.now = now
        self.activity = activity
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
/// Process-wide state lives only here (`current`, the once-per-launch flush flag) and in `RefreshGate`.
final class SDK: Sendable {
    static let userAgent = "reliable-push-ios/\(ReliablePush.version)"
    /// Upper bound of an app-side flush (R12: the flush holds `flush.lock` in the shared container across network
    /// I/O, and iOS kills a suspended app holding such a lock — 0xdead10cc).
    static let appFlushTimeout: TimeInterval = 25
    static let requestTimeout: TimeInterval = 10

    let baseURL: URL
    let appId: String
    let env: SDKEnvironment
    let api: DeviceAPI
    let tokens: TokenStore
    let queue: EventQueue
    let flushLock: FileLock
    private let lastFlush = Locked<Task<FlushResult, Never>?>(nil)

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
    static func configure(baseURL: URL, appId: String, env: SDKEnvironment) {
        let (sdk, startFlush) = process.withLock { p -> (SDK, Bool) in
            let sdk: SDK
            if let current = p.current, current.baseURL == baseURL, current.appId == appId {
                sdk = current
            } else {
                sdk = SDK(baseURL: baseURL, appId: appId, env: env)
                sdk.applyConfig()
                p.current = sdk
            }
            let start = !p.launchFlushStarted
            p.launchFlushStarted = true
            return (sdk, start)
        }
        if startFlush { sdk.startFlush() }
    }

    static func resetForTesting() { process.withLock { $0 = Process() } }

    private func applyConfig() {
        let state = env.state
        if let stored = state.config {
            if stored.baseURL.absoluteString == baseURL.absoluteString && stored.appId == appId { return }
            TokenStore(secrets: env.secrets, state: state, appId: stored.appId).clear()
            tokens.clear()
            queue.clear()
            state.retryNotBefore = nil
            SDKLog.info("sdk.reconfigured")
        }
        state.config = (baseURL, appId)
    }

    // MARK: Operations

    func register(idToken: String) async -> RegisterResult {
        var known: String?
        if case let .registered(id, _) = tokens.load() { known = id }
        switch await api.register(idToken: idToken, deviceId: known) {
        case .ok(let reg):
            guard tokens.save(reg) else {
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

    /// Best-effort revoke, then local cleanup in any case. True when the backend confirmed or nothing was registered.
    func logout() async -> Bool {
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
        tokens.clear()
        queue.clear()
        env.state.retryNotBefore = nil
        if confirmed {
            SDKLog.info("device.revoked", ["deviceId": deviceId ?? "none"])
        } else {
            SDKLog.warn("device.revoked", ["deviceId": deviceId ?? "none", "confirmed": "false"])
        }
        return confirmed
    }

    /// Disk-only registration check: a readable token, or (Keychain locked) the cached device id.
    var isRegistered: Bool {
        switch tokens.load() {
        case .registered: return true
        case .notRegistered: return false
        case .unavailable: return env.state.deviceId != nil
        }
    }

    /// Queues `type` for `trackingId` (disk only). False with WARN `tracking.not_registered` when not registered.
    @discardableResult
    func record(_ trackingId: String, _ type: EventType) -> Bool {
        guard isRegistered else {
            SDKLog.warn("tracking.not_registered", ["trackingId": trackingId, "type": type.rawValue])
            return false
        }
        queue.add(QueuedEvent(trackingId: trackingId, type: type, occurredAt: env.now()))
        return true
    }

    /// One flush bounded by `timeout`: the deadline is raced against the sender, whose in-flight request is then
    /// cancelled. Returns once the sender has unwound, so `flush.lock` is released. Cancelling the calling task has
    /// the same effect at once.
    func boundedFlush(timeout: TimeInterval) async -> FlushResult {
        if Task.isCancelled { return .retryLater }
        let sender = EventSender(api: api, tokens: tokens, state: env.state, queue: queue, flushLock: flushLock, now: env.now)
        let deadline = env.now().addingTimeInterval(timeout)
        let nanos = UInt64(max(timeout, 0) * 1_000_000_000)
        return await withTaskGroup(of: FlushResult?.self) { group in
            group.addTask { await sender.flush(deadline: deadline) }
            group.addTask {
                try? await Task.sleep(nanoseconds: nanos)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? .retryLater
        }
    }

    /// App-side flush (R12): runs inside an expiring activity and is bounded by `timeout`. When the activity expires
    /// (or is refused), the flush task is cancelled so `flush.lock` is released before suspension; events stay queued.
    @discardableResult
    func startFlush(timeout: TimeInterval = SDK.appFlushTimeout) -> Task<FlushResult, Never> {
        let finished = DispatchSemaphore(value: 0)
        let task = Task<FlushResult, Never> {
            defer { finished.signal() }
            return await self.boundedFlush(timeout: timeout)
        }
        lastFlush.withLock { $0 = task }
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
    var lastFlushForTesting: Task<FlushResult, Never>? { lastFlush.withLock { $0 } }
}
