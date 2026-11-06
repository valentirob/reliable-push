// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ReliablePush",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "ReliablePush", targets: ["ReliablePush"]),
    ],
    targets: [
        .target(
            name: "ReliablePush",
            path: "ios/Sources/ReliablePush",
            resources: [.copy("PrivacyInfo.xcprivacy")],
            swiftSettings: [.enableUpcomingFeature("ExistentialAny")]
        ),
        .testTarget(
            name: "ReliablePushTests",
            dependencies: ["ReliablePush"],
            path: "ios/Tests/ReliablePushTests",
            swiftSettings: [.enableUpcomingFeature("ExistentialAny")]
        ),
    ]
)
