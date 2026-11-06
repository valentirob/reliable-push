import XCTest
@testable import ReliablePush

/// I1: the queue directory and the lock files get `completeUntilFirstUserAuthentication` explicitly, so an NSE can open
/// them while the device is locked whatever default class the host app has. The Simulator does not enforce Data
/// Protection, so these tests check that the class is requested (through the seam) on every open.
final class FileProtectionTests: XCTestCase {
    private var root: URL!
    private var dir: URL!
    private var warnings: WarnBox!
    private let calls = Locked<[String]>([])

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("rp-protection-\(UUID().uuidString)")
        dir = root.appendingPathComponent("Library/Application Support/ReliablePush")
        warnings = WarnBox()
        let box = warnings!
        SDKLog.sink = { level, message in if level == .warn { box.add(message) } }
        let calls = calls
        FileProtection.setterForTesting = { path, attributes in
            calls.withLock { $0.append(path) }
            XCTAssertEqual(attributes[.protectionKey] as? FileProtectionType, .completeUntilFirstUserAuthentication)
        }
    }

    override func tearDown() {
        FileProtection.setterForTesting = nil
        SDKLog.sink = nil
        try? FileManager.default.removeItem(at: root)
    }

    private var protected: [String] { calls.withLock { $0 }.map { URL(fileURLWithPath: $0).lastPathComponent } }

    func testLockFilesGetProtectionOnEveryOpen() {
        let queue = EventQueue(directory: dir)
        queue.add(QueuedEvent(trackingId: "t1", type: .delivered, occurredAt: Date(timeIntervalSince1970: 1_700_000_000)))
        XCTAssertEqual(queue.count, 1)
        XCTAssertEqual(protected.filter { $0 == "events.lock" }.count, 2, "set on each open, also for an existing file")

        let flushLock = FileLock(url: dir.appendingPathComponent("flush.lock"))
        flushLock.tryLock()?.release()
        flushLock.withLock(timeout: 0.1) {}
        XCTAssertEqual(protected.filter { $0 == "flush.lock" }.count, 2)
        XCTAssertTrue(warnings.messages.isEmpty, "\(warnings.messages)")
    }

    func testQueueDirectoryIsCreatedWithProtection() throws {
        FileLock(url: dir.appendingPathComponent("events.lock")).withLock {}
        XCTAssertTrue(protected.contains("ReliablePush"), "directory created with the class: \(protected)")
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.path))
    }

    func testProtectionFailureWarnsButLockStillWorks() {
        FileProtection.setterForTesting = { _, _ in throw CocoaError(.fileWriteNoPermission) }
        let a = FileLock(url: dir.appendingPathComponent("flush.lock")), b = FileLock(url: dir.appendingPathComponent("flush.lock"))
        let held = a.tryLock()
        XCTAssertNotNil(held, "a protection failure is not fatal")
        XCTAssertNil(b.tryLock(), "and the lock is really held")
        held?.release()
        XCTAssertTrue(warnings.messages.contains { $0.hasPrefix("event=file.protection_failed") && $0.contains("file=flush.lock") },
                      "\(warnings.messages)")
    }

    func testProductionSetterAcceptsTheClass() throws {
        // the real FileManager call on this platform: no throw (the Simulator accepts the attribute; a device applies it)
        FileProtection.setterForTesting = nil
        let lock = FileLock(url: dir.appendingPathComponent("events.lock"))
        lock.withLock {}
        XCTAssertTrue(warnings.messages.isEmpty, "\(warnings.messages)")
        let attrs = try FileManager.default.attributesOfItem(atPath: dir.appendingPathComponent("events.lock").path)
        if let key = attrs[.protectionKey] as? FileProtectionType {
            XCTAssertEqual(key, .completeUntilFirstUserAuthentication)
        }
    }
}
