/// Fixed-size circular history of the most recent `size` samples.
///
/// The FFT needs a window that overlaps the previous one; the capture callback
/// delivers arbitrary chunk sizes that have nothing to do with the window size.
/// This is the piece that decouples them, and `pending` is what tells the engine
/// how many new samples have arrived since the last transform so it can fire one
/// hop at a time instead of once per chunk.
///
/// Owned by one thread (the analysis thread), like MonoRing.kt.
public final class MonoRing {
    public let size: Int
    private var buf: [Float]
    private var writeIndex = 0

    /// New samples written since the last `consume`.
    public private(set) var pending = 0

    public init(size: Int) {
        self.size = size
        buf = [Float](repeating: 0, count: size)
    }

    public func write(_ src: UnsafePointer<Float>, offset: Int, count: Int) {
        var remaining = count
        var from = offset
        buf.withUnsafeMutableBufferPointer { b in
            while remaining > 0 {
                let room = size - writeIndex
                let n = min(room, remaining)
                (b.baseAddress! + writeIndex).update(from: src + from, count: n)
                writeIndex = (writeIndex + n) % size
                from += n
                remaining -= n
            }
        }
        pending += count
        if pending > size { pending = size }
    }

    public func write(_ src: [Float], offset: Int, count: Int) {
        src.withUnsafeBufferPointer { write($0.baseAddress!, offset: offset, count: count) }
    }

    public func consume(_ n: Int) {
        pending -= n
        if pending < 0 { pending = 0 }
    }

    /// Copies the whole history into `out` oldest sample first.
    public func copyLatest(_ out: inout [Float]) {
        let tail = size - writeIndex
        buf.withUnsafeBufferPointer { b in
            out.withUnsafeMutableBufferPointer { o in
                o.baseAddress!.update(from: b.baseAddress! + writeIndex, count: tail)
                (o.baseAddress! + tail).update(from: b.baseAddress!, count: writeIndex)
            }
        }
    }

    /// Copies the newest `count` samples into `out`, oldest first, decimating by
    /// `stride`. Used for the scope trace, where drawing 4096 points into 400 px
    /// would just be four thousand ways to draw the same line.
    public func copyNewestDecimated(_ out: inout [Float], count: Int, stride: Int) {
        let start = ((writeIndex - count) % size + size) % size
        var idx = start
        buf.withUnsafeBufferPointer { b in
            for w in 0..<out.count {
                out[w] = b[idx]
                idx += stride
                if idx >= size { idx -= size }
            }
        }
    }

    public func clear() {
        for i in buf.indices { buf[i] = 0 }
        writeIndex = 0
        pending = 0
    }
}
