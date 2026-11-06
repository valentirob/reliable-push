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
    /// An event the server did not acknowledge in this many sent batches is dropped (Android `MAX_MISSES`).
    static let maxMisses = 2

    private let directory: URL
    private let fileURL: URL
    private let fileLock: FileLock
    private let processLock = NSLock()

    init(directory: URL) {
        self.directory = directory
        fileURL = directory.appendingPathComponent("events.json")
        fileLock = FileLock(url: directory.appendingPathComponent("events.lock"))
    }

    /// One stored event: the event's JSON plus `misses` (absent means 0).
    private struct Entry {
        let event: QueuedEvent
        var misses = 0

        init(_ e: QueuedEvent) { event = e }

        /// Decodes one array element on its own (nil drops just this element).
        init?(json: Any) {
            guard let json = json as? JSONObject, let e = QueuedEvent(json: json) else { return nil }
            event = e
            misses = json["misses"] as? Int ?? 0
        }

        var json: JSONObject {
            var j = event.json
            if misses > 0 { j["misses"] = misses }
            return j
        }
    }

    func add(_ e: QueuedEvent) {
        var dropped = 0
        var queued = false
        locked {
            guard var items = read() else { return }
            if items.contains(where: { $0.event.key == e.key }) { return }
            items.append(Entry(e))
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
        locked { Array((read() ?? []).lazy.map(\.event).filter { !excluding.contains($0.key) }.prefix(max)) }
    }

    func remove(keys: Set<String>) {
        guard !keys.isEmpty else { return }
        locked {
            guard let items = read() else { return }
            let kept = items.filter { !keys.contains($0.event.key) }
            if kept.count != items.count { _ = write(kept) }
        }
    }

    /// Records that these events were sent but not acknowledged. An event reaching `maxMisses` is removed.
    /// Returns the keys removed.
    func noteUnacknowledged(keys: Set<String>) -> Set<String> {
        guard !keys.isEmpty else { return [] }
        return locked {
            guard var items = read() else { return [] }
            var gone = Set<String>()
            var changed = false
            items = items.compactMap { entry in
                guard keys.contains(entry.event.key) else { return entry }
                var e = entry
                e.misses += 1
                changed = true
                if e.misses >= Self.maxMisses {
                    gone.insert(e.event.key)
                    return nil
                }
                return e
            }
            if changed { _ = write(items) }
            return gone
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

    /// The stored entries: empty when there is no file; nil on an I/O error (do not overwrite then). Undecodable
    /// elements are dropped, an undecodable file counts as empty; either is rewritten at once so the WARN is not
    /// repeated. Must be called under the lock.
    private func read() -> [Entry]? {
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError {
            return []
        } catch {
            SDKLog.warn("tracking.event_dropped", ["reason": "queue_read_failed"])
            return nil
        }
        guard let all = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else {
            SDKLog.warn("tracking.event_dropped", ["count": "0", "reason": "queue_corrupt"])
            _ = write([])
            return []
        }
        let entries = all.compactMap(Entry.init(json:))
        if entries.count != all.count {
            SDKLog.warn("tracking.event_dropped", ["count": "\(all.count - entries.count)", "reason": "queue_corrupt"])
            _ = write(entries)
        }
        return entries
    }

    private func write(_ items: [Entry]) -> Bool {
        do {
            try FileProtection.createDirectory(directory)
            let data = try JSONSerialization.data(withJSONObject: items.map(\.json))
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            SDKLog.warn("tracking.event_dropped", ["reason": "queue_write_failed"])
            return false
        }
    }
}
