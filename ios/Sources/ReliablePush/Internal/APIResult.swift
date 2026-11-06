/// Outcome of one backend call. `.http` carries any status other than the contract's success status.
enum APIResult<T: Sendable>: Sendable {
    case ok(T)
    /// `retryAfterSeconds` is the numeric `Retry-After` header, if present and valid (not clamped here).
    case http(status: Int, retryAfterSeconds: Int?)
    /// Transport failure, timeout, oversized or undecodable answer.
    case networkError
}
