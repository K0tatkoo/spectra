import Foundation

/// Reference results written by the Kotlin engine — the desktop dev task
/// `./gradlew :desktop:iosFixtures` (desktop/src/main/kotlin/com/n3d/spectra/desktop/dev/IosFixtures.kt).
///
/// A fixture is `<name>.json` (metadata, plus an index of the arrays) and
/// `<name>.bin` (the arrays, little-endian, back to back). The inputs a
/// fixture was made from are stored in it too, so the Swift side never has to
/// regenerate a signal and agree with Kotlin about how.
public struct Fixture {
    public let name: String
    private let meta: [String: Any]
    private let index: [String: (type: String, offset: Int, count: Int)]
    private let bin: Data

    /// The `Fixtures` folder beside the test file that asks, e.g.
    /// `Fixture.load("fft")` from Tests/SpectraCoreTests/FftTests.swift reads
    /// Tests/SpectraCoreTests/Fixtures/fft.json.
    public static func load(_ name: String, testFile: StaticString = #filePath) throws -> Fixture {
        let dir = URL(fileURLWithPath: "\(testFile)").deletingLastPathComponent().appendingPathComponent("Fixtures")
        return try load(name, in: dir)
    }

    public static func load(_ name: String, in dir: URL) throws -> Fixture {
        let json = try Data(contentsOf: dir.appendingPathComponent("\(name).json"))
        let bin = (try? Data(contentsOf: dir.appendingPathComponent("\(name).bin"))) ?? Data()
        guard let root = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw FixtureError.malformed(name)
        }
        var index: [String: (String, Int, Int)] = [:]
        for (key, value) in (root["arrays"] as? [String: Any]) ?? [:] {
            guard let entry = value as? [String: Any],
                  let type = entry["type"] as? String,
                  let offset = (entry["offset"] as? NSNumber)?.intValue,
                  let count = (entry["count"] as? NSNumber)?.intValue else { throw FixtureError.malformed(name) }
            index[key] = (type, offset, count)
        }
        return Fixture(name: name, meta: (root["meta"] as? [String: Any]) ?? [:], index: index, bin: bin)
    }

    public var keys: [String] { index.keys.sorted() }

    // ---- metadata ---------------------------------------------------------

    public func int(_ key: String) -> Int { (meta[key] as! NSNumber).intValue }
    public func double(_ key: String) -> Double { (meta[key] as! NSNumber).doubleValue }
    public func float(_ key: String) -> Float { (meta[key] as! NSNumber).floatValue }
    public func bool(_ key: String) -> Bool { (meta[key] as! NSNumber).boolValue }
    public func string(_ key: String) -> String { meta[key] as! String }
    public func has(_ key: String) -> Bool { meta[key] != nil || index[key] != nil }
    public func strings(_ key: String) -> [String] { meta[key] as! [String] }
    public func ints(_ key: String) -> [Int] { (meta[key] as! [NSNumber]).map(\.intValue) }
    public func doubles(_ key: String) -> [Double] { (meta[key] as! [NSNumber]).map(\.doubleValue) }

    // ---- arrays -------------------------------------------------------------

    public func f32(_ key: String) -> [Float] { read(key, "f32", Float.self) }
    public func f64(_ key: String) -> [Double] { read(key, "f64", Double.self) }
    public func i32(_ key: String) -> [Int32] { read(key, "i32", Int32.self) }
    public func i64(_ key: String) -> [Int64] { read(key, "i64", Int64.self) }
    /// Kotlin ByteArray, bit for bit (a Kotlin byte of -1 reads as 255).
    public func u8(_ key: String) -> [UInt8] { read(key, "u8", UInt8.self) }
    /// Kotlin `Int` colours (ARGB) as the unsigned 32-bit values Swift keeps them in.
    public func argb(_ key: String) -> [UInt32] { i32(key).map { UInt32(bitPattern: $0) } }

    private func read<T>(_ key: String, _ type: String, _: T.Type) -> [T] {
        guard let entry = index[key] else { fatalError("\(name): no array '\(key)' (has \(keys))") }
        precondition(entry.type == type, "\(name).\(key) is \(entry.type), not \(type)")
        let bytes = MemoryLayout<T>.size * entry.count
        return bin[entry.offset ..< entry.offset + bytes].withUnsafeBytes { raw in
            (0..<entry.count).map { raw.loadUnaligned(fromByteOffset: $0 * MemoryLayout<T>.size, as: T.self) }
        }
    }
}

public enum FixtureError: Error { case malformed(String) }

/// Where two arrays first disagree beyond a tolerance — nil when they agree.
///
/// A value passes when `|a − b| ≤ abs + rel·|b|`. `b` is the Kotlin reference.
/// Two NaNs or two equal infinities agree.
public func mismatch(_ a: [Float], _ b: [Float], abs: Float, rel: Float = 0) -> String? {
    if a.count != b.count { return "length \(a.count) vs \(b.count)" }
    var worst: (i: Int, d: Float)? = nil
    var failures = 0
    for i in a.indices {
        let x = a[i], y = b[i]
        if x.isNaN && y.isNaN { continue }
        if x.isInfinite || y.isInfinite { if x == y { continue } }
        let d = Swift.abs(x - y)
        if !(d <= abs + rel * Swift.abs(y)) {
            failures += 1
            if worst == nil || d > worst!.d { worst = (i, d) }
        }
    }
    guard let w = worst else { return nil }
    return "\(failures) of \(a.count) differ; worst at [\(w.i)]: \(a[w.i]) vs \(b[w.i]) (Δ \(w.d))"
}

public func mismatch(_ a: [Double], _ b: [Double], abs: Double, rel: Double = 0) -> String? {
    if a.count != b.count { return "length \(a.count) vs \(b.count)" }
    var worst: (i: Int, d: Double)? = nil
    var failures = 0
    for i in a.indices {
        let x = a[i], y = b[i]
        if x.isNaN && y.isNaN { continue }
        if x.isInfinite || y.isInfinite { if x == y { continue } }
        let d = Swift.abs(x - y)
        if !(d <= abs + rel * Swift.abs(y)) {
            failures += 1
            if worst == nil || d > worst!.d { worst = (i, d) }
        }
    }
    guard let w = worst else { return nil }
    return "\(failures) of \(a.count) differ; worst at [\(w.i)]: \(a[w.i]) vs \(b[w.i]) (Δ \(w.d))"
}
