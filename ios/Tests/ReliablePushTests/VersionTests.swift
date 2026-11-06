import XCTest
@testable import ReliablePush

final class VersionTests: XCTestCase {
    func testVersionIsSnapshot() {
        XCTAssertEqual(ReliablePush.version, "0.1.1")
    }
}
