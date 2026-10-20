import Foundation
import OnnxRuntimeBindings

/// The synth splitter's network, run by ONNX Runtime: one call per frame.
///
/// The contract, set by train/synthsplit/export.py: an input `power` of shape
/// [1, 1, inputs × bands], an output `mask` of the same shape, and any number of
/// carried states — every other input `x` has an output `next_x` of the same
/// shape, fed back on the next call. Like `StemSession`, every tensor is
/// allocated once and the states ping-pong between two pinned sets, so a call
/// allocates nothing.
///
/// No app types, so the unit tests can run it on the Mac.
/// Not thread safe: the splitter worker owns it.
public final class OnnxMaskNet: MaskNet {
    private let session: ORTSession
    private let runOptions: ORTRunOptions
    private let powerIn: UnsafeMutablePointer<Float>
    private let maskOut: UnsafeMutablePointer<Float>
    private let size: Int
    private let feeds: [NSDictionary]
    private let pins: [NSDictionary]

    private var current = 0

    private init(session: ORTSession, runOptions: ORTRunOptions, powerIn: UnsafeMutablePointer<Float>,
                 maskOut: UnsafeMutablePointer<Float>, size: Int, feeds: [NSDictionary], pins: [NSDictionary]) {
        self.session = session
        self.runOptions = runOptions
        self.powerIn = powerIn
        self.maskOut = maskOut
        self.size = size
        self.feeds = feeds
        self.pins = pins
    }

    public func step(_ power: UnsafeBufferPointer<Float>, _ mask: UnsafeMutableBufferPointer<Float>) throws {
        precondition(power.count >= size && mask.count >= size, "step: \(size) values each")
        powerIn.update(from: power.baseAddress!, count: size)
        try runPinned(session, feeds[current], pins[current], runOptions)
        current = 1 - current
        mask.baseAddress!.update(from: maskOut, count: size)
    }

    public static let inPower = "power"
    public static let outMask = "mask"
    public static let nextPrefix = "next_"

    /// Loads a splitter model from its file. Throws, naming the problem, if it cannot.
    ///
    /// A file rather than bytes, which is where the Kotlin differs: the
    /// Objective-C API can only open a session from a path. In the app the
    /// model is a bundle resource, so it is a file anyway.
    public static func open(env: ORTEnv, modelFile: URL, threads: Int = 1) throws -> SplitModel {
        // What the Java API reads off the session — the custom metadata and the
        // input and output shapes — the Objective-C one does not expose, so it
        // comes from the model file itself.
        let session = try ORTSession(env: env, modelPath: modelFile.path, sessionOptions: sessionOptions(threads: threads))
        let info = try OnnxModelInfo(contentsOf: modelFile)
        let layout = try SplitLayout.fromMetadata(info.metadata)
        let size = layout.inputs.count * layout.bands

        func shapeOf(_ name: String, _ from: [String: [Int64]]) throws -> [Int64] {
            guard let shape = from[name] else { throw StemsError("model has no tensor \(name)") }
            try require(shape.allSatisfy { $0 > 0 }, "\(name) has a dynamic shape \(shape)")
            return shape
        }
        let powerShape = try shapeOf(inPower, info.inputs)
        try require(powerShape.reduce(1, *) == Int64(size),
                    "power is \(powerShape), the layout needs \(size) values")
        try require(shapeOf(outMask, info.outputs) == powerShape, "mask and power differ in shape")
        let powerT = try FloatTensor(shape: powerShape)
        let maskT = try FloatTensor(shape: powerShape)

        // The inputs ONNX Runtime asks for: graph inputs that are not initializers.
        let states = try session.inputNames().filter { $0 != inPower }.map { ($0, try shapeOf($0, info.inputs)) }
        for (name, shape) in states {
            try require((try? shapeOf(nextPrefix + name, info.outputs)) == shape, "\(name) has no matching \(nextPrefix)\(name)")
        }
        // Fresh tensors start zeroed: the network's memory starts empty.
        let sets = try (0..<2).map { _ in try states.map { ($0.0, try FloatTensor(shape: $0.1)) } }
        // Call i reads set i and writes set 1 - i.
        let feeds = (0..<2).map { i -> NSDictionary in
            let d = NSMutableDictionary()
            d[inPower] = powerT.value
            for (name, t) in sets[i] { d[name] = t.value }
            return d.copy() as! NSDictionary
        }
        let pins = (0..<2).map { i -> NSDictionary in
            let d = NSMutableDictionary()
            d[outMask] = maskT.value
            for (name, t) in sets[1 - i] { d[nextPrefix + name] = t.value }
            return d.copy() as! NSDictionary
        }
        let net = OnnxMaskNet(session: session, runOptions: try ORTRunOptions(), powerIn: powerT.data,
                              maskOut: maskT.data, size: size, feeds: feeds, pins: pins)
        return SplitModel(layout: layout, net: net)
    }
}

