import AVFoundation
import AudioToolbox
import Synchronization

/// An audio file played through Spectra — on iPhone, the clean digital source
/// that device-audio capture is on Android, and the one oscilloscope music needs.
///
/// AVAudioPlayerNode does the decoding, scheduling and seeking. What the
/// analysis hears is taken inside the graph, by a pass-through audio unit
/// between the player and the mixer (TapUnit): every buffer that is about to
/// play passes through it, at the file's own rate, a render quantum at a time.
/// Two easier routes don't work: an AVAudioEngine tap delivers 100–400 ms at a
/// time, so the picture would step ten times a second, and the output unit's
/// render notification never fires on iOS — RemoteIO is pulled internally.
final class FileCapture: AudioCapture, @unchecked Sendable {
    let url: URL
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let tap: AVAudioUnitEffect
    private let file: AVAudioFile
    private var ring: SampleRing?
    /// Set by `seek` on any thread; the analysis thread drops what is waiting.
    private let discardPending = Atomic<Bool>(false)

    private(set) var sampleRate = 48000
    private(set) var channelCount = 2
    private(set) var describe: String

    /// Where playback is, for the player bar.
    private let state = Mutex(Transport())

    struct Transport {
        var playing = false
        var loop = true
        /// What is queued on the player, in order: where each segment starts on
        /// the player's own clock, and in the file. The clock runs on across a
        /// loop — the next pass is queued seconds ahead, while the player is
        /// still reading the last — and back to zero only when it is stopped.
        var segments: [Segment] = []
        /// Bumped by every reschedule, and before every stop of the player, so a
        /// stale completion is ignored.
        var generation = 0
        /// The place in the file the player's clock last showed. That clock reads
        /// nothing while the player is paused, or once iOS has stopped the engine
        /// for a route change, and this stands in for it then.
        var known: AVAudioFramePosition = 0
        /// Loop off, and the file has played to its end: Play starts it over.
        var ended = false
    }

    struct Segment {
        var playerStart: AVAudioFramePosition
        var fileStart: AVAudioFramePosition
        var frames: AVAudioFramePosition
    }

    init(url: URL) throws {
        self.url = url
        do {
            file = try AVAudioFile(forReading: url)
        } catch {
            throw CaptureError(.unavailable, "That file can't be played: \(error.localizedDescription)")
        }
        TapUnit.registerOnce()
        tap = AVAudioUnitEffect(audioComponentDescription: TapUnit.componentDescription)
        describe = url.deletingPathExtension().lastPathComponent
    }

    var duration: Double { Double(file.length) / file.processingFormat.sampleRate }

    /// Seconds into the file.
    var currentTime: Double { Double(currentFrame) / file.processingFormat.sampleRate }

    /// Frames into the file: by the player's own clock while it reads one, and
    /// where it last did when it reads nothing. Every reading is kept for then.
    private var currentFrame: AVAudioFramePosition {
        let (segments, generation, known) = state.withLock { ($0.segments, $0.generation, $0.known) }
        guard let first = segments.first, let node = player.lastRenderTime,
              let pt = player.playerTime(forNodeTime: node) else { return known }
        let now = max(0, pt.sampleTime)
        let playing = segments.last { $0.playerStart <= now } ?? first
        let frame = min(playing.fileStart + min(now - playing.playerStart, playing.frames), file.length)
        // Not over a reschedule that came in meanwhile: its start is the newer place.
        state.withLock { if $0.generation == generation { $0.known = frame } }
        return frame
    }

    var isPlaying: Bool { state.withLock { $0.playing } }

