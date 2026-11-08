import Foundation
import UserNotifications

/// Helper for the host's Notification Service Extension: reports `DELIVERED` for the SDK's notifications and always
/// hands the notification back, exactly once.
///
/// Single use: create one instance per request, as the system creates one `UNNotificationServiceExtension` instance
/// per request, and forward both entry points from the extension's synchronous overrides:
///
/// ```swift
/// import UserNotifications
/// import ReliablePush
///
/// final class NotificationService: UNNotificationServiceExtension {
///     private let reliablePush = ReliablePushExtension(appGroup: "group.com.example.app")
///
///     override func didReceive(_ request: UNNotificationRequest,
///                              withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
///         if !reliablePush.didReceive(request, withContentHandler: contentHandler) {
///             contentHandler(request.content) // not the SDK's push
///         }
///     }
///
///     override func serviceExtensionTimeWillExpire() { reliablePush.serviceExtensionTimeWillExpire() }
/// }
/// ```
///
/// Nothing is read before `didReceive` has found a valid `rp_tid`: the stored configuration (App Group, Keychain
/// access group) is resolved only for the SDK's notifications, so the host's own pushes cost nothing.
///
/// `@unchecked Sendable`: the only mutable state (the pending content handler, the best content so far, the flush
/// task and the expiry flag) is guarded by `lock`. The handler is taken out under the lock and called outside it, so
/// whichever path comes first (flush done, safety timer, expiry) calls it and every later path finds nothing to call:
/// exactly once, with no actor hop that could reorder the paths. The system's handler is not `Sendable`; it is only
/// ever reached through this lock-guarded slot and may be called from any thread. The configuration (`resolve`,
/// `timeout`) is immutable.
public final class ReliablePushExtension: @unchecked Sendable {
    private static let maxSendTimeout: TimeInterval = 25
    private static let gateTimeout: TimeInterval = 2
    private static let safetyMargin: TimeInterval = 0.5

    private let resolve: @Sendable () -> SDKEnvironment?
    private let timeout: TimeInterval

    private let lock = NSLock()
    private var pending: ((UNNotificationContent) -> Void)?
    private var bestContent: UNNotificationContent?
    private var work: Task<Void, Never>?
    private var expired = false

    /// Uses the configuration the app stored with `ReliablePush.configure` in the App Group, read by `didReceive` only
    /// for a request with a valid `rp_tid`. Without one (the app has never been configured, or the App Group cannot be
    /// opened, WARN), such a request is passed through untouched.
    ///
    /// - Parameters:
    ///   - appGroup: The same App Group id the app passes to `configure`.
    ///   - options: The Keychain access group override and `extensionSendTimeout`; see `ReliablePushOptions`. Pass
    ///     the same `keychainAccessGroup` as the app.
    public convenience init(appGroup: String, options: ReliablePushOptions = .init()) {
        self.init(resolve: {
            let env = try? SDKEnvironment.appGroup(appGroup, options: options)
            if env == nil { SDKLog.warn("sdk.app_group_unavailable") }
            return env
        }, options: options)
    }

    convenience init(environment: SDKEnvironment?, options: ReliablePushOptions) {
        self.init(resolve: { environment }, options: options)
    }

    /// `resolve` is called at most once, by `didReceive`, after the `rp_tid` check.
    init(resolve: @escaping @Sendable () -> SDKEnvironment?, options: ReliablePushOptions) {
        self.resolve = resolve
        timeout = min(max(options.extensionSendTimeout, 0), Self.maxSendTimeout)
    }

