import Foundation

/// Advisory `flock` on a lock file shared by the app and its extension. Every acquisition opens its own descriptor, so
/// two `FileLock`s on one path exclude each other even inside one process (flock is per open file description).
final class FileLock: Sendable {
    private let url: URL

    init(url: URL) { self.url = url }

    /// A held non-blocking lock. Unlocks on `release()` or `deinit`, whichever comes first, exactly once.
    final class Held: Sendable {
        private let fd: Locked<Int32>

        fileprivate init(fd: Int32) { self.fd = Locked(fd) }

        func release() {
            let descriptor = fd.withLock { value -> Int32 in
                defer { value = -1 }
                return value
            }
            if descriptor >= 0 {
                flock(descriptor, LOCK_UN)
                close(descriptor)
            }
        }

        deinit { release() }
    }

    /// Runs `body` while holding the exclusive lock (blocking). If the lock file cannot be opened, `body` still runs
    /// (an event path must not crash or stall); the in-process lock of the caller then remains the only guard.
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        let fd = openFile()
        var locked = false
        if fd >= 0 {
            var result: Int32
            repeat { result = flock(fd, LOCK_EX) } while result != 0 && errno == EINTR
            locked = result == 0
        }
        if !locked { SDKLog.warn("lock.unavailable", ["file": url.lastPathComponent]) }
        defer {
            if fd >= 0 {
                flock(fd, LOCK_UN)
                close(fd)
            }
        }
        return try body()
    }

    /// Runs `body` holding the lock if it can be taken within `timeout` (polled every 20 ms); after that `body` runs
    /// anyway, with WARN `lock.unavailable reason=timeout`. For cleanups (logout, reconfigure) that must not wait for
    /// another process's flush for long, and must happen in any case.
    func withLock<T>(timeout: TimeInterval, _ body: () -> T) -> T {
        let end = Date().addingTimeInterval(timeout)
        var held = tryLock()
        while held == nil && Date() < end {
            usleep(20_000)
            held = tryLock()
        }
        if held == nil { SDKLog.warn("lock.unavailable", ["file": url.lastPathComponent, "reason": "timeout"]) }
        defer { held?.release() }
        return body()
    }

    /// Polls `tryLock` every 20 ms until it succeeds, `now()` reaches `deadline` or the task is cancelled (then nil).
    /// A cancelled task never takes the lock. Never blocks a thread: the wait is a task sleep.
    func lock(until deadline: Date, now: () -> Date) async -> Held? {
        while !Task.isCancelled {
            if let held = tryLock() { return held }
            if now() >= deadline { return nil }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return nil
    }

    /// Takes the lock without blocking; nil when somebody else holds it (or the file cannot be opened).
    func tryLock() -> Held? {
        let fd = openFile()
        guard fd >= 0 else {
            SDKLog.warn("lock.unavailable", ["file": url.lastPathComponent])
            return nil
        }
        var result: Int32
        repeat { result = flock(fd, LOCK_EX | LOCK_NB) } while result != 0 && errno == EINTR
        guard result == 0 else {
            close(fd)
            return nil
        }
        return Held(fd: fd)
    }

    /// Opens (creating it and its directory if needed) and sets the protection class every time: a lock file left by
    /// an earlier version may have the host app's default class (I1).
    private func openFile() -> Int32 {
        var fd = open(url.path, O_CREAT | O_RDWR | O_CLOEXEC, 0o600)
        if fd < 0 {
            try? FileProtection.createDirectory(url.deletingLastPathComponent())
            fd = open(url.path, O_CREAT | O_RDWR | O_CLOEXEC, 0o600)
        }
        if fd >= 0 { FileProtection.apply(url) }
        return fd
    }
}

/// Data Protection class of the queue directory and its lock files: `completeUntilFirstUserAuthentication`, the class
/// of `events.json`, so an extension can open them while the device is locked (after the first unlock) even when the
/// host app's default class is `complete` (I1).
enum FileProtection {
    /// Test seam: receives every request for the class instead of `FileManager.setAttributes`.
    typealias Setter = @Sendable (_ path: String, _ attributes: [FileAttributeKey: Any]) throws -> Void

    private static let setter = Locked<Setter?>(nil)

    static var setterForTesting: Setter? {
        get { setter.withLock { $0 } }
        set { setter.withLock { $0 = newValue } }
    }

    static var attributes: [FileAttributeKey: Any] {
        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
    }

    /// Sets the class on an existing item. A refusal is not fatal: WARN `file.protection_failed`.
    static func apply(_ url: URL) {
        do {
            if let s = setterForTesting {
                try s(url.path, attributes)
            } else {
                try FileManager.default.setAttributes(attributes, ofItemAtPath: url.path)
            }
        } catch {
            SDKLog.warn("file.protection_failed", ["file": url.lastPathComponent])
        }
    }

    /// Creates the directory (and missing parents) with the class.
    static func createDirectory(_ url: URL) throws {
        if let s = setterForTesting {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            try s(url.path, attributes)
        } else {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: attributes)
        }
    }
}
