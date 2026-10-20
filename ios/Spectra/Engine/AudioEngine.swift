import Foundation
import Synchronization
import SpectraCore

/// The single analysis pipeline — AudioEngine.kt for iPhone, the same loop on
/// the same rings with the same constants.
///
/// One dedicated thread pulls audio from the capture and runs the DSP. Every
/// page reads the newest `frame`; none of them owns the capture. A published
/// frame is immutable, so a page that is slow to draw simply skips frames
/// instead of holding up the audio.
final class AudioEngine: @unchecked Sendable {
    static let shared = AudioEngine()

    enum State: Equatable {
        case idle
        case starting
        case running(source: SourceKind, describe: String)
        case failed(kind: CaptureError.Kind, message: String)
    }

    static let spectrogramRows = 256
    static let spectrogramColumns = 512
    static let bandHistory = 256
    private static let beamHistory = 32_768
    private static let scopeSpan = 4096
    private static let scopePoints = 512
    /// Frames per capture read. Every frame the pages see is published from
    /// inside one read, so this — not the hop — is what sets how often the
    /// picture can change: 1024 is about 47 updates a second, which the
    /// held-still scopes need to look still rather than to step.
    private static let readFrames = 1024

    /// Shared history, referenced (never copied) by every published frame.
    let spectrogram = SpectrogramBuffer(rows: spectrogramRows, columns: spectrogramColumns)
    /// Short-term loudness trace, shared with every page for the same reason.
    let loudnessHistory = ScrollBuffer(capacity: bandHistory, initial: -70)
    /// The raw stereo capture for the Oscilloscope page, 680 ms of it. Always
    /// written — two floats a frame is nothing — so a page that starts showing
    /// finds the present there, not whatever was playing the last time.
    let beamFeed = StereoFeed(capacityFrames: beamHistory)

    private let settingsBox = Mutex(Settings())
    private let frameBox = Mutex<AnalysisFrame?>(nil)
    private let running = Atomic<Bool>(false)
    private let paused = Atomic<Bool>(false)
    private let resetRequested = Atomic<Bool>(false)
    /// False while the app is in the background: capture keeps draining, but
    /// nothing is analysed for a screen nobody can see.
    private let visible = Atomic<Bool>(true)
    private let pageBox = Mutex<VizPage?>(nil)
    private let control = NSLock()
    private var capture: AudioCapture?
    private var finished: DispatchSemaphore?

    /// Called on the main actor whenever `state` or `warning` changes.
    var onChange: (@MainActor @Sendable (State, String?) -> Void)?
    private let status = Mutex<(State, String?)>((.idle, nil))

    /// Makes the expensive held-still scopes and stems. Swapped for the real
    /// ScopeRunner once the stems are ported; until then nothing is held still.
    var makeScopes: @Sendable (Int) -> HeldScopes = { _ in NoScopes() }

    // ---- what the UI reads -------------------------------------------------------

    /// The newest analysis, or nil while stopped.
    var frame: AnalysisFrame? { frameBox.withLock { $0 } }
    var state: State { status.withLock { $0.0 } }
    var isPaused: Bool { paused.load(ordering: .relaxed) }
    var currentSettings: Settings { settingsBox.withLock { $0 } }

    /// The page on screen, or nil. The stem model and the held-still scopes only
    /// run for pages somebody can see — they are the two most expensive things.
    var inAppPage: VizPage? {
        get { pageBox.withLock { $0 } }
        set { pageBox.withLock { $0 = newValue } }
    }

    func setVisible(_ value: Bool) { visible.store(value, ordering: .relaxed) }

    /// Applies new settings to the running analysis. Safe from any thread: the
    /// analysis thread picks the new value up whole at the top of its next
    /// block, so a change can never be observed half-applied.
    ///
    /// Returns true when the change needs the capture itself reopened.
    @discardableResult
    func updateSettings(_ next: Settings) -> Bool {
        let previous = settingsBox.withLock { s -> Settings in
            let p = s
            s = next
            return p
        }
        return previous.source != next.source ||
            previous.sampleRate != next.sampleRate ||
            previous.stereo != next.stereo ||
            previous.micMode != next.micMode
    }

