// swift-tools-version: 5.9
//
// Component-pack template. Rename `MyAppExample` to whatever fits
// your app. Depends only on the `Wun` package; once published this
// would point at a tagged release rather than `path:`.

import PackageDescription

let package = Package(
    name: "MyAppExample",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "MyAppExample", targets: ["MyAppExample"]),
    ],
    dependencies: [
        // For local development against the Wun monorepo:
        .package(path: "../../../wun-ios"),
        // For real apps, bump to a published version:
        // .package(url: "https://github.com/your-org/wun-ios", from: "0.1.0"),
    ],
    targets: [
        .target(
            name: "MyAppExample",
            dependencies: [.product(name: "Wun", package: "wun-ios")],
            path: "Sources"),
    ]
)
