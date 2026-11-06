/// At most one token refresh per process (the app and the NSE are separate processes).
enum RefreshGate {
    private static let used = Locked(false)

    /// True the first time it is called in this process, false afterwards.
    static func tryAcquire() -> Bool {
        used.withLock { v in
            if v { return false }
            v = true
            return true
        }
    }

    static func resetForTesting() { used.withLock { $0 = false } }
}
