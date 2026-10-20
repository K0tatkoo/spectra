import Synchronization

/// Circular column store for the spectrogram.
///
/// Columns are quantised to a byte (0 = at or below the floor, 255 = at the
/// ceiling) rather than kept as floats. At 256 rows × 512 columns that is 128 KB
/// instead of 512 KB, and it is what the painter wants anyway — a colour-map
/// lookup index. The dB range is baked in at write time, so changing the range
/// in settings clears the history; the alternative (keeping dB and re-mapping on
/// every draw) costs a multiply per pixel per frame.
///
/// Written by the analysis thread, read by the painter on another. There is no
/// lock: the worst possible outcome is one torn column in one frame, which is
/// invisible, and a lock here would put the audio thread at the mercy of a
/// painter's scheduling. That is why this is `@unchecked Sendable`: the storage
/// is raw memory that both threads touch, and the index is an atomic.
public final class SpectrogramBuffer: @unchecked Sendable {
    public let rows: Int
    public let columns: Int
    /// rows × columns bytes, column-major: column c is `data[c*rows ..< (c+1)*rows]`.
    public let data: UnsafeMutableBufferPointer<UInt8>
    private let _head = Atomic<Int>(0)
    private let _filled = Atomic<Int>(0)

    public init(rows: Int, columns: Int) {
        self.rows = rows
        self.columns = columns
        data = .allocate(capacity: rows * columns)
        data.initialize(repeating: 0)
    }

    deinit { data.deallocate() }

    /// Index of the column that will be written next — i.e. the oldest column.
    public var head: Int { _head.load(ordering: .acquiring) }
    public var filled: Int { _filled.load(ordering: .acquiring) }

    public func push(_ column: [UInt8]) {
        let h = head
        let base = h * rows
        let n = min(rows, column.count)
        column.withUnsafeBufferPointer { c in
            (data.baseAddress! + base).update(from: c.baseAddress!, count: n)
        }
        _head.store((h + 1) % columns, ordering: .releasing)
        let f = filled
        if f < columns { _filled.store(f + 1, ordering: .releasing) }
    }

    public func clear() {
        data.update(repeating: 0)
        _head.store(0, ordering: .releasing)
        _filled.store(0, ordering: .releasing)
    }
}

/// A fixed-length scrolling history of one scalar — used for the per-band level
/// graphs and for the loudness trace. Same reasoning as above on locking.
public final class ScrollBuffer: @unchecked Sendable {
    public let capacity: Int
    private let initial: Float
    public let data: UnsafeMutableBufferPointer<Float>
    private let _head = Atomic<Int>(0)

    public init(capacity: Int, initial: Float = -140) {
        self.capacity = capacity
        self.initial = initial
        data = .allocate(capacity: capacity)
        data.initialize(repeating: initial)
    }

    deinit { data.deallocate() }

    public var head: Int { _head.load(ordering: .acquiring) }

    public func push(_ value: Float) {
        let h = head
        data[h] = value
        _head.store((h + 1) % capacity, ordering: .releasing)
    }

    /// Copies the history into `out` oldest-first, so painters can walk it linearly.
    public func snapshot(_ out: inout [Float]) {
        let h = head
        let tail = capacity - h
        out.withUnsafeMutableBufferPointer { o in
            o.baseAddress!.update(from: data.baseAddress! + h, count: tail)
            (o.baseAddress! + tail).update(from: data.baseAddress!, count: h)
        }
    }

    public func clear() {
        data.update(repeating: initial)
        _head.store(0, ordering: .releasing)
    }
}
