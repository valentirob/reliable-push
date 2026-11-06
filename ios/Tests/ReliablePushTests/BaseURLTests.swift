import XCTest
@testable import ReliablePush

final class BaseURLTests: XCTestCase {
    func testRejectsCleartextToRemoteHost() {
        XCTAssertThrowsError(try BaseURL.validate("http://example.com")) {
            XCTAssertEqual($0 as? ReliablePushError, .insecureBaseURL)
        }
        for ok in ["http://127.0.0.1:8091", "http://localhost", "http://[::1]:8080", "https://x.example"] {
            XCTAssertNoThrow(try BaseURL.validate(ok), ok)
        }
    }

    func testRejectsHostsThatOnlyLookLocal() {
        let spoofs = ["http://localhost.evil.com", "http://127.0.0.1.nip.io", "http://localhost@evil.com",
                      "http://localhost.evil.com:8080/api"]
        for spoof in spoofs {
            XCTAssertThrowsError(try BaseURL.validate(spoof), spoof) {
                let error = $0 as? ReliablePushError
                XCTAssertTrue(error == .insecureBaseURL || error == .invalidBaseURL, "\(spoof): \($0)")
            }
        }
    }

    func testNormalisesTrailingSlashAndWhitespace() throws {
        XCTAssertEqual(try BaseURL.validate("  https://x.example/api/// ").absoluteString, "https://x.example/api")
    }

    func testRejectsInvalidUrls() {
        for bad in ["", "not a url", "https://", "ftp://x.example", "https://u:p@x.example", "https://x.example?q=1", "https://x.example#f", "/relative"] {
            XCTAssertThrowsError(try BaseURL.validate(bad), bad) {
                XCTAssertNotNil($0 as? ReliablePushError, bad)
            }
        }
    }
}
