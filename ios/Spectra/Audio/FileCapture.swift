import AVFoundation
import AudioToolbox
import Synchronization

/// An audio file played through Spectra — on iPhone, the clean digital source
/// that device-audio capture is on Android, and the one oscilloscope music needs.
///
/// AVAudioPlayerNode does the decoding, scheduling and seeking. What the
/// analysis hears is taken at the very end of the chain, from the output unit's
/// post-render notification: exactly the samples going to the speaker, in each
/// small I/O buffer as it is rendered. (An AVAudioEngine tap would be simpler
/// and would deliver 100–400 ms at a time — the picture would step.)
final class FileCapture: AudioCapture, @unchecked Sendable {
    let url: URL
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let file: AVAudioFile
    private var ring: SampleRing?
    private var tapBox: Unmanaged<TapBox>?
    /// Set by `seek` on any thread; the analysis thread drops what is waiting.
    private let discardPending = Atomic<Bool>(false)

    private(set) var sampleRate = 48000
    private(set) var channelCount = 2
    private(set) var describe: String

    /// Where playback is, kept on the main actor for the player bar.
    private let state = Mutex(Transport())

    struct Transport {
        var playing = false
        var loop = true
        /// File frame the current segment started at.
        var segmentStart: AVAudioFramePosition = 0
        /// Bumped by every reschedule, so a stale completion is ignored.
        var generation = 0
    }

    init(url: URL) throws {
        self.url = url
        do {
            file = try AVAudioFile(forReading: url)
        } catch {
            throw CaptureError(.unavailable, "That file can't be played: \(error.localizedDescription)")
        }
        describe = url.deletingPathExtension().lastPathComponent
    }

    var duration: Double { Double(file.length) / file.processingFormat.sampleRate }

    /// Seconds into the file.
    var currentTime: Double {
        let t = state.withLock { $0 }
        guard let node = player.lastRenderTime, let pt = player.playerTime(forNodeTime: node) else {
            return Double(t.segmentStart) / file.processingFormat.sampleRate
        }
        let frame = t.segmentStart + max(0, pt.sampleTime)
        return Double(min(frame, file.length)) / file.processingFormat.sampleRate
    }

    var isPlaying: Bool { state.withLock { $0.playing } }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default)
            try session.setPreferredIOBufferDuration(0.005)
            try session.setActive(true)
        } catch {
            throw CaptureError(.unavailable, "iOS refused to play audio: \(error.localizedDescription)")
        }
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: file.processingFormat)
        engine.prepare()

        let out = engine.outputNode.inputFormat(forBus: 0)
        sampleRate = Int(out.sampleRate.rounded())
        channelCount = min(max(Int(out.channelCount), 1), 2)
        let ring = SampleRing(channels: channelCount, capacityFrames: sampleRate)
        self.ring = ring

        guard let unit = engine.outputNode.audioUnit else {
            throw CaptureError(.unavailable, "iOS gave no output unit to listen to.")
        }
        let box = Unmanaged.passRetained(
            TapBox(ring: ring, interleaved: out.isInterleaved, channels: Int(out.channelCount))
        )
        tapBox = box
        let status = AudioUnitAddRenderNotify(unit, outputTapNotify, box.toOpaque())
        guard status == noErr else {
            box.release()
            tapBox = nil
            throw CaptureError(.unavailable, "Could not listen to the output (\(status)).")
        }

        do {
            try engine.start()
        } catch {
            throw CaptureError(.unavailable, "Playback could not start: \(error.localizedDescription)")
        }
        schedule(from: 0)
        play()
    }

    func play() {
        if !engine.isRunning { try? engine.start() }
        tapBox?.takeUnretainedValue().listening.store(true, ordering: .relaxed)
        player.play()
        state.withLock { $0.playing = true }
    }

    /// Pauses the sound and, with it, the picture: nothing new reaches the
    /// analysis, so the last frame stays on screen — and the tube's phosphor,
    /// which fades by audio time, freezes too.
    func pause() {
        tapBox?.takeUnretainedValue().listening.store(false, ordering: .relaxed)
        player.pause()
        state.withLock { $0.playing = false }
    }

    func setLoop(_ on: Bool) { state.withLock { $0.loop = on } }

    var loops: Bool { state.withLock { $0.loop } }

    func seek(to seconds: Double) {
        let frame = AVAudioFramePosition(max(0, min(seconds, duration)) * file.processingFormat.sampleRate)
        let wasPlaying = isPlaying
        player.stop()
        schedule(from: frame)
        discardPending.store(true, ordering: .releasing)
        if wasPlaying { player.play() }
    }

    private func schedule(from frame: AVAudioFramePosition) {
        let start = min(frame, max(0, file.length - 1))
        let generation = state.withLock { s -> Int in
            s.segmentStart = start
            s.generation += 1
            return s.generation
        }
        let count = AVAudioFrameCount(max(0, file.length - start))
        player.scheduleSegment(file, startingFrame: start, frameCount: count, at: nil,
                               completionCallbackType: .dataPlayedBack) { [weak self] _ in
            self?.segmentEnded(generation)
        }
    }

    private func segmentEnded(_ generation: Int) {
        let (current, loop) = state.withLock { ($0.generation == generation, $0.loop) }
        guard current else { return }
        if loop {
            schedule(from: 0)
            if isPlaying { player.play() }
        } else {
            DispatchQueue.main.async { [weak self] in self?.pause() }
        }
    }

    func read(_ out: UnsafeMutablePointer<Float>, frames: Int) -> Int {
        guard let ring else { return -1 }
        if discardPending.exchange(false, ordering: .acquiring) { ring.discard() }
        return ring.read(out, frames: frames)
    }

    func stop() {
        ring?.close()
        if let unit = engine.outputNode.audioUnit, let box = tapBox {
            box.takeUnretainedValue().listening.store(false, ordering: .relaxed)
            engine.stop()
            AudioUnitRemoveRenderNotify(unit, outputTapNotify, box.toOpaque())
        } else {
            engine.stop()
        }
        tapBox?.release()
        tapBox = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// The output unit's render notification. One function, so the same pointer
/// that added it can remove it.
private let outputTapNotify: AURenderCallback = { refCon, flags, _, bus, frames, ioData in
    guard flags.pointee.contains(.unitRenderAction_PostRender), bus == 0, let ioData else { return noErr }
    let box = Unmanaged<TapBox>.fromOpaque(refCon).takeUnretainedValue()
    if box.listening.load(ordering: .relaxed) { box.take(ioData, frames: Int(frames)) }
    return noErr
}

/// What the render-notify callback needs, behind one pointer: C callbacks
/// cannot capture.
private final class TapBox: @unchecked Sendable {
    let ring: SampleRing
    let interleaved: Bool
    /// Channels in the output format (the ring keeps at most two).
    let channels: Int
    let listening = Atomic<Bool>(false)

    init(ring: SampleRing, interleaved: Bool, channels: Int) {
        self.ring = ring
        self.interleaved = interleaved
        self.channels = channels
    }

    /// Real-time thread: no allocation, no locks.
    func take(_ ioData: UnsafeMutablePointer<AudioBufferList>, frames: Int) {
        let list = UnsafeMutableAudioBufferListPointer(ioData)
        guard list.count > 0, let l = list[0].mData?.assumingMemoryBound(to: Float.self) else { return }
        if interleaved {
            ring.write(interleaved: l, channels: channels, frames: frames)
        } else {
            let r = list.count > 1 ? list[1].mData?.assumingMemoryBound(to: Float.self) : nil
            ring.write(left: l, right: r, frames: frames)
        }
    }
}
