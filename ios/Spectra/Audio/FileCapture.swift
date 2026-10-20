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
        TapUnit.registerOnce()
        tap = AVAudioUnitEffect(audioComponentDescription: TapUnit.componentDescription)
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
    }

    func play() {
        if !engine.isRunning { try? engine.start() }
        tapUnit?.box.listening.store(true, ordering: .relaxed)
        player.play()
        state.withLock { $0.playing = true }
    }

    /// Pauses the sound and, with it, the picture: nothing new reaches the
    /// analysis, so the last frame stays on screen — and the tube's phosphor,
    /// which fades by audio time, freezes too.
    func pause() {
        tapUnit?.box.listening.store(false, ordering: .relaxed)
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
