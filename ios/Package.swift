// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "ComedorCore", platforms: [.iOS(.v16), .macOS(.v13)], products: [.library(name: "ComedorCore", targets: ["ComedorCore"])], targets: [.target(name: "ComedorCore"), .testTarget(name: "ComedorCoreTests", dependencies: ["ComedorCore"], resources: [.copy("Fixtures")])])
