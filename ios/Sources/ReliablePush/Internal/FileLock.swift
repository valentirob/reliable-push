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

    private func openFile() -> Int32 {
        var fd = open(url.path, O_CREAT | O_RDWR | O_CLOEXEC, 0o600)
        if fd < 0 {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            fd = open(url.path, O_CREAT | O_RDWR | O_CLOEXEC, 0o600)
        }
        return fd
    }
}
