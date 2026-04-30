// swift-tools-version: 5.9
import PackageDescription

// Demonstrates the brief's claim that user code participates in the
// Wun registry identically to framework code: this is a separate
// Swift package, with its own products + tests, that depends on the
// `Wun` library by path. WunExample.register(into:) drops a custom
// `:myapp/Greeting` renderer into a Wun.Registry alongside whatever
// foundational `:wun/*` renderers the host has already wired up.

let package = Package(
    name: "WunExample",
    platforms: [
        .iOS(.v15),
        .macOS(.v12)
    ],
    products: [
        .library(name: "WunExample", targets: ["WunExample"]),
        .executable(name: "example-smoke", targets: ["ExampleSmoke"]),
        .executable(name: "wun-demo-mac", targets: ["WunDemoMac"])
    ],
    dependencies: [
        .package(name: "Wun", path: "../wun-ios"),
    ],
    targets: [
        .target(
            name: "WunExample",
            dependencies: [.product(name: "Wun", package: "Wun")]
        ),
        .executableTarget(
            name: "ExampleSmoke",
            dependencies: [
                .product(name: "Wun", package: "Wun"),
                "WunExample",
            ]
        ),
        .executableTarget(
            name: "WunDemoMac",
            dependencies: [
                .product(name: "Wun", package: "Wun"),
                "WunExample",
            ]
        ),
    ]
)
