import Foundation

/// A recorded request, with the body drained from the upload stream.
struct RecordedRequest: Sendable {
    let url: URL
    let method: String
    let headers: [String: String]
    let body: Data
}

/// `URLProtocol` stub registered on a test session configuration. Records requests and serves canned replies.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    enum Reply: Sendable {
        case response(status: Int, headers: [String: String] = [:], body: Data = Data())
        case redirect(status: Int, to: String)
        case hang
        /// A transport error (`URLError.notConnectedToInternet`), at once.
        case fail
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var handler: (@Sendable (URLRequest) -> Reply)?
    nonisolated(unsafe) private static var recorded: [RecordedRequest] = []

    static func reset(_ handler: (@Sendable (URLRequest) -> Reply)? = nil) {
        lock.lock(); defer { lock.unlock() }
        self.handler = handler
        recorded = []
    }

    static var requests: [RecordedRequest] {
        lock.lock(); defer { lock.unlock() }
        return recorded
    }

    static func configuration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return config
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let request = self.request
        var body = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                body.append(buffer, count: n)
            }
            stream.close()
        }
        let reply: Reply
        Self.lock.lock()
        Self.recorded.append(RecordedRequest(
            url: request.url!, method: request.httpMethod ?? "GET",
            headers: request.allHTTPHeaderFields ?? [:], body: body))
        let handler = Self.handler
        Self.lock.unlock()
        reply = handler?(request) ?? .hang

        switch reply {
        case .hang:
            return
        case .fail:
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
        case let .response(status, headers, data):
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        case let .redirect(status, to):
            let target = URL(string: to)!
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Location": to])!
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: target), redirectResponse: response)
            // A refused redirect makes the 3xx answer itself the final response, as with a real server.
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}