/// What ONNX Runtime's Java API tells about a model before it runs and its
/// Objective-C API does not: the custom metadata (`OrtSession.metadata`) and the
/// shapes of the graph's inputs and outputs (`inputInfo`/`outputInfo`). Read
/// straight from the model file, which is a protobuf `onnx.ModelProto`.
///
/// Only the handful of fields needed are decoded; everything else — the
/// weights included — is skipped by length. A dimension without a fixed value
/// (a named or unknown one) reads as -1, as ONNX Runtime reports it.
struct OnnxModelInfo {
    var metadata: [String: String] = [:]
    var inputs: [String: [Int64]] = [:]
    var outputs: [String: [Int64]] = [:]

    init(contentsOf url: URL) throws {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        try data.withUnsafeBytes { raw in
            try parse(Protobuf(raw))
        }
    }

    // onnx.proto field numbers.
    private enum F {
        // ModelProto
        static let graph = 7, metadataProps = 14
        // StringStringEntryProto
        static let key = 1, value = 2
        // GraphProto
        static let input = 11, output = 12
        // ValueInfoProto
        static let name = 1, type = 2
        // TypeProto, TypeProto.Tensor, TensorShapeProto, TensorShapeProto.Dimension
        static let tensorType = 1, shape = 2, dim = 1, dimValue = 1
    }

    private mutating func parse(_ model: Protobuf) throws {
        var m = model
        while let (field, wire) = try m.next() {
            switch (field, wire) {
            case (F.metadataProps, 2):
                var entry = try m.message()
                var key = "", value = ""
                while let (f, w) = try entry.next() {
                    switch (f, w) {
                    case (F.key, 2): key = try entry.string()
                    case (F.value, 2): value = try entry.string()
                    default: try entry.skip(w)
                    }
                }
                metadata[key] = value
            case (F.graph, 2):
                var graph = try m.message()
                while let (f, w) = try graph.next() {
                    switch (f, w) {
                    case (F.input, 2): let (n, s) = try valueInfo(graph.message()); inputs[n] = s
                    case (F.output, 2): let (n, s) = try valueInfo(graph.message()); outputs[n] = s
                    default: try graph.skip(w)
                    }
                }
            default:
                try m.skip(wire)
            }
        }
    }

    /// A ValueInfoProto's name and tensor shape (empty when it is not a tensor).
    private func valueInfo(_ message: Protobuf) throws -> (String, [Int64]) {
        var v = message
        var name = ""
        var shape: [Int64] = []
        while let (f, w) = try v.next() {
            switch (f, w) {
            case (F.name, 2): name = try v.string()
            case (F.type, 2):
                var type = try v.message()
                while let (tf, tw) = try type.next() {
                    guard tf == F.tensorType, tw == 2 else { try type.skip(tw); continue }
                    var tensor = try type.message()
                    while let (sf, sw) = try tensor.next() {
                        guard sf == F.shape, sw == 2 else { try tensor.skip(sw); continue }
                        var dims = try tensor.message()
                        while let (df, dw) = try dims.next() {
                            guard df == F.dim, dw == 2 else { try dims.skip(dw); continue }
                            var dim = try dims.message()
                            var size: Int64 = -1
                            while let (xf, xw) = try dim.next() {
                                if xf == F.dimValue, xw == 0 { size = Int64(bitPattern: try dim.varint()) } else { try dim.skip(xw) }
                            }
                            shape.append(size)
                        }
                    }
                }
            default: try v.skip(w)
            }
        }
        return (name, shape)
    }
}

/// A forward-only reader of protobuf's wire format over borrowed bytes.
private struct Protobuf {
    private let bytes: UnsafeRawBufferPointer
    private var at: Int

    init(_ bytes: UnsafeRawBufferPointer) {
        self.bytes = bytes
        at = 0
    }

    private static func malformed() -> StemsError { StemsError("the model file is not a valid ONNX model") }

    /// The next field's number and wire type, or nil at the end.
    mutating func next() throws -> (Int, Int)? {
        if at >= bytes.count { return nil }
        let tag = try varint()
        return (Int(tag >> 3), Int(tag & 7))
    }

    mutating func varint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while true {
            guard at < bytes.count, shift < 64 else { throw Protobuf.malformed() }
            let b = bytes[at]
            at += 1
            result |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 { return result }
            shift += 7
        }
    }

    /// A length-delimited field's bytes, as a reader of their own.
    mutating func message() throws -> Protobuf {
        let length = try varint()
        guard length <= UInt64(bytes.count - at) else { throw Protobuf.malformed() }
        let sub = Protobuf(UnsafeRawBufferPointer(rebasing: bytes[at ..< at + Int(length)]))
        at += Int(length)
        return sub
    }

    mutating func string() throws -> String {
        let m = try message()
        return String(decoding: m.bytes, as: UTF8.self)
    }

    /// Skips a field of wire type `wire`.
    mutating func skip(_ wire: Int) throws {
        switch wire {
        case 0: _ = try varint()
        case 1: at += 8
        case 2: _ = try message()
        case 5: at += 4
        default: throw Protobuf.malformed() // groups (3, 4) never appear in ONNX
        }
        guard at <= bytes.count else { throw Protobuf.malformed() }
    }
}
