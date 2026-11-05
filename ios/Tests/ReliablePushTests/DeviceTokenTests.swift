import XCTest
@testable import ReliablePush

final class DeviceTokenTests: XCTestCase {
    static func jwt(_ payload: String) -> String {
        func b64(_ s: String) -> String {
            Data(s.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return "\(b64("{\"alg\":\"ES256\"}")).\(b64(payload)).sig"
    }

    static func token(iat: Int, exp: Int) -> String { jwt("{\"iat\":\(iat),\"exp\":\(exp)}") }

    func testNeedsRefreshBelowTwentyPercent() throws {
        let t = try XCTUnwrap(DeviceToken.parse(Self.token(iat: 0, exp: 100)))
        XCTAssertFalse(t.needsRefresh(now: Date(timeIntervalSince1970: 80)))
        XCTAssertTrue(t.needsRefresh(now: Date(timeIntervalSince1970: 81)))
        XCTAssertTrue(t.needsRefresh(now: Date(timeIntervalSince1970: 100)))
        XCTAssertTrue(t.needsRefresh(now: Date(timeIntervalSince1970: 500)))
    }

    func testParseReadsClaims() throws {
        let raw = Self.token(iat: 1000, exp: 2000)
        let t = try XCTUnwrap(DeviceToken.parse(raw))
        XCTAssertEqual(t.raw, raw)
        XCTAssertEqual(t.issuedAt, Date(timeIntervalSince1970: 1000))
        XCTAssertEqual(t.expiresAt, Date(timeIntervalSince1970: 2000))
    }

    func testParseRejectsMalformedToken() {
        let bad = [
            "", "a.b", "a.b.c.d", "a.!!!.c",
            Self.jwt("not json"), Self.jwt("{}"),
            Self.jwt("{\"iat\":1}"), Self.jwt("{\"exp\":2}"),
            Self.jwt("{\"iat\":\"1\",\"exp\":\"2\"}"),
            Self.token(iat: -1, exp: 10), Self.token(iat: 10, exp: 10), Self.token(iat: 10, exp: 5),
            Self.token(iat: 1, exp: 100_000_000_000), Self.token(iat: 100_000_000_000, exp: 100_000_000_001),
        ]
        for raw in bad { XCTAssertNil(DeviceToken.parse(raw), raw) }
    }
}
