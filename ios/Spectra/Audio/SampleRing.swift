import Foundation
import Synchronization

/// Frames from a real-time audio thread to the analysis thread.
///
/// iOS hands audio to a callback on its render thread, which must never
/// allocate, lock or wait; Android's analysis thread instead *pulls* with a
/// blocking read. This ring turns the one into the other, so the engine's loop
/// is the same loop on both: the callback copies into the ring and signals,
/// and `read` blocks until a whole read's worth has arrived.
///
/// Single producer, single consumer. Each side owns one position and only
/// reads the other's, so no lock is needed. If the analysis thread ever falls
/// a full second behind, new audio is dropped rather than overwriting what it
/// is reading — `dropped` counts it.
final class SampleRing: @unchecked Sendable {
    let channels: Int
    private let capacity: Int
    private let buf: UnsafeMutableBufferPointer<Float>
    private let written = Atomic<Int>(0)
    private let consumed = Atomic<Int>(0)
    private let closed = Atomic<Bool>(false)
    let dropped = Atomic<Int>(0)
    private let ready = DispatchSemaphore(value: 0)

    init(channels: Int, capacityFrames: Int) {
        self.channels = channels
        capacity = capacityFrames
        buf = .allocate(capacity: capacityFrames * channels)
        buf.initialize(repeating: 0)
    }

    deinit { buf.deallocate() }

    // ---- producer: the real-time thread ------------------------------------

    /// Writes `frames` frames from planar channel buffers. `right` is ignored for
    /// a mono ring and duplicated from `left` when a stereo ring is fed mono.
    func write(left: UnsafePointer<Float>, right: UnsafePointer<Float>?, frames: Int) {
        let w = written.load(ordering: .relaxed)
        let r = consumed.load(ordering: .acquiring)
        if capacity - (w - r) < frames {
            dropped.add(frames, ordering: .relaxed)
            return
        }
        var pos = w % capacity
        if channels == 1 {
            for i in 0..<frames {
                buf[pos] = left[i]
                pos += 1
                if pos == capacity { pos = 0 }
            }
        } else {
            let rr = right ?? left
            for i in 0..<frames {
                let j = pos * 2
                buf[j] = left[i]
                buf[j + 1] = rr[i]
                pos += 1
                if pos == capacity { pos = 0 }
            }
        }
        written.store(w + frames, ordering: .releasing)
        ready.signal()
    }

    /// Writes `frames` frames of `channels`-channel interleaved audio, keeping the
    /// first one or two channels.
    func write(interleaved src: UnsafePointer<Float>, channels srcChannels: Int, frames: Int) {
        let w = written.load(ordering: .relaxed)
        let r = consumed.load(ordering: .acquiring)
        if capacity - (w - r) < frames {
            dropped.add(frames, ordering: .relaxed)
            return
        }
        var pos = w % capacity
        let second = srcChannels > 1 ? 1 : 0
        for i in 0..<frames {
            let s = i * srcChannels
            if channels == 1 {
                buf[pos] = src[s]
            } else {
                buf[pos * 2] = src[s]
                buf[pos * 2 + 1] = src[s + second]
            }
            pos += 1
            if pos == capacity { pos = 0 }
        }
        written.store(w + frames, ordering: .releasing)
        ready.signal()
    }

    // ---- consumer: the analysis thread -------------------------------------

    /// Frames waiting to be read.
    var available: Int { written.load(ordering: .acquiring) - consumed.load(ordering: .relaxed) }

    /// Blocks until `frames` frames are there (or the ring is closed), then
    /// copies them out interleaved. Returns `frames`, or −1 once closed.
    func read(_ out: UnsafeMutablePointer<Float>, frames: Int) -> Int {
        while available < frames {
            if closed.load(ordering: .acquiring) { return -1 }
            _ = ready.wait(timeout: .now() + .milliseconds(100))
        }
        let r = consumed.load(ordering: .relaxed)
        var pos = r % capacity
        let n = frames * channels
        var o = 0
        while o < n {
            let run = min((capacity - pos) * channels, n - o)
            (out + o).update(from: buf.baseAddress! + pos * channels, count: run)
            o += run
            pos = (pos + run / channels) % capacity
        }
        consumed.store(r + frames, ordering: .releasing)
        return frames
    }

    /// Drops everything waiting — after a seek, so the picture does not show
    /// the old position's last half second. Consumer side only.
    func discard() {
        consumed.store(written.load(ordering: .acquiring), ordering: .releasing)
    }

    /// Wakes a blocked `read` for good.
    func close() {
        closed.store(true, ordering: .releasing)
        ready.signal()
    }
}
