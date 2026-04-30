// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Wun",
    platforms: [
        .iOS(.v15),
        .macOS(.v12)
    ],
    products: [
        .library(name: "Wun", targets: ["Wun"]),
        .executable(name: "wun-smoke", targets: ["WunSmoke"])
    ],
    targets: [
        .target(name: "Wun", path: "Sources/Wun"),
        .executableTarget(
            name: "WunSmoke",
            dependencies: ["Wun"],
            path: "Sources/WunSmoke"
        ),
        .testTarget(
            name: "WunTests",
            dependencies: ["Wun"],
            path: "Tests/WunTests"
        )
    ]
)
