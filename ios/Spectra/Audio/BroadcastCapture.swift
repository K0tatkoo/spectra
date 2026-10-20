import Foundation

/// Other apps' sound, as it arrives from Spectra's screen-broadcast extension.
///
/// The extension runs only while the user broadcasts (started from the input
/// sheet or Control Center), so this starts only when a broadcast is already
/// sending — otherwise it says how to start one — and ends when it stops.
final class BroadcastCapture: AudioCapture {
    private var ring: BroadcastRing?
    private var position: UInt64 = 0
    private var stopped = false
    private(set) var sampleRate = 44100
    let channelCount = 2
    let describe = "Screen broadcast"

    func start() throws {
        guard let ring = BroadcastRing(writer: false), ring.silenceMs < 2000, ring.sampleRate > 0 else {
            throw CaptureError(.broadcast, "Spectra hears other apps through a screen broadcast. Open the input "
                + "sheet, tap the broadcast button, choose Spectra and start it — the picture begins as soon as sound arrives.")
        }
        self.ring = ring
        sampleRate = ring.sampleRate
        // Start at the present, a little behind the writer.
        position = ring.written > 2048 ? ring.written - 2048 : 0
    }

    func read(_ out: UnsafeMutablePointer<Float>, frames: Int) -> Int {
        guard let ring else { return -1 }
        var waited = 0
        while !stopped {
            if ring.sampleRate != sampleRate { return -1 }
            let w = ring.written
            if w > position + UInt64(BroadcastRing.capacity / 2) {
                // Fell far behind (the app was busy): skip to the present.
                position = w - UInt64(frames)
            }
            if ring.read(from: position, frames: frames, into: out) {
                position += UInt64(frames)
                return frames
            }
            if ring.silenceMs > 3000 { return -1 }
            usleep(4000)
            waited += 4
            if waited >= 100 { return 0 }
        }
        return -1
    }

    func stop() { stopped = true }
}
