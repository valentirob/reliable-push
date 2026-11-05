import XCTest
@testable import ReliablePush

final class SDKLogTests: XCTestCase {
    override func tearDown() { SDKLog.sink = nil }

    func testFormatsEventAndFieldsAndDropsDeniedKeys() {
        let box = WarnBox()
        SDKLog.sink = { level, m in box.add("\(level):\(m)") }
        SDKLog.warn("x.y", ["deviceId": "d1", "token": "SECRET", "idToken": "SECRET2"])
        XCTAssertEqual(box.messages, ["warn:event=x.y deviceId=d1"])
    }
}
