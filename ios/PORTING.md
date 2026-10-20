# Porting the engine to Swift

Spectra for iPhone runs a **Swift rewrite** of the Kotlin engine that Android and
Windows share (`app/src/main/java/com/n3d/spectra`). Two copies of every
algorithm drift unless something holds them together. Here, the Kotlin code
stays the reference, and every Swift class is tested against results the Kotlin
class produced.

## Where things go

- `ios/SpectraCore` is a Swift package with no UI and no AVFoundation. It builds
  and tests on the Mac with `swift test`, and needs no simulator.
- One Swift file per Kotlin file, at the same relative path:
  `dsp/LoudnessMeter.kt` becomes `Sources/SpectraCore/dsp/LoudnessMeter.swift`.
  The Kotlin package folders are kept (`dsp`, `dsp/nes`, `audio`, `paint`,
  `settings`), so finding the original is never a search.
- Not ported: anything Android-only, such as services, notifications, the
  widget, the overlay, the updater, `CpuAffinity` and `android.*`.

## Fidelity rules

1. **Same names.** Keep the Kotlin type, method and property names. Constants
   become lowerCamelCase (`WAVE_RMS` becomes `waveRms`), as do enum cases. Enum
   raw values are the Kotlin names (`case blackmanHarris = "BLACKMAN_HARRIS"`).
   The fixtures and the saved settings use them.
2. **Same arithmetic.** Use the same `Float`/`Double` types as the Kotlin, in the
   same order of operations. Where Kotlin computes in Double and stores a Float,
   do the same.
3. **Kotlin's rounding and conversion, not Swift's.** Kotlin rounds .5 up, and
   its `Float.toInt()` saturates where Swift traps. Use the helpers in
   `support/KotlinParity.swift`: `ktRoundToInt`, `ktRoundToLong`, `ktToInt` and
   `coerceIn`. Kotlin `Int` is 32 bits and wraps, so use `&+`/`&*` where the
   Kotlin relies on wrapping. Kotlin `x % 1f` is `x.truncatingRemainder(dividingBy: 1)`.
4. **Carry the comments over.** The Kotlin doc comments hold the reasons behind
   the code: measurements, traps, things not to "fix". Keep them, reworded only
   where Swift needs it.
5. **Accelerate is welcome where it computes the same thing.** For example,
   `Fft` hands the same transform to vDSP. It has to pass the same fixtures.

## Performance

- No allocation per sample or per block on the analysis thread. Preallocate the
  way the Kotlin does.
- Hot loops over arrays go through `withUnsafeMutableBufferPointer` or
  preallocated `UnsafeMutableBufferPointer` storage, which avoids bounds and
  exclusivity checks per element.
- For a class that is expensive per frame (the CRT, the tracker, the spectrum),
  add a timing test that prints ms per call in a release build
  (`swift test -c release`).

## Threads

- A class owned by one thread, which is most of the engine, is a plain
  `final class`, not `Sendable`. Kotlin says "not thread safe; the analysis
  thread owns it" for these.
- A class that one thread writes and another reads (the Kotlin uses
  `@Volatile`) is `final class X: @unchecked Sendable`. Its storage is raw
  memory (`UnsafeMutableBufferPointer`) and its published index is an `Atomic`
  (Synchronization), as in `dsp/StreamRings.swift`. Say why in its comment.
- Readings that cross threads (`AnalysisFrame`, `ScopeTrace`, `NesReading`, …)
  are immutable and `Sendable`.

## Cross-checks

- The Kotlin side is `desktop/src/main/kotlin/com/n3d/spectra/desktop/dev/Ios*Fixtures.kt`.
  These run the Kotlin classes on fixed inputs and store the inputs and outputs
  (`IosFixture`). Regenerate after any change to the Kotlin engine:

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ./gradlew :desktop:iosFixtures
  ```

  The desktop module compiles the shared engine on a plain JVM, so this never
  builds the Android app.
- The Swift side is `Tests/SpectraCoreTests/*Tests.swift`, which runs the same
  inputs and compares with `mismatch(_:_:abs:rel:)`. Choose the tightest
  tolerance that passes, and write down why it is that size. Float rounding in
  `log10`/`exp` is fine. A whole-dB disagreement is a bug.
- Port the Kotlin unit tests for a class too (`app/src/test/...`). They pin
  behaviour that someone measured.
- If the Swift and Kotlin disagree and the Kotlin is wrong, fix the Kotlin in
  its own commit and regenerate. Android and Windows get the fix too.
