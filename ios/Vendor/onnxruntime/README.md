# ONNX Runtime 1.24.2 (vendored)

The runtime the stem models run on (`SpectraStems`), as a local Swift package.

## Why it lives here

Spectra used Microsoft's Swift package,
`https://github.com/microsoft/onnxruntime-swift-package-manager`, pinned to
1.24.2. Since about 2026-11-17 that repository, and `microsoft/onnxruntime`
itself, answer 404, and `download.onnxruntime.ai`, where the package fetched
its binary, no longer presents a certificate for its own name. A fresh clone
could not resolve its packages. Copies of the repository have turned up on
GitHub, but nothing says who runs them, so Spectra keeps its own.

## Where each file came from

Everything was taken from this Mac's Swift Package Manager cache, filled on
2026-10-20, before the repository went away. Both were checked against the pins:

| File | Source | Check |
|---|---|---|
| `objectivec/` | `microsoft/onnxruntime-swift-package-manager` at `b7fb7f7dea8a2469e6335d95a61b8f36d0dc83b2` (tag 1.24.2), the revision in `SpectraCore/Package.resolved` | checkout clean at that commit; the files the original manifest excluded (tests, docs, training API) left out |
| `pod-archive-onnxruntime-c-1.24.2.zip` | `https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.24.2.zip`, downloaded by SwiftPM | SHA-256 `f7100a992d2a8135168c8afd831e6a58b465349101982aa58b3e11d36e600b54`, the checksum in Microsoft's manifest at that revision |
| `LICENSE` | the same repository | MIT, Microsoft |

The zip holds `onnxruntime.xcframework` (iOS, iOS Simulator, macOS — the
macOS slice runs `swift test` in `SpectraCore`) with its own licence and
third-party notices.

## Changing versions

Android ships 1.26 (from Maven Central, which still serves it). To move iOS
on, put a new xcframework zip here from a source you can check, change the
`binaryTarget` path, and run the stems tests in `SpectraCore` against the
Kotlin fixtures before shipping (see `ios/PORTING.md`).