    private var tapUnit: TapUnit? { tap.auAudioUnit as? TapUnit }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default)
            try session.setPreferredIOBufferDuration(0.005)
            try session.setActive(true)
        } catch {
            throw CaptureError(.unavailable, "iOS refused to play audio: \(error.localizedDescription)")
        }
        guard let unit = tapUnit else {
            throw CaptureError(.unavailable, "The analysis tap could not be created.")
        }
        // The file's own format all the way to the mixer: the analysis runs at
        // the file's rate, and the mixer converts once, for the speaker.
        let format = AVAudioFormat(standardFormatWithSampleRate: file.processingFormat.sampleRate,
                                   channels: min(max(file.processingFormat.channelCount, 1), 2))!
        sampleRate = Int(format.sampleRate.rounded())
        channelCount = Int(format.channelCount)
        let ring = SampleRing(channels: channelCount, capacityFrames: sampleRate)
        self.ring = ring
        unit.box.ring = ring
        let marker = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 64)!
        marker.frameLength = 64
        for c in 0..<Int(format.channelCount) { marker.floatChannelData![c].update(repeating: 0, count: 64) }
        endMarker = marker

        engine.attach(player)
        engine.attach(tap)
        engine.connect(player, to: tap, format: format)
        engine.connect(tap, to: engine.mainMixerNode, format: format)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            throw CaptureError(.unavailable, "Playback could not start: \(error.localizedDescription)")
        }
        schedule(from: 0)
        play()
        observeSystem()
    }

    private var observers: [NSObjectProtocol] = []

    /// A call, Siri or another app's audio interrupts playback, and a route
    /// change (headphones, AirPlay) reconfigures the engine, which stops it.
    /// Pause for the first and resume if iOS says so; restart the engine where
    /// playback was for the second.
    private func observeSystem() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let self, let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            switch type {
            case .began:
                self.pause()
            case .ended:
                let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init)
                if options?.contains(.shouldResume) == true {
                    try? AVAudioSession.sharedInstance().setActive(true)
                    self.play()
                }
            @unknown default:
                break
            }
        })
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            guard let self else { return }
            // iOS has stopped the engine before saying so, and the player's clock
            // reads nothing now: `currentFrame` is where it last read, a few
            // hundredths of a second ago at most (see `read`).
            let resume = self.isPlaying
            let at = self.currentFrame
            let ended = self.state.withLock { $0.ended }
            try? self.engine.start()
            self.restart(from: at)
            if ended { self.state.withLock { $0.ended = true } }
            if resume { self.play() }
        })
    }

    func play() {
        if !engine.isRunning { try? engine.start() }
        // Played to its end with Loop off: from the top, as any player does.
        if state.withLock({ $0.ended }) { restart(from: 0) }
        tapUnit?.box.listening.store(true, ordering: .relaxed)
        player.play()
        state.withLock { $0.playing = true }
    }

    /// Pauses the sound and, with it, the picture: nothing new reaches the
    /// analysis, so the last frame stays on screen — and the tube's phosphor,
    /// which fades by audio time, freezes too.
    func pause() {
        // The player's clock reads nothing while it is paused: note the place first.
        _ = currentFrame
        tapUnit?.box.listening.store(false, ordering: .relaxed)
        player.pause()
        state.withLock { $0.playing = false }
    }

    func setLoop(_ on: Bool) { state.withLock { $0.loop = on } }

    var loops: Bool { state.withLock { $0.loop } }

    func seek(to seconds: Double) {
        let frame = AVAudioFramePosition(max(0, min(seconds, duration)) * file.processingFormat.sampleRate)
        let wasPlaying = isPlaying
        restart(from: frame)
        discardPending.store(true, ordering: .releasing)
        if wasPlaying { player.play() }
    }

    /// Stops the player and queues the file from `frame`. Stopping calls the
    /// completion of what was queued, right there inside `stop()` — so the
    /// generation moves on first, and that call reads as stale rather than as a
    /// pass ending. (It used to read as a pass ending: with Loop on it queued
    /// the file from 0:00 ahead of the new segment, so the sound started over
    /// while the clock showed the seek target; with Loop off it paused.)
    private func restart(from frame: AVAudioFramePosition) {
        state.withLock { $0.generation += 1 }
        player.stop()
        schedule(from: frame)
    }

    /// Queues the file from `frame`. `continuing` means the player was not
    /// stopped — a loop's next pass — so its clock carries on from the last one.
    private func schedule(from frame: AVAudioFramePosition, continuing: Bool = false) {
        let start = min(frame, max(0, file.length - 1))
        let generation = state.withLock { s -> Int in
            let frames = max(0, file.length - start)
            if continuing, let last = s.segments.last {
                // Only the one before is still needed to read the clock by.
                s.segments = [last, Segment(playerStart: last.playerStart + last.frames, fileStart: start, frames: frames)]
            } else {
                s.segments = [Segment(playerStart: 0, fileStart: start, frames: frames)]
                s.known = start
            }
            s.ended = false
            s.generation += 1
            return s.generation
        }
        let count = AVAudioFrameCount(max(0, file.length - start))
        player.scheduleSegment(file, startingFrame: start, frameCount: count, at: nil,
                               completionCallbackType: .dataConsumed) { [weak self] _ in
            self?.segmentEnded(generation)
        }
    }

    /// Called when the player has taken the last of the segment — before it
    /// has played, so a loop's next pass is queued without a gap. (Waiting for
    /// `.dataPlayedBack` instead left the player running on silence.)
    private func segmentEnded(_ generation: Int) {
        let (current, loop) = state.withLock { ($0.generation == generation, $0.loop) }
        guard current else { return }
        if loop {
            schedule(from: 0, continuing: true)
            if isPlaying { player.play() }
        } else {
            // Loop off. The player reads ahead, so the end has not played yet: a
            // second or more of it is still to come. A moment of silence queued
            // behind it says when it has — its `.dataRendered` comes once
            // everything before it has gone through the player.
            player.scheduleBuffer(endMarker, at: nil, options: [], completionCallbackType: .dataRendered) { [weak self] _ in
                self?.reachedEnd(generation)
            }
        }
    }

    /// The moment of silence `segmentEnded` queues behind the last pass.
    private var endMarker: AVAudioPCMBuffer!

    /// Loop off, and the last of the file has played: stop there, at the end.
    private func reachedEnd(_ generation: Int) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let (current, loop) = self.state.withLock { ($0.generation == generation, $0.loop) }
            // An engine that has stopped calls this too, played or not (a route
            // change, before iOS says so): that handler takes it from there.
            guard current, self.engine.isRunning else { return }
            if loop {
                // Loop went back on while the end played out: on from the top.
                self.restart(from: 0)
                if self.isPlaying { self.play() }
                return
            }
            self.pause()
            self.state.withLock { $0.known = self.file.length; $0.ended = true }
        }
    }

    func read(_ out: UnsafeMutablePointer<Float>, frames: Int) -> Int {
        guard let ring else { return -1 }
        if discardPending.exchange(false, ordering: .acquiring) { ring.discard() }
        noteTime()
        return ring.read(out, frames: frames)
    }

    /// The analysis thread reads all the time, in the background too, so it
    /// keeps the place fresh for when nothing on screen asks for it: a route
    /// change while the app is in the background still resumes where it was.
    private var noted: UInt64 = 0
    private func noteTime() {
        let now = DispatchTime.now().uptimeNanoseconds
        guard now &- noted >= 20_000_000 else { return }
        noted = now
        _ = currentFrame
    }

    func stop() {
        // Whatever is still queued may not act once the capture is stopped.
        state.withLock { $0.generation += 1 }
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        tapUnit?.box.listening.store(false, ordering: .relaxed)
        ring?.close()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// What the tap's render block needs, behind one reference: the block runs on
/// the render thread and must not touch the unit itself.
final class TapBox: @unchecked Sendable {
    /// Set once, before rendering starts.
    var ring: SampleRing?
    let listening = Atomic<Bool>(false)

    /// Render thread: no allocation, no locks.
    func take(_ list: UnsafeMutableAudioBufferListPointer, frames: Int) {
        guard let ring, list.count > 0, let l = list[0].mData?.assumingMemoryBound(to: Float.self) else { return }
        let r = list.count > 1 ? list[1].mData?.assumingMemoryBound(to: Float.self) : nil
        ring.write(left: l, right: r, frames: frames)
    }
}

/// A pass-through effect in the playback chain that hands every buffer
/// flowing through it to the analysis. Registered in-process, so it costs one
/// copy of each buffer and no IPC.
final class TapUnit: AUAudioUnit {
    static let componentDescription = AudioComponentDescription(
        componentType: kAudioUnitType_Effect,
        componentSubType: 0x7370_7470,           // 'sptp'
        componentManufacturer: 0x4E33_4478,      // 'N3Dx'
        componentFlags: 0,
        componentFlagsMask: 0
    )

    private static let registered: Void = {
        AUAudioUnit.registerSubclass(TapUnit.self, as: componentDescription, name: "N3D: Spectra tap", version: 1)
    }()

    static func registerOnce() { _ = registered }

    let box = TapBox()
    private var inputBus: AUAudioUnitBus!
    private var busses: (input: AUAudioUnitBusArray, output: AUAudioUnitBusArray)!
    /// Where the input is pulled to when the host gives no buffers of its own.
    /// A holder rather than two properties: the render block is fetched before
    /// the buffers exist, and must see them once allocateRenderResources makes them.
    private let pull = PullBuffers()

    final class PullBuffers: @unchecked Sendable {
        var list: UnsafeMutableAudioBufferListPointer?
        var storage: [UnsafeMutableRawPointer] = []

        func free() {
            storage.forEach { $0.deallocate() }
            storage = []
            list.map { Foundation.free($0.unsafeMutablePointer) }
            list = nil
        }
    }

    override init(componentDescription: AudioComponentDescription, options: AudioComponentInstantiationOptions = []) throws {
        try super.init(componentDescription: componentDescription, options: options)
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 2)!
        let input = try AUAudioUnitBus(format: format)
        let output = try AUAudioUnitBus(format: format)
        input.maximumChannelCount = 2
        output.maximumChannelCount = 2
        inputBus = input
        busses = (AUAudioUnitBusArray(audioUnit: self, busType: .input, busses: [input]),
                  AUAudioUnitBusArray(audioUnit: self, busType: .output, busses: [output]))
        maximumFramesToRender = 4096
    }

    override var inputBusses: AUAudioUnitBusArray { busses.input }
    override var outputBusses: AUAudioUnitBusArray { busses.output }
    override var canProcessInPlace: Bool { true }

    override func allocateRenderResources() throws {
        try super.allocateRenderResources()
        pull.free()
        let channels = Int(inputBus.format.channelCount)
        let bytes = Int(maximumFramesToRender) * MemoryLayout<Float>.size
        let list = AudioBufferList.allocate(maximumBuffers: channels)
        for c in 0..<channels {
            let storage = UnsafeMutableRawPointer.allocate(byteCount: bytes, alignment: 16)
            pull.storage.append(storage)
            list[c] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(bytes), mData: storage)
        }
        pull.list = list
    }

    override func deallocateRenderResources() {
        pull.free()
        super.deallocateRenderResources()
    }

    deinit { pull.free() }

    override var internalRenderBlock: AUInternalRenderBlock {
        let box = self.box
        let buffers = self.pull
        return { _, timestamp, frameCount, _, outputData, _, pullInputBlock in
            guard let pull = pullInputBlock, let ownList = buffers.list else { return kAudioUnitErr_NoConnection }
            let storage = buffers.storage
            let out = UnsafeMutableAudioBufferListPointer(outputData)
            let bytes = UInt32(Int(frameCount) * MemoryLayout<Float>.size)
            // Pull into the host's buffers when it gave some, otherwise into ours
            // and hand those on: the usual in-place pass-through.
            let inPlace = out.count > 0 && out[0].mData != nil
            let target: UnsafeMutableAudioBufferListPointer
            if inPlace {
                target = out
            } else {
                for c in 0..<min(ownList.count, storage.count) {
                    ownList[c].mData = storage[c]
                    ownList[c].mDataByteSize = bytes
                }
                target = ownList
            }
            var flags = AudioUnitRenderActionFlags()
            let status = pull(&flags, timestamp, frameCount, 0, target.unsafeMutablePointer)
            guard status == noErr else { return status }
            if !inPlace {
                for c in 0..<min(out.count, target.count) {
                    out[c].mData = target[c].mData
                    out[c].mDataByteSize = target[c].mDataByteSize
                }
            }
            if box.listening.load(ordering: .relaxed) { box.take(target, frames: Int(frameCount)) }
            return noErr
        }
    }
}
