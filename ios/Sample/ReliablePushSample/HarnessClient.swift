#if DEBUG
import Foundation

/// Debug build only: the harness console API (`/console/api/…`), which plays the customer's backend for the sample:
/// it mints the signed grant (the ID token of the harness's `signed-grant` identity) and lists the registered device.
/// Every POST carries `Content-Type: application/json` (the console API requires it). The grant is a credential: it is
/// returned to the caller, never logged or shown.
struct HarnessClient: SampleBackend {
    let baseURL: String

    private static let session = URLSession(configuration: .ephemeral)

    func idToken(appId: String, userId: String) async throws -> String {
        let json = try await post("/console/api/grants", ["appId": appId, "userId": userId], expect: 201)
        guard let grant = json["grant"] as? String, !grant.isEmpty else { throw SampleError("no grant in the answer") }
        return grant
    }

    func deviceRegistered(appId: String, userId: String, deviceId: String) async throws {
        let device = ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "iPhone"
        _ = try await post("/console/api/real-devices", [
            "appId": appId, "userId": userId, "deviceId": deviceId, "platform": "IOS", "label": "\(device) (sample)",
        ], expect: 201)
    }

    private func post(_ path: String, _ body: [String: String], expect: Int) async throws -> [String: Any] {
        guard let url = URL(string: baseURL + path) else { throw SampleError("bad harness URL") }
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await Self.session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        // the status only: an answer body may hold the grant
        guard status == expect else { throw SampleError("POST \(path) answered \(status)") }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
    }
}
#endif
