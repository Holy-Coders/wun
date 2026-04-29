// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Wun",
    platforms: [
        .iOS(.v15),
        .macOS(.v12)
    ],
    products: [
        .library(name: "Wun", targets: ["Wun"])
    ],
    targets: [
        .target(name: "Wun", path: "Sources/Wun")
    ]
)
