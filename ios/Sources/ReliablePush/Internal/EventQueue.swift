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

    private struct Entry: Codable {
        let trackingId: String
        let type: EventType
        let occurredAt: Date
        var misses: Int?

        init(_ e: QueuedEvent) {
            trackingId = e.trackingId; type = e.type; occurredAt = e.occurredAt; misses = nil
        }
        var event: QueuedEvent { QueuedEvent(trackingId: trackingId, type: type, occurredAt: occurredAt) }
    }

    /// Decodes one array element without failing the whole array.
    private struct Lossy: Decodable {
        let entry: Entry?
        init(from decoder: any Decoder) throws { entry = try? Entry(from: decoder) }
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
                e.misses = (e.misses ?? 0) + 1
                changed = true
                if e.misses! >= Self.maxMisses {
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
        do {
            let all = try ISO8601Coding.makeDecoder().decode([Lossy].self, from: data)
            let entries = all.compactMap(\.entry)
            if entries.count != all.count {
                SDKLog.warn("tracking.event_dropped", ["count": "\(all.count - entries.count)", "reason": "queue_corrupt"])
                _ = write(entries)
            }
            return entries
        } catch {
            let n = ((try? JSONSerialization.jsonObject(with: data)) as? [Any])?.count ?? 0
            SDKLog.warn("tracking.event_dropped", ["count": "\(n)", "reason": "queue_corrupt"])
            _ = write([])
            return []
        }
    }

    private func write(_ items: [Entry]) -> Bool {
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
