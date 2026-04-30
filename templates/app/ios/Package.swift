// swift-tools-version: 5.9
//
// myapp iOS / macOS demo. Depends on the Wun Swift package via a
// sibling-clone path; switch to a tagged version once the framework
// stabilises:
//
//     .package(url: "https://github.com/Holy-Coders/wun.git",
//              from: "0.1.0")
//
// The product is `myapp-demo`, runnable via `swift run myapp-demo`.

import PackageDescription

let package = Package(
    name: "myapp",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .executable(name: "myapp-demo", targets: ["MyAppDemo"]),
    ],
    dependencies: [
        // sibling clone -- the user has wun cloned at ../../wun/wun-ios
        .package(path: "../../wun/wun-ios"),
        // remote alternative once the repo is public + tagged:
        // .package(url: "https://github.com/Holy-Coders/wun.git", from: "0.1.0"),
    ],
    targets: [
        .executableTarget(
            name: "MyAppDemo",
            dependencies: [.product(name: "Wun", package: "wun-ios")],
            path: "Sources/MyAppDemo"),
    ]
)
