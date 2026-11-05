import XCTest
@testable import ReliablePush

final class EventQueueTests: XCTestCase {
    private var dir: URL!
    private var warnings: WarnBox!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("rp-queue-\(UUID().uuidString)/Library/Application Support/ReliablePush")
        warnings = WarnBox()
        let box = warnings!
        SDKLog.sink = { level, message in if level == .warn { box.add(message) } }
    }

    override func tearDown() {
        SDKLog.sink = nil
        try? FileManager.default.removeItem(at: dir.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent())
    }

    private func event(_ id: String, _ type: EventType = .delivered) -> QueuedEvent {
        QueuedEvent(trackingId: id, type: type, occurredAt: Date(timeIntervalSince1970: 1_700_000_000))
    }

    func testQueueSurvivesNewInstance() {
        let a = EventQueue(directory: dir)
        a.add(event("t1")); a.add(event("t2", .read))
        let b = EventQueue(directory: dir)
        XCTAssertEqual(b.count, 2)
        XCTAssertEqual(b.peek(max: 10).map(\.trackingId), ["t1", "t2"])
        XCTAssertEqual(b.peek(max: 10)[1].type, .read)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent("events.json").path))
    }

    func testCapDropsOldestWithWarn() throws {
        // Seed a full file directly (500 single adds would be slow), then overflow through the API.
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try ISO8601Coding.makeEncoder().encode((0..<500).map { event("t\($0)") }).write(to: dir.appendingPathComponent("events.json"))
        let q = EventQueue(directory: dir)
        XCTAssertEqual(q.count, 500)
        for i in 500..<503 { q.add(event("t\(i)")) }
        XCTAssertEqual(q.count, 500)
        XCTAssertEqual(q.peek(max: 1).first?.trackingId, "t3")
        XCTAssertTrue(warnings.messages.contains { $0.contains("tracking.event_dropped") && $0.contains("queue_full") })
    }

    func testSameKeyIsNotAddedTwice() {
        let q = EventQueue(directory: dir)
        q.add(event("t1")); q.add(event("t1")); q.add(event("t1", .read))
        XCTAssertEqual(q.count, 2)
    }

    func testRemoveByKeyAndClear() {
        let q = EventQueue(directory: dir)
        q.add(event("t1")); q.add(event("t2"))
        q.remove(keys: [event("t1").key])
        XCTAssertEqual(q.peek(max: 10).map(\.trackingId), ["t2"])
        q.clear()
        XCTAssertEqual(q.count, 0)
    }

    func testCorruptFileIsEmptyAndOverwritten() throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("{not json".utf8).write(to: dir.appendingPathComponent("events.json"))
        let q = EventQueue(directory: dir)
        XCTAssertEqual(q.count, 0)
        XCTAssertTrue(warnings.messages.contains { $0.contains("tracking.event_dropped") && $0.contains("queue_corrupt") })
        q.add(event("t1"))
        XCTAssertEqual(EventQueue(directory: dir).count, 1)
    }

    func testConcurrentEnqueueKeepsEveryEvent() async {
        let q = EventQueue(directory: dir)
        await withTaskGroup(of: Void.self) { group in
            for t in 0..<8 {
                group.addTask { for i in 0..<50 { q.add(QueuedEvent(trackingId: "t\(t)-\(i)", type: .delivered, occurredAt: Date())) } }
            }
        }
        XCTAssertEqual(q.count, 400)
    }

    func testTwoQueuesOnOneDirectoryKeepEveryEvent() throws {
        let a = EventQueue(directory: dir), b = EventQueue(directory: dir)
        let group = DispatchGroup()
        for (n, q) in [a, b].enumerated() {
            for t in 0..<4 {
                DispatchQueue.global().async(group: group) {
                    for i in 0..<25 {
                        let e = QueuedEvent(trackingId: "q\(n)t\(t)-\(i)", type: .delivered, occurredAt: Date())
                        q.add(e)
                        // interleave: also add-then-remove a throwaway event
                        let tmp = QueuedEvent(trackingId: "tmp\(n)t\(t)-\(i)", type: .read, occurredAt: Date())
                        q.add(tmp); q.remove(keys: [tmp.key])
                    }
                }
            }
        }
        group.wait()
        XCTAssertEqual(a.count, 200)
        XCTAssertEqual(Set(b.peek(max: 500).map(\.trackingId)).count, 200)
        let data = try Data(contentsOf: dir.appendingPathComponent("events.json"))
        XCTAssertNoThrow(try JSONSerialization.jsonObject(with: data))
    }

    func testFileLockExcludesAcrossInstancesAndReleasesOnce() {
        let url = dir.appendingPathComponent("x.lock")
        let a = FileLock(url: url), b = FileLock(url: url)
        let held = a.tryLock()
        XCTAssertNotNil(held)
        XCTAssertNil(b.tryLock())
        held?.release()
        held?.release()
        let again = b.tryLock()
        XCTAssertNotNil(again)
        XCTAssertNil(a.tryLock())
    }
}
