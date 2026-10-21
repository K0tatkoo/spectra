import AVFoundation
import SpectraCore

/// The microphone.
///
/// An `AVAudioSinkNode` on the input, not a tap: an AVAudioEngine tap delivers
/// 100–400 ms at a time, so the picture would step ten times a second. The sink
/// is called on the render thread with each I/O buffer — 5–20 ms — which is
/// what lets the held-still scopes look still rather than step.
final class MicCapture: AudioCapture {
    private let settings: Settings
    private let engine = AVAudioEngine()
    private var ring: SampleRing?
    private(set) var sampleRate = 48000
    private(set) var channelCount = 1
    private(set) var describe = "Microphone"

    init(settings: Settings) {
        self.settings = settings
    }

    func start() throws {
        guard AVAudioApplication.shared.recordPermission == .granted else {
            throw CaptureError(.permission, "Spectra needs the microphone. Allow it in Settings › Privacy & Security › Microphone.")
        }
        let session = AVAudioSession.sharedInstance()
        do {
            // The measurement mode is iOS's counterpart of Android's UNPROCESSED
            // source: no automatic gain, no noise suppression, no voice processing.
            // But it is mono, as is the default mode: iOS offers the built-in
            // mics' stereo pattern only in the video-recording mode, so stereo
            // takes that mode whatever the picker says.
            let chosen: AVAudioSession.Mode = switch settings.micMode {
            case .measurement: .measurement
            case .standard: .default
            case .voice: .voiceChat
            }
            let mode = settings.stereo ? .videoRecording : chosen
            try session.setCategory(.record, mode: mode)
            try session.setPreferredSampleRate(Double(settings.sampleRate))
            try session.setPreferredIOBufferDuration(0.005)
            try session.setActive(true)
            // The built-in mic lists its data sources, and their polar patterns,
            // only once the session is active: before, there is nothing to pick.
            if settings.stereo { preferStereo(session) }
        } catch {
            throw CaptureError(.unavailable, "iOS refused the microphone: \(error.localizedDescription)")
        }

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            throw CaptureError(.unavailable, "There is no microphone input available.")
        }
        sampleRate = Int(format.sampleRate.rounded())
        channelCount = min(Int(format.channelCount), 2)
        let ring = SampleRing(channels: channelCount, capacityFrames: sampleRate)
        self.ring = ring
        let stereo = channelCount == 2

        let sink = AVAudioSinkNode { _, frameCount, bufferList in
            let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: bufferList))
            guard let l = buffers[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            let r = stereo && buffers.count > 1 ? buffers[1].mData?.assumingMemoryBound(to: Float.self) : nil
            ring.write(left: l, right: r, frames: Int(frameCount))
            return noErr
        }
        engine.attach(sink)
        engine.connect(input, to: sink, format: format)
        do {
            try engine.start()
        } catch {
            throw CaptureError(.unavailable, "The microphone could not be started: \(error.localizedDescription)")
        }
        let port = session.currentRoute.inputs.first?.portName ?? "Microphone"
        describe = "\(port) · \(sampleRate / 1000) kHz \(stereo ? "stereo" : "mono")"
    }

    /// iPhones since the XS record stereo from their built-in mics through a
    /// "stereo" polar pattern on the front or back pair. The back pair faces the
    /// sound while the screen faces the listener. Best effort: an input with no
    /// stereo pattern stays mono, which the engine handles.
    private func preferStereo(_ session: AVAudioSession) {
        guard let builtIn = session.availableInputs?.first(where: { $0.portType == .builtInMic }) else { return }
        try? session.setPreferredInput(builtIn)
        let input = session.preferredInput ?? builtIn
        let stereo = (input.dataSources ?? []).filter { $0.supportedPolarPatterns?.contains(.stereo) == true }
        guard let source = stereo.first(where: { $0.orientation == .back }) ?? stereo.first else { return }
        try? source.setPreferredPolarPattern(.stereo)
        try? input.setPreferredDataSource(source)
        try? session.setPreferredInputOrientation(.portrait)
        try? session.setPreferredInputNumberOfChannels(min(2, session.maximumInputNumberOfChannels))
    }

    func read(_ out: UnsafeMutablePointer<Float>, frames: Int) -> Int {
        ring?.read(out, frames: frames) ?? -1
    }

    func stop() {
        ring?.close()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
