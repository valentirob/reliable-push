import XCTest
import Security
@testable import ReliablePush

final class KeychainStoreTests: XCTestCase {
    func testKeychainRoundTripWithoutAccessGroup() throws {
        let service = "io.reliablepush.test.\(UUID().uuidString)"
        let store = KeychainStore(service: service, accessGroup: nil)
        let account = "app-\(UUID().uuidString)"
        defer { store.delete(account: account) }

        if case let .failed(status) = store.read(account: account), status == errSecMissingEntitlement {
            throw XCTSkip("Keychain unavailable in this test host (errSecMissingEntitlement -34018); covered by Task 6 hosted tests")
        }
        guard case .missing = store.read(account: account) else { return XCTFail("expected missing") }
        XCTAssertTrue(store.write(Data("one".utf8), account: account))
        guard case let .found(d1) = store.read(account: account) else { return XCTFail("expected found") }
        XCTAssertEqual(d1, Data("one".utf8))
        XCTAssertTrue(store.write(Data("two".utf8), account: account))
        guard case let .found(d2) = store.read(account: account) else { return XCTFail("expected found") }
        XCTAssertEqual(d2, Data("two".utf8))
        store.delete(account: account)
        guard case .missing = store.read(account: account) else { return XCTFail("expected missing after delete") }
    }
}
