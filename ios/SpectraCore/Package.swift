// swift-tools-version: 6.2
//
// Spectra's analysis engine for iPhone: a Swift port of the Kotlin engine that
// the Android and Windows builds share (app/src/main/java/com/n3d/spectra).
// ios/PORTING.md has the rules that keep the two engines in step, and every
// ported class is checked against results the Kotlin engine wrote itself
// (`./gradlew :desktop:iosFixtures`, then `swift test` here).
import PackageDescription

let package = Package(
    name: "SpectraCore",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "SpectraCore", targets: ["SpectraCore"]),
        .library(name: "SpectraStems", targets: ["SpectraStems"]),
    ],
    dependencies: [
        // The runtime the stem models run on. Android ships 1.26; 1.24.2 is the
        // newest Swift package, and the stems' tests check it against the
        // Kotlin results like everything else.
        .package(url: "https://github.com/microsoft/onnxruntime-swift-package-manager", exact: "1.24.2"),
    ],
    targets: [
        .target(name: "SpectraCore"),
        // stems/ — the stem separator, the synth splitter and the held-still
        // lanes, on ONNX Runtime. A target of its own so the rest of the engine
        // builds and tests without it.
        .target(
            name: "SpectraStems",
            dependencies: [
                "SpectraCore",
                .product(name: "onnxruntime", package: "onnxruntime-swift-package-manager"),
            ]
        ),
        // Reads the Kotlin engine's reference results. Tests only; no product ships it.
        .target(name: "SpectraFixtures", path: "Tests/SpectraFixtures"),
        .testTarget(
            name: "SpectraCoreTests",
            dependencies: ["SpectraCore", "SpectraFixtures"],
            exclude: ["Fixtures"]
        ),
        .testTarget(
            name: "SpectraStemsTests",
            dependencies: ["SpectraStems", "SpectraFixtures"],
            exclude: ["Fixtures"]
        ),
    ]
)
