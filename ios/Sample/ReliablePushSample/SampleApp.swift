import SwiftUI

/// The Reliable Push sample app: one screen to register, log out and see the device id. The SDK is configured in
/// `AppDelegate`, which is also the notification center delegate (READ on tap).
@main
struct SampleApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView(model: appDelegate.model)
            #if DEBUG
                // rpsample://debug-push?... : the Simulator path for a push (see DebugPush)
                .onOpenURL { url in DebugPush.handle(url, model: appDelegate.model) }
            #endif
        }
    }
}
