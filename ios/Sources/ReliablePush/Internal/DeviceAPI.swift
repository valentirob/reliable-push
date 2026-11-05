import Foundation

/// Client of the backend's device API (`/v1/devices`, `/v1/events`). Never logs tokens or bodies.
final class DeviceAPI: Sendable {
    static let maxBodyBytes = 64 * 1024

    private let baseURL: URL
    private let appId: String
    private let userAgent: String
    private let timeout: TimeInterval
    private let session: URLSession

    init(baseURL: URL, appId: String, userAgent: String,
         configuration: URLSessionConfiguration = .ephemeral, timeout: TimeInterval = 10) {
        self.baseURL = baseURL
        self.appId = appId
        self.userAgent = userAgent
        self.timeout = timeout
        // The session is built from a copy, so the caller's configuration is not mutated.
        // swiftlint:disable:next force_cast
        let config = configuration.copy() as! URLSessionConfiguration
        config.timeoutIntervalForRequest = timeout
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        session = URLSession(configuration: config, delegate: NoRedirectDelegate(), delegateQueue: nil)
    }

    deinit { session.finishTasksAndInvalidate() }

    // MARK: Endpoints

    func register(idToken: String, deviceId: String?) async -> APIResult<Registration> {
        var body: JSONObject = ["appId": appId, "idToken": idToken, "platform": "IOS"]
        if let deviceId { body["deviceId"] = deviceId }
        return decode(await send("POST", "/v1/devices", token: nil, body: body, expected: 201), Registration.init(json:))
    }

    func refresh(token: String) async -> APIResult<IssuedToken> {
        decode(await send("POST", "/v1/devices/token:refresh", token: token, body: nil, expected: 200), IssuedToken.init(json:))
    }

    func revoke(token: String) async -> APIResult<Void> {
        switch await send("DELETE", "/v1/devices/me", token: token, body: nil, expected: 204) {
        case .ok: return .ok(())
        case let .http(status, retryAfter): return .http(status: status, retryAfterSeconds: retryAfter)
        case .networkError: return .networkError
        }
    }

    func sendEvents(token: String, events: [QueuedEvent]) async -> APIResult<[EventOutcome]> {
        // `items` is optional on our side: a 200 that omits it (or sends null) acknowledges nothing. Any malformed item
        // makes the whole answer unusable, as before.
        decode(await send("POST", "/v1/events", token: token, body: ["events": events.map(\.json)], expected: 200)) { json in
            guard let raw = json["items"], !(raw is NSNull) else { return [] }
            guard let list = raw as? [Any] else { return nil }
            let items = list.compactMap(EventOutcome.init(json:))
            return items.count == list.count ? items : nil
        }
    }

    // MARK: Plumbing

    private struct Raw: Sendable {
        let status: Int
        let body: Data
        let retryAfter: Int?
    }

    /// Performs the call; `.ok` carries the raw body when the status is `expected`.
    private func send(_ method: String, _ path: String, token: String?, body: JSONObject?, expected: Int) async -> APIResult<Data> {
        // Built from the string: appendingPathComponent could re-encode the ':' of `token:refresh`.
        guard let url = URL(string: baseURL.absoluteString + path) else { return .networkError }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, application/problem+json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            guard let data = JSON.data(body) else { return .networkError }
            request.httpBody = data
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        guard let raw = await execute(request) else { return .networkError }
        guard raw.status == expected else { return .http(status: raw.status, retryAfterSeconds: raw.retryAfter) }
        return .ok(raw.body)
    }

    /// An empty body counts as `{}`; a body that is not a JSON object, or that `map` rejects, is a `.networkError`.
    private func decode<T: Sendable>(_ result: APIResult<Data>, _ map: (JSONObject) -> T?) -> APIResult<T> {
        switch result {
        case let .ok(data):
            guard let json = data.isEmpty ? [:] : JSON.object(data), let value = map(json) else { return .networkError }
            return .ok(value)
        case let .http(status, retryAfter): return .http(status: status, retryAfterSeconds: retryAfter)
        case .networkError: return .networkError
        }
    }

    /// Runs the request against a hard deadline (also effective when a `URLProtocol` ignores the session timeout).
    private func execute(_ request: URLRequest) async -> Raw? {
        let session = self.session
        let timeout = self.timeout
        return await withTaskGroup(of: Raw?.self) { group in
            group.addTask { await Self.fetch(session, request) }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private static func fetch(_ session: URLSession, _ request: URLRequest) async -> Raw? {
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { return nil }
            if http.expectedContentLength > Int64(maxBodyBytes) { return nil }
            var data = Data()
            for try await byte in bytes {
                if data.count >= maxBodyBytes { return nil }
                data.append(byte)
            }
            return Raw(status: http.statusCode, body: data, retryAfter: parseRetryAfter(http.value(forHTTPHeaderField: "Retry-After")))
        } catch {
            return nil
        }
    }

    /// Integer seconds only; anything else (dates, signs, junk) is ignored.
    static func parseRetryAfter(_ value: String?) -> Int? {
        guard let s = value?.trimmingCharacters(in: .whitespaces), !s.isEmpty, s.allSatisfy({ $0 >= "0" && $0 <= "9" }) else { return nil }
        return Int(s)
    }
}

/// Refuses every redirect: the 3xx answer itself becomes the result, so the bearer token never leaves the base URL.
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
