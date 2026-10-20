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
    ],
    targets: [
        .target(name: "SpectraCore"),
        // Reads the Kotlin engine's reference results. Tests only; no product ships it.
        .target(name: "SpectraFixtures", path: "Tests/SpectraFixtures"),
        .testTarget(
            name: "SpectraCoreTests",
            dependencies: ["SpectraCore", "SpectraFixtures"],
            exclude: ["Fixtures"]
        ),
    ]
)
