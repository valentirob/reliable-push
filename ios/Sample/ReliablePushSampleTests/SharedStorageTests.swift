import Foundation
import Security
import XCTest
@testable import ReliablePush

/// Hosted in the sample app, so the Keychain and the App Group are those of a process signed with the sample's
/// entitlements (the App Group `$(RP_APP_GROUP)`, read here from the app's Info.plist key `RPAppGroup`). They prove what
/// the Notification Service Extension relies on: the device token in the Keychain access group = App Group id, and the
/// shared `UserDefaults` suite and container. The SwiftPM test host has no entitlements (`errSecMissingEntitlement`),
/// so the package suite skips the real Keychain round trip; it runs here instead.
final class SharedStorageTests: XCTestCase {
    private var appGroup: String {
        get throws {
            let value = Bundle.main.object(forInfoDictionaryKey: "RPAppGroup") as? String ?? ""
            guard value.hasPrefix("group.") else {
                throw XCTSkip("the test host is not the sample app (no RPAppGroup in its Info.plist)")
            }
            return value
        }
    }

    func testKeychainAccessGroupAndAppGroupAreUsable() throws {
        let group = try appGroup
        try roundTrip(KeychainStore(service: "io.reliablepush.test.\(UUID().uuidString)", accessGroup: group),
                      what: "Keychain access group \(group)")

        let defaults = try XCTUnwrap(UserDefaults(suiteName: group), "UserDefaults(suiteName: \(group))")
        let key = "io.reliablepush.test.\(UUID().uuidString)"
        defaults.set("value", forKey: key)
        XCTAssertEqual(defaults.string(forKey: key), "value")
        defaults.removeObject(forKey: key)
        XCTAssertNil(defaults.string(forKey: key))

        let container = try XCTUnwrap(FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group),
                                      "App Group container of \(group)")
        let dir = container.appendingPathComponent("Library/Caches/io.reliablepush.test", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("\(UUID().uuidString).json")
        try Data("{}".utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        XCTAssertEqual(try Data(contentsOf: file), Data("{}".utf8))
    }

    /// R9: the round trip the SwiftPM host had to skip (-34018), here with the app's entitlements.
    func testKeychainRoundTripWithoutAccessGroup() throws {
        _ = try appGroup
        try roundTrip(KeychainStore(service: "io.reliablepush.test.\(UUID().uuidString)", accessGroup: nil),
                      what: "Keychain default access group")
    }

    /// Write, read, update, delete through the SDK's `KeychainStore`; never skipped: a missing entitlement fails.
    private func roundTrip(_ store: KeychainStore, what: String, file: StaticString = #filePath,
                           line: UInt = #line) throws {
        let account = "app-\(UUID().uuidString)"
        defer { store.delete(account: account) }
        let first = store.read(account: account)
        if case let .failed(status) = first {
            return XCTFail("\(what): read failed with OSStatus \(status) (\(status == errSecMissingEntitlement ? "errSecMissingEntitlement: the entitlement is not applied" : "unexpected"))",
                           file: file, line: line)
        }
        XCTAssertEqual(first, .missing, "\(what): a new account is missing", file: file, line: line)
        XCTAssertTrue(store.write(Data("one".utf8), account: account), "\(what): add", file: file, line: line)
        XCTAssertEqual(store.read(account: account), .found(Data("one".utf8)), "\(what): read after add",
                       file: file, line: line)
        XCTAssertTrue(store.write(Data("two".utf8), account: account), "\(what): update", file: file, line: line)
        XCTAssertEqual(store.read(account: account), .found(Data("two".utf8)), "\(what): read after update",
                       file: file, line: line)
        store.delete(account: account)
        XCTAssertEqual(store.read(account: account), .missing, "\(what): missing after delete", file: file, line: line)
    }
}
