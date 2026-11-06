import XCTest
@testable import ReliablePush

final class RefreshGateTests: XCTestCase {
    override func setUp() { RefreshGate.resetForTesting() }
    override func tearDown() { RefreshGate.resetForTesting() }

    func testRefreshGateAllowsOncePerProcess() {
        XCTAssertTrue(RefreshGate.tryAcquire())
        XCTAssertFalse(RefreshGate.tryAcquire())
        XCTAssertFalse(RefreshGate.tryAcquire())
    }

    func testRefreshGateIsAtomicAcrossThreads() {
        let wins = NSLock()
        nonisolated(unsafe) var count = 0
        DispatchQueue.concurrentPerform(iterations: 200) { _ in
            if RefreshGate.tryAcquire() { wins.lock(); count += 1; wins.unlock() }
        }
        XCTAssertEqual(count, 1)
    }
}
