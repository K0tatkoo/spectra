import Foundation

/// Other apps' sound, from the broadcast extension to the app.
///
/// iOS hands an app the sound of other apps only inside a screen-broadcast
/// extension, which runs as its own process. The extension writes the audio
/// into a file in the App Group that both processes map, and the app reads it
/// back — a single-producer, single-consumer ring like the one inside the app,
/// except that its positions live in the shared memory.
///
/// Layout: a 64-byte header, then `capacity` interleaved stereo float frames.
///
///     0  magic 'SPBR'      4  version
///     8  sample rate       12 channels (2)
///     16 frames written    (published with release ordering)
///     24 heartbeat         (the writer's clock, ms, at its last write)
///     32 capacity, frames
final class BroadcastRing: @unchecked Sendable {
    static let appGroup = "group.com.n3d.spectra"
    static let capacity = 1 << 17
    private static let magic: UInt32 = 0x5350_4252
    private static let version: UInt32 = 1
    private static let headerBytes = 64

    private let base: UnsafeMutableRawPointer
    private let length: Int
    private let samples: UnsafeMutablePointer<Float>

    /// Opens (and for the writer, creates) the shared file. Nil without the App Group.
    init?(writer: Bool) {
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) else {
            return nil
        }
        let path = dir.appendingPathComponent("broadcast-audio.ring").path
        let fd = open(path, writer ? (O_RDWR | O_CREAT) : O_RDWR, 0o644)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        let length = Self.headerBytes + Self.capacity * 2 * MemoryLayout<Float>.size
        if writer { guard ftruncate(fd, off_t(length)) == 0 else { return nil } }
        guard let mapped = mmap(nil, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0), mapped != MAP_FAILED else {
            return nil
        }
        base = mapped
        self.length = length
        samples = (mapped + Self.headerBytes).assumingMemoryBound(to: Float.self)
        if writer {
            base.storeBytes(of: Self.magic, toByteOffset: 0, as: UInt32.self)
            base.storeBytes(of: Self.version, toByteOffset: 4, as: UInt32.self)
            base.storeBytes(of: 2, toByteOffset: 12, as: UInt32.self)
            base.storeBytes(of: UInt32(Self.capacity), toByteOffset: 32, as: UInt32.self)
        } else if base.load(fromByteOffset: 0, as: UInt32.self) != Self.magic {
            munmap(base, length)
            return nil
        }
    }

    deinit { munmap(base, length) }

    var sampleRate: Int { Int(base.load(fromByteOffset: 8, as: UInt32.self)) }
    var written: UInt64 { spectra_load_acquire(base + 16) }
    /// Milliseconds since the writer last wrote; very large when it never has.
    var silenceMs: UInt64 {
        let beat = spectra_load_acquire(base + 24)
        let now = Self.clockMs()
        return beat == 0 || beat > now ? .max : now - beat
    }

    // ---- writer: the extension --------------------------------------------------

    /// Appends `frames` frames. `sample(i, channel)` gives each one as a float;
    /// called once per sample, so a conversion needs no buffer of its own.
    func write(frames: Int, sampleRate rate: Int, sample: (Int, Int) -> Float) {
        if sampleRate != rate {
            // A new rate starts a new stream: the reader notices and reopens.
            base.storeBytes(of: UInt32(rate), toByteOffset: 8, as: UInt32.self)
        }
        let w = written
        var pos = Int(w % UInt64(Self.capacity))
        for i in 0..<frames {
            samples[pos * 2] = sample(i, 0)
            samples[pos * 2 + 1] = sample(i, 1)
            pos += 1
            if pos == Self.capacity { pos = 0 }
        }
        spectra_store_release(base + 16, w + UInt64(frames))
        spectra_store_release(base + 24, Self.clockMs())
    }

    /// The broadcast ended: make the reader see silence for good.
    func markStopped() { spectra_store_release(base + 24, 0) }

    // ---- reader: the app ------------------------------------------------------------

    /// Copies `frames` frames starting at absolute frame `from` into `out`,
    /// interleaved stereo. False if they are not all there (yet, or any more).
    func read(from: UInt64, frames: Int, into out: UnsafeMutablePointer<Float>) -> Bool {
        let w = written
        if from + UInt64(frames) > w || w - from > UInt64(Self.capacity) { return false }
        var pos = Int(from % UInt64(Self.capacity))
        for i in 0..<frames {
            out[i * 2] = samples[pos * 2]
            out[i * 2 + 1] = samples[pos * 2 + 1]
            pos += 1
            if pos == Self.capacity { pos = 0 }
        }
        return true
    }

    static func clockMs() -> UInt64 { UInt64(Date().timeIntervalSince1970 * 1000) }
}
