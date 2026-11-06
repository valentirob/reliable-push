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
/// `@unchecked Sendable`: the only mutable state (the pending content handler, the best content so far, the flush
/// task and the expiry flag) is guarded by `lock`. The handler is taken out under the lock and called outside it, so
/// whichever path comes first (flush done, safety timer, expiry) calls it and every later path finds nothing to call:
/// exactly once, with no actor hop that could reorder the paths. The system's handler is not `Sendable`; it is only
/// ever reached through this lock-guarded slot and may be called from any thread. The configuration (`sdk`,
/// `timeout`) is immutable.
public final class ReliablePushExtension: @unchecked Sendable {
    private static let maxSendTimeout: TimeInterval = 25
    private static let gateTimeout: TimeInterval = 2
    private static let safetyMargin: TimeInterval = 0.5

    private let sdk: SDK?
    private let settings: (any NotificationSettingsSource)?
    private let timeout: TimeInterval

    private let lock = NSLock()
    private var pending: ((UNNotificationContent) -> Void)?
    private var bestContent: UNNotificationContent?
    private var work: Task<Void, Never>?
    private var expired = false

    /// Reads the configuration the app stored with `ReliablePush.configure` in the App Group. Without one (the app
    /// has never been configured, or the App Group cannot be opened, WARN), every request is passed through untouched.
    ///
    /// - Parameters:
    ///   - appGroup: The same App Group id the app passes to `configure`.
    ///   - options: The Keychain access group override and `extensionSendTimeout`; see `ReliablePushOptions`. Pass
    ///     the same `keychainAccessGroup` as the app.
    public convenience init(appGroup: String, options: ReliablePushOptions = .init()) {
        let env = try? SDKEnvironment.appGroup(appGroup, options: options)
        if env == nil { SDKLog.warn("sdk.app_group_unavailable") }
        self.init(environment: env, options: options)
    }

    init(environment: SDKEnvironment?, options: ReliablePushOptions) {
        sdk = environment.flatMap(SDK.fromStoredConfig)
        settings = environment?.settings
        timeout = min(max(options.extensionSendTimeout, 0), Self.maxSendTimeout)
    }

    /// Handles a notification request in the extension.
    ///
    /// Returns `false`, without calling `handler`, when the request has no valid `rp_tid` (a malformed one is logged
    /// as WARN), so the host extension can handle its own pushes. Otherwise it returns `true` and owns `handler`:
    /// - it applies `customize` to a mutable copy of the content (with the tracking id);
    /// - if the device is registered, it checks the notification settings; only if the notification will be shown is
    ///   `DELIVERED` persisted in the queue, before this method returns;
    /// - it flushes the queue for what is left of `extensionSendTimeout`, then calls `handler` once with the
    ///   (customised) content.
    ///
    /// When the device is not registered, `handler` is called at once and no event is recorded (WARN
    /// `tracking.not_registered`). Without a stored configuration the content is passed through untouched.
    ///
    /// Call it once per instance, from the extension's synchronous `didReceive(_:withContentHandler:)` override, never
    /// from async code: the settings check and the queue write block the calling thread briefly (the settings read is
    /// capped at 2 s whatever `extensionSendTimeout` is; a source that does not answer counts as not shown). The send
    /// runs in the background.
    ///
    /// - Parameters:
    ///   - request: The request given to `UNNotificationServiceExtension.didReceive(_:withContentHandler:)`.
    ///   - handler: The extension's content handler; called exactly once when this returns `true`.
    ///   - customize: Optional synchronous changes to the content (title, attachments…), with the tracking id.
    @discardableResult
    public func didReceive(_ request: UNNotificationRequest,
                           withContentHandler handler: @escaping (UNNotificationContent) -> Void,
                           customize: (@Sendable (UNMutableNotificationContent, String) -> Void)? = nil) -> Bool {
        guard case let .valid(trackingId) = TrackingId.parse(request.content.userInfo) else { return false }
        let start = Date()
        guard let sdk, let settings else {
            SDKLog.warn("tracking.not_registered", ["trackingId": trackingId, "reason": "not_configured"])
            handler(request.content)
            return true
        }

        // Best content: the customised copy, else a mutable copy of the original.
        let mutable = request.content.mutableCopy() as? UNMutableNotificationContent
        if let customize, let mutable { customize(mutable, trackingId) }
        let content: UNNotificationContent = mutable ?? request.content

        // DELIVERED goes to disk before the handler can be called by any path (flush end, safety timer, expiry).
        // Registration first: an unregistered device needs no settings read.
        let registered = sdk.isRegistered
        if !registered {
            SDKLog.warn("tracking.not_registered", ["trackingId": trackingId, "type": EventType.delivered.rawValue])
        } else if NotificationGate.settings(from: settings, timeout: Self.gateTimeout)?.isShown == true {
            sdk.enqueue(trackingId, .delivered)
        }

        let expiredEarly = install(handler: handler, content: content)
        if expiredEarly || !registered {
            finish(expired: expiredEarly)
            return true
        }

        // The settings read counts against the send budget.
        let remaining = max(timeout - Date().timeIntervalSince(start), 0)
        let task = Task { [self] in
            // Returns once the sender has unwound and released flush.lock; only then is the notification handed back.
            _ = await sdk.boundedFlush(timeout: remaining)
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
