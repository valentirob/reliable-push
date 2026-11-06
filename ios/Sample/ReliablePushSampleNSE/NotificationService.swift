import ReliablePush
import UserNotifications

/// The sample's Notification Service Extension: `ReliablePushExtension` reports DELIVERED when the notification will
/// be shown and calls the content handler exactly once; any other push passes through untouched.
final class NotificationService: UNNotificationServiceExtension {
    private let reliablePush = ReliablePushExtension(
        appGroup: Bundle.main.object(forInfoDictionaryKey: "RPAppGroup") as? String ?? "")

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        if !reliablePush.didReceive(request, withContentHandler: contentHandler) {
            contentHandler(request.content) // not a reliable-push notification
        }
    }

    override func serviceExtensionTimeWillExpire() {
        reliablePush.serviceExtensionTimeWillExpire()
    }
}