    /// Handles a notification request in the extension.
    ///
    /// Returns `false`, without calling `handler` and without reading the stored configuration, when the request has
    /// no valid `rp_tid` (a malformed one is logged as WARN), so the host extension can handle its own pushes. Otherwise it
    /// returns `true` and owns `handler`, in this order (spec 3.3):
    /// - the stored configuration is read; without one the content is passed through untouched;
    /// - `customize` is applied to a mutable copy of the content (with the tracking id);
    /// - if the device is registered and the notification settings say it will be shown, `DELIVERED` is persisted in
    ///   the queue, before this method returns;
    /// - one `POST /v1/events` (this event first) runs for what is left of `extensionSendTimeout`, with the stored
    ///   token as it is: the extension never renews the token (a 401 leaves the event queued for the app);
    /// - `handler` is called once with the (customised) content.
    ///
    /// Whenever there is nothing to send (not registered, not shown, the Keychain or the queue cannot be used), the
    /// handler is called at once, before this returns. Tracking never decides whether the notification is shown.
    ///
    /// Call it once per instance, from the extension's synchronous `didReceive(_:withContentHandler:)` override, never
    /// from async code: the settings check and the queue write block the calling thread briefly (the settings read is
    /// capped at 2 s, or at `extensionSendTimeout` when that is shorter, and counts against it; a read that does not
    /// answer in time counts as shown, as iOS shows the content, and only an actual "not shown" answer records
    /// nothing). The send runs in the background.
    ///
    /// - Parameters:
    ///   - request: The request given to `UNNotificationServiceExtension.didReceive(_:withContentHandler:)`.
    ///   - handler: The extension's content handler; called exactly once when this returns `true`.
    ///   - customize: Optional synchronous changes to the content (title, attachments…), with the tracking id (lower case).
    @discardableResult
    public func didReceive(_ request: UNNotificationRequest,
                           withContentHandler handler: @escaping (UNNotificationContent) -> Void,
                           customize: (@Sendable (UNMutableNotificationContent, String) -> Void)? = nil) -> Bool {
        guard case let .valid(trackingId) = TrackingId.parse(request.content.userInfo) else { return false }
        let start = Date()
        let environment = resolve()
        guard let environment, let sdk = SDK.fromStoredConfig(environment) else {
            SDKLog.warn("tracking.not_registered", ["trackingId": trackingId, "reason": "not_configured"])
            handler(request.content)
            return true
        }

        // Best content: the customised copy, else a mutable copy of the original.
        let mutable = request.content.mutableCopy() as? UNMutableNotificationContent
        if let customize, let mutable { customize(mutable, trackingId) }
        let content: UNNotificationContent = mutable ?? request.content

        // DELIVERED goes to disk before the handler can be called by any path (send end, safety timer, expiry).
        let event = record(trackingId, sdk: sdk, environment: environment)
        let expiredEarly = install(handler: handler, content: content)
        guard !expiredEarly, let event else {
            finish(expired: expiredEarly)
            return true
        }

        // The settings read counts against the send budget.
        let remaining = max(timeout - Date().timeIntervalSince(start), 0)
        let task = Task { [self] in
            // Returns once the sender has unwound and released flush.lock; only then is the notification handed back.
            _ = await sdk.boundedFlush(timeout: remaining, maxBatches: 1, priority: event)
            finish(expired: false)
        }
        // An expiry that came in between found no task to cancel: cancel it here.
        if locked({ () -> Bool in
            work = task
            return expired
        }) { task.cancel() }
        // Safety net only (the bounded flush normally returns first): hands the notification back even if the
        // flush has not unwound by then.
        Task { [self] in
            try? await sleep(remaining + Self.safetyMargin)
            task.cancel()
            finish(expired: false)
        }
        return true
    }

    /// Registered check → settings gate → persist. Returns the persisted event when there is something to send with a
    /// readable token; nil when the handler should be called at once (nothing recorded, or nothing to send it with).
    private func record(_ trackingId: String, sdk: SDK, environment: SDKEnvironment) -> QueuedEvent? {
        let sendable: Bool
        switch sdk.tokens.load() {
        case .registered:
            sendable = true
        case .unavailable where environment.state.deviceId != nil:
            sendable = false // registered per the cache, but the Keychain cannot give the token now: the app sends it
        case .unavailable, .notRegistered:
            SDKLog.warn("tracking.not_registered", ["trackingId": trackingId, "type": EventType.delivered.rawValue])
            return nil
        }
        // An unregistered device needs no settings read; the read is capped by what is left of the budget.
        let gateTimeout = min(Self.gateTimeout, max(timeout, Self.safetyMargin))
        // Only a "no" means not shown: a read with no answer leaves the content to iOS, which shows it.
        guard NotificationGate.settings(from: environment.settings, timeout: gateTimeout)?.isShown != false else { return nil }
        guard let event = sdk.enqueue(trackingId, .delivered) else { return nil } // the queue write failed (WARN)
        return sendable ? event : nil
    }

    /// Forward `UNNotificationServiceExtension.serviceExtensionTimeWillExpire()` here. If the content handler has not
    /// been called yet, it is called now with the best content (the customised one, else the original), WARN
    /// `extension.time_expired`, and the flush is cancelled; the event stays queued for the next app launch or
    /// extension run.
    public func serviceExtensionTimeWillExpire() {
        let task = locked { () -> Task<Void, Never>? in
            expired = true
            return work
        }
        task?.cancel()
        finish(expired: true)
    }

    /// Installs the handler and the best content; returns true when the time already expired (then finish at once).
    private func install(handler: @escaping (UNNotificationContent) -> Void, content: UNNotificationContent) -> Bool {
        locked {
            pending = handler
            bestContent = content
            return expired
        }
    }

    /// Calls the pending handler, if any, exactly once.
    private func finish(expired: Bool) {
        let taken = locked { () -> (handler: (UNNotificationContent) -> Void, content: UNNotificationContent)? in
            guard let handler = pending, let content = bestContent else { return nil }
            pending = nil
            bestContent = nil
            return (handler, content)
        }
        guard let taken else { return }
        if expired { SDKLog.warn("extension.time_expired") }
        taken.handler(taken.content)
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
