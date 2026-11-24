// swift-tools-version: 5.9
//
// ONNX Runtime 1.24.2 for SpectraStems, kept in this repository because the
// package it came from is gone: github.com/microsoft/onnxruntime-swift-package-manager
// (and microsoft/onnxruntime) answer 404 since 2026-11-17, and
// download.onnxruntime.ai, where its binary came from, no longer presents a
// certificate for its own name. README.md says where every file here came from.
//
// This is Microsoft's own manifest at the pinned revision (b7fb7f7), cut down
// to what Spectra uses: the Objective-C bindings and the runtime, no
// extensions, no tests, no training API.
import PackageDescription

let package = Package(
    name: "onnxruntime",
    platforms: [.iOS(.v15), .macOS(.v14)],
    products: [
        .library(name: "onnxruntime", type: .static, targets: ["OnnxRuntimeBindings"]),
    ],
    targets: [
        .target(
            name: "OnnxRuntimeBindings",
            dependencies: ["onnxruntime"],
            path: "objectivec",
            cxxSettings: [.define("SPM_BUILD")]
        ),
        // The exact zip Microsoft published (SHA-256 f7100a99…0b54, the
        // checksum in their manifest), not a rebuild.
        .binaryTarget(name: "onnxruntime", path: "pod-archive-onnxruntime-c-1.24.2.zip"),
    ],
    cxxLanguageStandard: .cxx17
)
