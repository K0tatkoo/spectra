import Foundation

/// A running PCM source — AudioCapture.kt for iPhone. Every implementation hands
/// back interleaved floats in −1…1, so the engine never has to care whether the
/// samples came from the microphone, a file Spectra is playing, or another app.
protocol AudioCapture: AnyObject {
    var sampleRate: Int { get }
    var channelCount: Int { get }
    /// The concrete input iOS gave us, for display when it differs from the request.
    var describe: String { get }

    /// Throws `CaptureError` if the source cannot be opened.
    func start() throws

    /// Blocks until `frames` frames are available and copies them, interleaved,
    /// into `out`. Returns the frames read: `frames`, or 0 to say "nothing yet,
    /// ask again", or −1 once the source has stopped for good.
    func read(_ out: UnsafeMutablePointer<Float>, frames: Int) -> Int

    func stop()
}

struct CaptureError: Error {
    enum Kind {
        /// Microphone access not granted, or revoked while running.
        case permission
        /// Another app holds the input, or iOS refused the configuration.
        case unavailable
        /// "Other apps" was chosen but no screen broadcast is sending audio.
        case broadcast
        case unknown
    }

    let kind: Kind
    let message: String

    init(_ kind: Kind, _ message: String) {
        self.kind = kind
        self.message = message
    }
}
