import Foundation

/// Errors thrown by the SDK's public API.
public enum ReliablePushError: Error, Sendable {
    /// The base URL uses cleartext `http` for a host other than `localhost`, `127.0.0.1` or `::1`.
    case insecureBaseURL
    /// The base URL is not an absolute http(s) URL, or carries user info, a query or a fragment.
    case invalidBaseURL
}

enum BaseURL {
    private static let cleartextHosts: Set<String> = ["localhost", "127.0.0.1", "::1"]

    /// Returns the URL trimmed and without trailing slashes.
    static func validate(_ string: String) throws(ReliablePushError) -> URL {
        var trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard let components = URLComponents(string: trimmed), var host = components.host?.lowercased(), !host.isEmpty,
              let scheme = components.scheme?.lowercased(),
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil
        else { throw .invalidBaseURL }
        if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        switch scheme {
        case "https": break
        case "http": guard cleartextHosts.contains(host) else { throw .insecureBaseURL }
        default: throw .invalidBaseURL
        }
        guard let url = components.url else { throw .invalidBaseURL }
        return url
    }
}
