import XCTest
@testable import ReliablePush

final class FakeSecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: Data] = [:]
    private var forced: SecretRead?
    func force(_ r: SecretRead?) { lock.lock(); forced = r; lock.unlock() }
    func put(_ d: Data, account: String) { lock.lock(); items[account] = d; lock.unlock() }
    func has(_ account: String) -> Bool { lock.lock(); defer { lock.unlock() }; return items[account] != nil }
    func read(account: String) -> SecretRead {
        lock.lock(); defer { lock.unlock() }
        if let forced { return forced }
        return items[account].map { .found($0) } ?? .missing
    }
    func write(_ data: Data, account: String) -> Bool { put(data, account: account); return true }
    func delete(account: String) { lock.lock(); items[account] = nil; lock.unlock() }
}

final class TokenStoreTests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!
    private var secrets: FakeSecretStore!
    private var state: SharedState!
    private var store: TokenStore!
    private var warnings: [String] = []

    override func setUp() {
        suite = "rp.test.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        secrets = FakeSecretStore()
        state = SharedState(defaults: defaults)
        store = TokenStore(secrets: secrets, state: state, appId: "app1")
        let box = WarnBox()
        SDKLog.sink = { level, message in if level == .warn { box.add(message) } }
        self.box = box
    }
    private var box: WarnBox!

    override func tearDown() {
        SDKLog.sink = nil
        defaults.removePersistentDomain(forName: suite)
    }

    private func registration(_ raw: String = DeviceTokenTests.token(iat: 1000, exp: 2000)) -> Registration {
        Registration(deviceId: "dev-1", deviceToken: raw, expiresAt: Date(timeIntervalSince1970: 2000))
    }

    func testStoreRoundTripsAndKeepsTokenOutOfDefaults() throws {
        let reg = registration()
        XCTAssertTrue(store.save(reg))
        guard case let .registered(deviceId, token) = store.load() else { return XCTFail("not registered") }
        XCTAssertEqual(deviceId, "dev-1")
        XCTAssertEqual(token.raw, reg.deviceToken)
        let all = defaults.persistentDomain(forName: suite) ?? [:]
        XCTAssertFalse(all.isEmpty)
        XCTAssertFalse("\(all)".contains(reg.deviceToken))
        XCTAssertFalse("\(all)".contains("ES256"))
        XCTAssertEqual(state.deviceId, "dev-1")
        XCTAssertEqual(state.expiresAt, reg.expiresAt)
    }

    func testEmptyStoreIsNotRegistered() {
        guard case .notRegistered = store.load() else { return XCTFail() }
    }

    func testUndecodableTokenClearsRegistrationWithWarn() {
        XCTAssertTrue(store.save(registration()))
        secrets.put(Data("garbage".utf8), account: "app1")
        guard case .notRegistered = store.load() else { return XCTFail() }
        XCTAssertNil(state.deviceId)
        XCTAssertNil(state.expiresAt)
        XCTAssertFalse(secrets.has("app1"))
        XCTAssertTrue(box.messages.contains { $0.hasPrefix("event=token.unreadable") })
        XCTAssertFalse(box.messages.joined().contains("garbage"))
    }

    func testSecretPresentBeforeMetadataIsNotClearedByReader() {
        // Interleaving: another process's save() has written the Keychain record, SharedState not yet.
        XCTAssertTrue(store.save(registration()))
        state.clearRegistration()
        guard case let .registered(deviceId, _) = store.load() else { return XCTFail("reader must see the record") }
        XCTAssertEqual(deviceId, "dev-1")
        XCTAssertTrue(secrets.has("app1"))
        XCTAssertTrue(box.messages.isEmpty)
    }

    func testRestoredWithoutSecretIsNotRegisteredAndDropsStaleState() {
        XCTAssertTrue(store.save(registration()))
        secrets.delete(account: "app1")
        guard case .notRegistered = store.load() else { return XCTFail() }
        XCTAssertNil(state.deviceId)
        XCTAssertTrue(box.messages.isEmpty)
    }

    func testLockedKeychainKeepsRegistration() {
        XCTAssertTrue(store.save(registration()))
        secrets.force(.unavailable)
        guard case .unavailable = store.load() else { return XCTFail() }
        XCTAssertEqual(state.deviceId, "dev-1")
        secrets.force(nil)
        guard case .registered = store.load() else { return XCTFail("registration was cleared") }
    }

    func testOtherKeychainFailureIsUnavailableAndNeverClears() {
        XCTAssertTrue(store.save(registration()))
        secrets.force(.failed(-25299))
        guard case .unavailable = store.load() else { return XCTFail() }
        XCTAssertEqual(state.deviceId, "dev-1")
        secrets.force(nil)
        guard case .registered = store.load() else { return XCTFail() }
    }

    func testSaveRejectsUnparseableTokenWithoutWriting() {
        XCTAssertFalse(store.save(registration("nope")))
        XCTAssertNil(state.deviceId)
        XCTAssertFalse(secrets.has("app1"))
    }

    func testClearRemovesEverything() {
        XCTAssertTrue(store.save(registration()))
        store.clear()
        XCTAssertFalse(secrets.has("app1"))
        XCTAssertNil(state.deviceId)
        guard case .notRegistered = store.load() else { return XCTFail() }
    }

    func testSharedStateConfigAndRetry() {
        XCTAssertNil(state.config)
        state.config = (URL(string: "https://x.example")!, "app1")
        XCTAssertEqual(state.config?.baseURL, URL(string: "https://x.example"))
        XCTAssertEqual(state.config?.appId, "app1")
        let d = Date(timeIntervalSince1970: 12345)
        state.retryNotBefore = d
        XCTAssertEqual(state.retryNotBefore, d)
        state.clearRegistration()
        XCTAssertEqual(state.retryNotBefore, d, "retry-not-before is not registration state")
        state.retryNotBefore = nil
        XCTAssertNil(state.retryNotBefore)
    }
}

final class WarnBox: @unchecked Sendable {
    private let lock = NSLock()
    private var list: [String] = []
    func add(_ m: String) { lock.lock(); list.append(m); lock.unlock() }
    var messages: [String] { lock.lock(); defer { lock.unlock() }; return list }
}
