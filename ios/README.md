# Reliable Push iOS SDK

Swift package `ReliablePush` (iOS 15.0+, Swift 6). Apple frameworks only: Foundation, UserNotifications, Security, OSLog.

## Layout

- `Package.swift` (repository root): the manifest. SwiftPM resolves a package by URL from the repository root, so it cannot live in `ios/`.
- `ios/Sources/ReliablePush`: SDK sources and `PrivacyInfo.xcprivacy`.
- `ios/Tests/ReliablePushTests`: XCTest unit tests.
- `ios/Sample`: sample app, notification service extension and UI tests (added in Task 6).
- `ios/scripts/size.sh`: binary size gate.

## API at a glance

Full guide: [docs/ios.md](../docs/ios.md).

- App: `ReliablePush.configure(baseURL:appId:appGroup:)` at every launch, then
  `ReliablePush.ensureRegistered(idTokenProvider:)` at app start or foreground and after login. It is the only place the
  device token is renewed (expired or missing → ID token, `renewalNeeded` or under 20 % left → refresh, else
  `.alreadyRegistered` with no network). `register(idToken:)` stays for an explicit registration; `logout()` on a user
  switch; `trackOpen(response:)` for `READ`.
- Notification Service Extension: `ReliablePushExtension(appGroup:)`. It records `DELIVERED`, makes one
  `POST /v1/events` with the stored token (never a refresh; a `401` sets `renewalNeeded` for the app) and calls the
  content handler exactly once, within `extensionSendTimeout` (default 2 s) + 0.5 s, and at once when an SDK step fails.
  Display never depends on tracking.

## Prerequisites (pinned)

- macOS 26.5.1 host.
- Xcode 26.6 (build 17F113) at `/Applications/Xcode.app`, selected with `xcode-select`. The Command Line Tools alone have no XCTest.
- iOS Simulator runtime iOS 26.5 (build 23F77).
- Default simulator device: `iPhone 17`. Override with the `IOS_SIMULATOR` environment variable.

Check with `xcodebuild -version` and `xcrun simctl list runtimes`.

## Commands

```sh
export IOS_SIMULATOR="${IOS_SIMULATOR:-iPhone 17}"

# Unit tests (from the repository root)
xcodebuild test -scheme ReliablePush -destination "platform=iOS Simulator,name=$IOS_SIMULATOR"

# Privacy manifest lint
plutil -lint ios/Sources/ReliablePush/PrivacyInfo.xcprivacy

# Size gate: stripped arm64 dylib must be < 307200 bytes
ios/scripts/size.sh
```