    func setPaused(_ value: Bool) { paused.store(value, ordering: .relaxed) }

    func resetMeters() { resetRequested.store(true, ordering: .relaxed) }

    func start(_ source: AudioCapture) {
        control.lock()
        defer { control.unlock() }
        stopLocked()
        publish(.starting, warning: nil)
        capture = source
        running.store(true, ordering: .releasing)
        let done = DispatchSemaphore(value: 0)
        finished = done
        let thread = Thread { [self] in
            runLoop(source)
            done.signal()
        }
        thread.name = "spectra-dsp"
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    func stop() {
        control.lock()
        defer { control.unlock() }
        stopLocked()
    }

    private func stopLocked() {
        running.store(false, ordering: .releasing)
        // Stopping the capture is what makes a blocked read() return; then wait
        // for the loop to leave before the next start touches anything.
        capture?.stop()
        _ = finished?.wait(timeout: .now() + 1)
        finished = nil
        capture = nil
        if case .failed = state {} else { publish(.idle, warning: nil) }
        frameBox.withLock { $0 = nil }
        spectrogram.clear()
        loudnessHistory.clear()
    }

    var isRunning: Bool { running.load(ordering: .acquiring) }

    // ------------------------------------------------------------------------------

    private func runLoop(_ cap: AudioCapture) {
        do {
            try cap.start()
        } catch let e as CaptureError {
            publish(.failed(kind: e.kind, message: e.message), warning: nil)
            running.store(false, ordering: .releasing)
            return
        } catch {
            publish(.failed(kind: .unknown, message: error.localizedDescription), warning: nil)
            running.store(false, ordering: .releasing)
            return
        }

        let rate = cap.sampleRate
        let channels = cap.channelCount
        var s = currentSettings
        publish(.running(source: s.source, describe: cap.describe), warning: nil)

        let analyzer = SpectrumAnalyzer(sampleRate: rate, settings: s, spectrogramRows: Self.spectrogramRows)
        let bands = BandSplitter(sampleRate: rate, settings: s, historyLength: Self.bandHistory)
        let loudness = LoudnessMeter(sampleRate: rate, settings: s)
        let stereo = StereoAnalyzer(sampleRate: rate, settings: s)

        var ring = MonoRing(size: s.fftSize)
        var block = [Float](repeating: 0, count: s.fftSize)
        var hop = s.hopSize
        let scopes = makeScopes(rate)
        let scopeL = MonoRing(size: Self.scopeSpan)
        let scopeR = MonoRing(size: Self.scopeSpan)
        var column = [UInt8](repeating: 0, count: Self.spectrogramRows)

        let interleaved = UnsafeMutablePointer<Float>.allocate(capacity: Self.readFrames * channels)
        let left = UnsafeMutablePointer<Float>.allocate(capacity: Self.readFrames)
        let right = UnsafeMutablePointer<Float>.allocate(capacity: Self.readFrames)
        defer {
            interleaved.deallocate()
            left.deallocate()
            right.deallocate()
        }
        var waveL = [Float](repeating: 0, count: Self.scopePoints)
        var waveR = [Float](repeating: 0, count: Self.scopePoints)
        let scopeStride = Self.scopeSpan / Self.scopePoints

        var gain = dbToLinear(s.inputGainDb)
        var seq: Int64 = 0
        var silentMs: Float = 0
        var warnedSilent = false
        var clipHoldMs: Float = 0

        while running.load(ordering: .acquiring) {
            let read = cap.read(interleaved, frames: Self.readFrames)
            if read < 0 {
                if running.load(ordering: .acquiring) {
                    publish(.failed(kind: .unavailable,
                                    message: "The audio input stopped delivering samples. Another app may have taken it."),
                            warning: nil)
                }
                break
            }
            if read == 0 { continue }

            // Pick up a settings change at a block boundary.
            let next = currentSettings
            if next != s {
                if next.fftSize != s.fftSize || next.overlap != s.overlap {
                    ring = MonoRing(size: next.fftSize)
                    block = [Float](repeating: 0, count: next.fftSize)
                    hop = next.hopSize
                    spectrogram.clear()
                }
                s = next
                gain = dbToLinear(s.inputGainDb)
                analyzer.configure(s, sampleRate: rate)
                bands.configure(s, sampleRate: rate)
                loudness.configure(s, sampleRate: rate)
                stereo.configure(s, sampleRate: rate)
            }

            if resetRequested.exchange(false, ordering: .relaxed) {
                loudness.reset()
                bands.reset()
                stereo.reset()
                analyzer.peaks.reset()
                spectrogram.clear()
                loudnessHistory.clear()
                scopes.reset()
            }

            let frames = read
            var clipped = false
            var allSilent = true

            if channels == 2 {
                var j = 0
                for i in 0..<frames {
                    let l = interleaved[j] * gain
                    let r = interleaved[j + 1] * gain
                    j += 2
                    left[i] = l
                    right[i] = r
                    if abs(l) >= Self.clipThreshold || abs(r) >= Self.clipThreshold { clipped = true }
                    if allSilent && (abs(l) > Self.silenceThreshold || abs(r) > Self.silenceThreshold) { allSilent = false }
                }
            } else {
                for i in 0..<frames {
                    let l = interleaved[i] * gain
                    left[i] = l
                    if abs(l) >= Self.clipThreshold { clipped = true }
                    if allSilent && abs(l) > Self.silenceThreshold { allSilent = false }
                }
            }

            let blockMs = Float(frames) * 1000 / Float(rate)
            silentMs = allSilent ? silentMs + blockMs : 0
            clipHoldMs = clipped ? Self.clipHoldMs : max(clipHoldMs - blockMs, 0)

            warnedSilent = updateSilenceWarning(s, silentMs: silentMs, alreadyWarned: warnedSilent)

            if paused.load(ordering: .relaxed) || !visible.load(ordering: .relaxed) { continue }

            let rightOrNil: UnsafePointer<Float>? = channels == 2 ? UnsafePointer(right) : nil

            bands.process(left, rightOrNil, frames: frames)
            // The band meters tick once per capture chunk rather than once per
            // hop: the filters are streaming, so the natural grain is however
            // many samples the capture just handed over.
            bands.tick(blockMs)
            loudness.process(left, rightOrNil, frames: frames)
            stereo.process(left, rightOrNil, frames: frames)

            scopes.process(left, rightOrNil, frames: frames, wants: wants(s), settings: s, blockMs: blockMs)

            scopeL.write(left, offset: 0, count: frames)
            if let r = rightOrNil { scopeR.write(r, offset: 0, count: frames) }
            beamFeed.write(left: left, right: rightOrNil ?? UnsafePointer(left), count: frames)

            // The transform runs on the mono sum: a spectrum of L+R is what an
            // engineer expects from an analyser, and running two transforms
            // would double the cost to show two nearly identical curves.
            if channels == 2 {
                for i in 0..<frames { left[i] = (left[i] + right[i]) * 0.5 }
            }
            ring.write(left, offset: 0, count: frames)

            let dtMs = Float(hop) * 1000 / Float(rate)
            while ring.pending >= hop {
                ring.consume(hop)
                ring.copyLatest(&block)
                analyzer.process(block, dtMs: dtMs)
                analyzer.fillSpectrogramColumn(&column, floorDb: s.spectrogramFloorDb, ceilDb: s.spectrogramCeilingDb)
                spectrogram.push(column)

                scopeL.copyNewestDecimated(&waveL, count: Self.scopeSpan, stride: scopeStride)
                if rightOrNil != nil { scopeR.copyNewestDecimated(&waveR, count: Self.scopeSpan, stride: scopeStride) }

                let loudnessReading = loudness.read()
                loudnessHistory.push(loudnessReading.shortTerm)

                let published = AnalysisFrame(
                    seq: seq,
                    sampleRate: rate,
                    fftSize: s.fftSize,
                    binHz: Float(rate) / Float(s.fftSize),
                    magnitudesDb: analyzer.smoothedDb,
                    peakDb: s.peakHold ? analyzer.peaks.values : [],
                    bands: bands.readings(),
                    waveL: waveL,
                    waveR: rightOrNil != nil ? waveR : [],
                    gonio: stereo.snapshotCloud(),
                    correlation: stereo.correlation,
                    stereoWidth: stereo.width,
                    loudness: loudnessReading,
                    rmsDbL: StereoAnalyzer.toDb(stereo.rmsL),
                    rmsDbR: StereoAnalyzer.toDb(rightOrNil != nil ? stereo.rmsR : stereo.rmsL),
                    peakDbL: StereoAnalyzer.toDb(stereo.peakL),
                    peakDbR: StereoAnalyzer.toDb(rightOrNil != nil ? stereo.peakR : stereo.peakL),
                    clipped: clipHoldMs > 0,
                    silent: silentMs > Self.silenceHintMs,
                    spectrogram: spectrogram,
                    scopes: scopes.latest,
                    nes: scopes.latestNes,
                    beam: beamFeed
                )
                seq += 1
                frameBox.withLock { $0 = published }
            }
        }

        scopes.release()
        running.store(false, ordering: .releasing)
    }

    /// Which of the expensive analyses the page on screen is actually showing.
    private func wants(_ s: Settings) -> ScopeWants {
        let page = inAppPage
        let wave = page == .waveform
        return ScopeWants(
            stems: page == .stems,
            hold: wave && s.waveformMode == .hold,
            nes: wave && s.waveformMode == .nes
        )
    }

    /// Digital silence is ambiguous — nothing is playing, or what is playing is
    /// protected. Rather than guess, say exactly what iOS allows.
    private func updateSilenceWarning(_ s: Settings, silentMs: Float, alreadyWarned: Bool) -> Bool {
        if silentMs > Self.silenceWarnMs {
            if !alreadyWarned {
                let text: String? = switch s.source {
                case .otherApps:
                    "No sound is reaching Spectra from the broadcast. Either nothing is playing, or the app " +
                        "playing it is protected — Apple Music and other DRM audio arrive as silence. " +
                        "Use the microphone to measure those."
                case .microphone:
                    "The microphone is delivering digital silence. Check that no other app is holding it " +
                        "and that the mic is not muted."
                case .file:
                    nil
                }
                if let text { publish(state, warning: text) }
            }
            return true
        }
        if alreadyWarned { publish(state, warning: nil) }
        return false
    }

    func clearWarning() { publish(state, warning: nil) }

    private func publish(_ state: State, warning: String?) {
        let changed = status.withLock { s -> Bool in
            if s.0 == state && s.1 == warning { return false }
            s = (state, warning)
            return true
        }
        guard changed, let onChange else { return }
        Task { @MainActor in onChange(state, warning) }
    }

    private func dbToLinear(_ db: Float) -> Float { db == 0 ? 1 : pow(10, db / 20) }

    private static let clipThreshold: Float = 0.999
    private static let silenceThreshold: Float = 1e-6
    private static let silenceHintMs: Float = 1000
    private static let silenceWarnMs: Float = 2500
    private static let clipHoldMs: Float = 1200
}

/// Which of the expensive analyses a visible page is showing.
struct ScopeWants: Sendable {
    var stems: Bool
    var hold: Bool
    var nes: Bool
}

/// The held-still scopes and the stems, as the engine drives them.
/// ScopeRunner (stems/ScopeRunner.kt) is the real one.
protocol HeldScopes: AnyObject {
    func process(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, frames: Int,
                 wants: ScopeWants, settings: Settings, blockMs: Float)
    var latest: ScopeFrame? { get }
    var latestNes: NesReading? { get }
    func reset()
    func release()
}

final class NoScopes: HeldScopes {
    func process(_ left: UnsafePointer<Float>, _ right: UnsafePointer<Float>?, frames: Int,
                 wants: ScopeWants, settings: Settings, blockMs: Float) {}
    var latest: ScopeFrame? { nil }
    var latestNes: NesReading? { nil }
    func reset() {}
    func release() {}
}
