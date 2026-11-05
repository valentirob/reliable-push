import ReliablePush
import SwiftUI
import UserNotifications

/// The customer's backend as the sample needs it: an ID token (here a signed grant) for the signed-in user, and the
/// announcement of the registered device. Debug builds use the harness (`HarnessClient`); Release builds have none.
protocol SampleBackend: Sendable {
    func idToken(appId: String, userId: String) async throws -> String
    func deviceRegistered(appId: String, userId: String, deviceId: String) async throws
    /// RP_APNS=1 only: the hex APNs device token of this device, for the harness's real pushes.
    func apnsToken(deviceId: String, token: String) async throws
}

struct SampleError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

/// State of the one screen. The labels are what the UI tests read (accessibility identifiers in `ContentView`).
@MainActor
final class SampleModel: ObservableObject {
    @Published var deviceId: String?
    @Published var status = "starting"
    @Published var notificationAuth = "unknown"
    @Published var lastPush = "none"
    @Published var busy = false

    let config: SampleConfig
    let backend: (any SampleBackend)?

    init(config: SampleConfig) {
        self.config = config
        #if DEBUG
        backend = HarnessClient(baseURL: config.harnessURL)
        #else
        backend = nil
        #endif
    }

    func configured() {
        deviceId = ReliablePush.deviceId
        status = deviceId == nil ? "configured" : "registered"
    }

    #if DEBUG
    private var apnsToken: String?

    /// RP_APNS=1: the token may arrive before or after the registration; it is reported once both exist.
    func apnsTokenReceived(_ token: String) {
        apnsToken = token
        reportApnsToken()
    }

    private func reportApnsToken() {
        guard config.apns, let backend, let token = apnsToken, let deviceId else { return }
        Task {
            do {
                try await backend.apnsToken(deviceId: deviceId, token: token)
                sampleLog.info("event=sample.apns_token_reported")
            } catch {
                sampleLog.error("event=sample.apns_token_report_failed")
            }
        }
    }
    #endif

    /// Grant from the backend -> `ReliablePush.register` -> the device announced to the backend.
    func register() {
        guard let backend, !busy else { return }
        busy = true
        status = "registering"
        let config = config
        Task {
            defer { busy = false }
            do {
                let idToken = try await backend.idToken(appId: config.appId, userId: config.userId)
                switch await ReliablePush.register(idToken: idToken) {
                case let .success(deviceId):
                    try await backend.deviceRegistered(appId: config.appId, userId: config.userId, deviceId: deviceId)
                    self.deviceId = deviceId
                    status = "registered"
                    #if DEBUG
                    reportApnsToken()
                    #endif
                case let .failed(httpStatus):
                    status = "register failed (\(httpStatus.map(String.init) ?? "no HTTP status"))"
                }
            } catch {
                status = "register failed: \(error)"
            }
        }
    }

    func logout() {
        status = "logging out"
        ReliablePush.logout { [weak self] confirmed in
            self?.deviceId = ReliablePush.deviceId
            self?.status = confirmed ? "logged out (confirmed)" : "logged out (not confirmed)"
        }
    }

    /// Asks for notification authorization on the first launch (the system prompts only while it is undecided),
    /// then shows the decision. Not when the app only hosts unit tests: nobody would answer the prompt.
    func refreshNotificationAuthorization() async {
        var status = await Self.authorizationStatus()
        if status == .notDetermined && ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            notificationAuth = "requesting"
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            status = await Self.authorizationStatus()
        }
        notificationAuth = Self.name(status)
    }

    nonisolated private static func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    private static func name(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: "notDetermined"
        case .denied: "denied"
        case .authorized: "authorized"
        case .provisional: "provisional"
        case .ephemeral: "ephemeral"
        @unknown default: "unknown"
        }
    }
}

struct ContentView: View {
    @ObservedObject var model: SampleModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Reliable Push sample").font(.title2).bold()
            row("Device id", model.deviceId ?? "not registered", id: "deviceId")
            row("Status", model.status, id: "status")
            row("Notifications", model.notificationAuth, id: "notificationAuth")
            #if DEBUG
            row("Last debug push", model.lastPush, id: "lastPush")
            row("Process", "\(ProcessInfo.processInfo.processIdentifier)", id: "pid")
            #endif
            HStack(spacing: 24) {
                Button("Register") { model.register() }
                    .accessibilityIdentifier("register")
                    .disabled(model.backend == nil || model.busy)
                Button("Logout") { model.logout() }
                    .accessibilityIdentifier("logout")
            }
            if model.backend == nil {
                Text("Release build: register with your app's ID token after sign-in.").font(.footnote)
            }
            Spacer()
        }
        .padding()
        .task { await model.refreshNotificationAuthorization() }
    }

    private func row(_ title: String, _ value: String, id: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(value).font(.body.monospaced()).accessibilityIdentifier(id)
        }
    }
}
