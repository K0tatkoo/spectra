import Synchronization

/// A mono history that one thread writes and another reads, indexed by the
/// absolute number of samples ever written.
///
/// The absolute index is what lets a display hold something *still*: a trigger
/// that fired at sample 1 234 567 still names the same sample a second later,
/// whereas an offset from "now" moves every time a block arrives.
///
/// Single producer, single consumer, no locks. The writer copies first and
/// publishes `written` after, so a reader that snapshots `written` and copies
/// the samples behind it never sees a half-written block. The only way to read
/// garbage is for the writer to lap the reader during one copy, which needs the
/// capacity minus the read length to arrive in the time it takes to copy the
/// read length — a second and a half of audio in a few microseconds.
public final class HistoryRing: @unchecked Sendable {
    public let capacity: Int
    private let mask: Int64
    private let buf: UnsafeMutableBufferPointer<Float>
    private let _written = Atomic<Int64>(0)

    public init(capacity: Int) {
        self.capacity = nextPowerOfTwo(atLeast: capacity)
        mask = Int64(self.capacity - 1)
        buf = .allocate(capacity: self.capacity)
        buf.initialize(repeating: 0)
    }

    deinit { buf.deallocate() }

    /// Total samples ever written.
    public var written: Int64 { _written.load(ordering: .acquiring) }

    public func write(_ src: UnsafePointer<Float>, offset: Int, count: Int) {
        var w = written
        for i in 0..<count {
            buf[Int(w & mask)] = src[offset + i]
            w += 1
        }
        _written.store(w, ordering: .releasing)
    }

    public func write(_ src: [Float], offset: Int, count: Int) {
        src.withUnsafeBufferPointer { write($0.baseAddress!, offset: offset, count: count) }
    }

    /// Copies the `count` samples that end at absolute index `end` (exclusive)
    /// into `dst`, oldest first. Samples before the start of the stream read as
    /// zero. Returns false if `end` is ahead of the writer or already overwritten.
    @discardableResult
    public func read(end: Int64, into dst: UnsafeMutablePointer<Float>, count: Int) -> Bool {
        let w = written
        if end > w || count > capacity || end - Int64(count) < w - Int64(capacity) { return false }
        let start = end - Int64(count)
        for i in 0..<count {
            let abs = start + Int64(i)
            dst[i] = abs < 0 ? 0 : buf[Int(abs & mask)]
        }
        return true
    }

    @discardableResult
    public func read(end: Int64, into dst: inout [Float], count: Int) -> Bool {
        dst.withUnsafeMutableBufferPointer { read(end: end, into: $0.baseAddress!, count: count) }
    }

    /// Moves the stream on to absolute index `end` as silence — for a writer that
    /// has to stay index-aligned with another stream across a jump. Costs at most
    /// one pass over the ring, however far it jumps.
    public func advanceTo(_ end: Int64) {
        let w = written
        if end <= w { return }
        var i = end - min(end - w, Int64(capacity))
        while i < end {
            buf[Int(i & mask)] = 0
            i += 1
        }
        _written.store(end, ordering: .releasing)
    }

    public func clear() {
        buf.update(repeating: 0)
        _written.store(0, ordering: .releasing)
    }
}

/// Stereo frames from the analysis thread to another thread: the stem worker,
/// and the Oscilloscope page's beam, which reads the stretch it needs.
///
/// Unlike `HistoryRing` the reader here *consumes*: it keeps its own position and
/// asks how far behind the writer it is, which is what lets the worker notice it
/// has fallen behind and skip ahead rather than drift ever further from "now".
public final class StereoFeed: @unchecked Sendable {
    public let capacity: Int
    private let mask: Int64
    /// Interleaved L, R.
    private let buf: UnsafeMutableBufferPointer<Float>
    private let _written = Atomic<Int64>(0)

    public init(capacityFrames: Int) {
        capacity = nextPowerOfTwo(atLeast: capacityFrames)
        mask = Int64(capacity - 1)
        buf = .allocate(capacity: capacity * 2)
        buf.initialize(repeating: 0)
    }

    deinit { buf.deallocate() }

    /// Total frames ever written.
    public var written: Int64 { _written.load(ordering: .acquiring) }

    public func write(left: UnsafePointer<Float>, right: UnsafePointer<Float>, count: Int) {
        var w = written
        for i in 0..<count {
            let j = Int(w & mask) * 2
            buf[j] = left[i]
            buf[j + 1] = right[i]
            w += 1
        }
        _written.store(w, ordering: .releasing)
    }

    public func write(left: [Float], right: [Float], count: Int) {
        left.withUnsafeBufferPointer { l in
            right.withUnsafeBufferPointer { r in
                write(left: l.baseAddress!, right: r.baseAddress!, count: count)
            }
        }
    }

    /// Copies `count` frames starting at absolute frame `from` into `dst` as
    /// planar channels: `count` left samples, then `count` right samples — the
    /// `[1, 2, count]` layout the stem model takes. False if they are not all there.
    @discardableResult
    public func readPlanar(from: Int64, count: Int, into dst: UnsafeMutablePointer<Float>) -> Bool {
        let w = written
        if from + Int64(count) > w || from < w - Int64(capacity) { return false }
        for i in 0..<count {
            let j = Int((from + Int64(i)) & mask) * 2
            dst[i] = buf[j]
            dst[count + i] = buf[j + 1]
        }
        return true
    }

    @discardableResult
    public func readPlanar(from: Int64, count: Int, into dst: inout [Float]) -> Bool {
        dst.withUnsafeMutableBufferPointer { readPlanar(from: from, count: count, into: $0.baseAddress!) }
    }
}
