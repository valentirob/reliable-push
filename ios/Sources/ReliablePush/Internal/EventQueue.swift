import Foundation

extension QueuedEvent {
    /// Identity of an event in the queue: tracking id + type.
    var key: String { "\(trackingId)|\(type.rawValue)" }
}

/// Persistent FIFO of events waiting to be sent, `events.json` in `directory`
/// (`<container>/Library/Application Support/ReliablePush`). Capped at 500 (oldest dropped, WARN); an equal key is
/// not added twice. The app and the NSE are separate processes, so every operation holds `events.lock` (flock) and
/// reads the file fresh. A corrupt file counts as empty and is overwritten by the next write. A write failure loses
/// the change but never crashes.
final class EventQueue: Sendable {
    static let cap = 500

    private let directory: URL
    private let fileURL: URL
    private let fileLock: FileLock
    private let processLock = NSLock()

    init(directory: URL) {
        self.directory = directory
        fileURL = directory.appendingPathComponent("events.json")
        fileLock = FileLock(url: directory.appendingPathComponent("events.lock"))
    }

    func add(_ e: QueuedEvent) {
        var dropped = 0
        var queued = false
        locked {
            guard var items = read() else { return }
            if items.contains(where: { $0.key == e.key }) { return }
            items.append(e)
            while items.count > Self.cap {
                items.removeFirst()
                dropped += 1
            }
            queued = write(items)
        }
        if queued { SDKLog.info("tracking.event_queued", ["trackingId": e.trackingId, "type": e.type.rawValue]) }
        if dropped > 0 { SDKLog.warn("tracking.event_dropped", ["count": "\(dropped)", "reason": "queue_full"]) }
    }

    /// The oldest `max` events whose key is not in `excluding`.
    func peek(max: Int, excluding: Set<String> = []) -> [QueuedEvent] {
        locked { Array((read() ?? []).lazy.filter { !excluding.contains($0.key) }.prefix(max)) }
    }

    func remove(keys: Set<String>) {
        guard !keys.isEmpty else { return }
        locked {
            guard let items = read() else { return }
            let kept = items.filter { !keys.contains($0.key) }
            if kept.count != items.count { _ = write(kept) }
        }
    }

    func clear() {
        locked { try? FileManager.default.removeItem(at: fileURL) }
    }

    var count: Int { locked { read()?.count ?? 0 } }

    private func locked<T>(_ body: () -> T) -> T {
        processLock.lock()
        defer { processLock.unlock() }
        return fileLock.withLock(body)
    }

    /// The stored events: empty when there is no file or it is corrupt; nil on an I/O error (do not overwrite then).
    private func read() -> [QueuedEvent]? {
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError {
            return []
        } catch {
            SDKLog.warn("tracking.event_dropped", ["reason": "queue_read_failed"])
            return nil
        }
        do {
            return try ISO8601Coding.makeDecoder().decode([QueuedEvent].self, from: data)
        } catch {
            let n = ((try? JSONSerialization.jsonObject(with: data)) as? [Any])?.count ?? 0
            SDKLog.warn("tracking.event_dropped", ["count": "\(n)", "reason": "queue_corrupt"])
            return []
        }
    }

    private func write(_ items: [QueuedEvent]) -> Bool {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try ISO8601Coding.makeEncoder().encode(items)
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            SDKLog.warn("tracking.event_dropped", ["reason": "queue_write_failed"])
            return false
        }
    }
}
