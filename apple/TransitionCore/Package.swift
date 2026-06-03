// swift-tools-version:6.0
import PackageDescription

// Local package that exposes the Rust core to the app as `import TransitionCore`.
//
// Two pieces, both produced by ../build-ios.sh:
//   • TransitionCoreFFI.xcframework  — the static Rust lib (device + simulator
//     slices) plus the C header + modulemap (the low-level `transitionFFI` module).
//   • Sources/TransitionCore/transition.swift — uniffi's generated high-level
//     Swift wrapper (gitignored; regenerated each build). It does
//     `import transitionFFI`, which resolves to the binary target's modulemap.
//
// Until build-ios.sh has run, the .xcframework is absent — that's expected; CI
// builds the Rust core before resolving this package.
let package = Package(
    name: "TransitionCore",
    platforms: [.iOS(.v18)],
    products: [
        .library(name: "TransitionCore", targets: ["TransitionCore"]),
    ],
    targets: [
        .binaryTarget(
            name: "TransitionCoreFFI",
            path: "TransitionCoreFFI.xcframework"
        ),
        .target(
            name: "TransitionCore",
            dependencies: ["TransitionCoreFFI"],
            path: "Sources/TransitionCore",
            // uniffi 0.28's generated Swift targets the Swift 5 model; keep the
            // wrapper in Swift 5 mode to avoid strict-concurrency errors.
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
