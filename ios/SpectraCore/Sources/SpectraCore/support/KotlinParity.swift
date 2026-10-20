// The handful of places where Kotlin and Swift arithmetic disagree, written down
// once. The engine is a port, and these are the differences that would make the
// Swift numbers drift from the Kotlin ones without any line looking wrong.
//
//  - Kotlin's roundToInt/roundToLong (Math.round) round a half UP, toward +∞:
//    -2.5 → -2. Swift's rounded() rounds a half away from zero: -2.5 → -3.
//  - Kotlin's Float.toInt() truncates and SATURATES (NaN → 0, ±∞ → Int.MIN/MAX).
//    Swift's Int(x) traps on NaN, infinity and anything out of range.
//  - Kotlin's Int is 32 bits and wraps on overflow; Swift's traps. Use &+ &* where
//    the Kotlin relies on wrapping (hashes, LCGs), and Int32 where width matters.
//
// Kotlin's Float % Float and Swift's truncatingRemainder agree (sign of the
// dividend), as do integer / and %. Shifts: Kotlin `ushr` is >> on an unsigned value.

/// Kotlin `Float.roundToInt()`: half up, NaN → 0, saturating at the Int32 range.
@inlinable @inline(__always)
public func ktRoundToInt(_ x: Float) -> Int {
    if x.isNaN { return 0 }
    return ktToInt((x + 0.5).rounded(.down))
}

/// Kotlin `Double.roundToInt()`: half up, NaN → 0, saturating at the Int32 range.
@inlinable @inline(__always)
public func ktRoundToInt(_ x: Double) -> Int {
    if x.isNaN { return 0 }
    return ktToInt((x + 0.5).rounded(.down))
}

/// Kotlin `Double.roundToLong()`: half up, NaN → 0, saturating at the Int64 range.
@inlinable @inline(__always)
public func ktRoundToLong(_ x: Double) -> Int64 {
    if x.isNaN { return 0 }
    let r = (x + 0.5).rounded(.down)
    if r >= 9.223372036854775807e18 { return .max }
    if r <= -9.223372036854775808e18 { return .min }
    return Int64(r)
}

/// Kotlin `Float.toInt()`: truncates toward zero, NaN → 0, saturating at the Int32 range.
@inlinable @inline(__always)
public func ktToInt(_ x: Float) -> Int {
    if x.isNaN { return 0 }
    if x >= 2147483647 { return Int(Int32.max) }
    if x <= -2147483648 { return Int(Int32.min) }
    return Int(x)
}

/// Kotlin `Double.toInt()`: truncates toward zero, NaN → 0, saturating at the Int32 range.
@inlinable @inline(__always)
public func ktToInt(_ x: Double) -> Int {
    if x.isNaN { return 0 }
    if x >= 2147483647 { return Int(Int32.max) }
    if x <= -2147483648 { return Int(Int32.min) }
    return Int(x)
}

/// Kotlin `coerceIn`.
@inlinable @inline(__always)
public func coerceIn<T: Comparable>(_ x: T, _ lo: T, _ hi: T) -> T {
    x < lo ? lo : (x > hi ? hi : x)
}

/// Java `Integer.highestOneBit(capacity - 1) shl 1`: the power of two at or above
/// `capacity`, the way the rings size themselves.
@inlinable
public func nextPowerOfTwo(atLeast capacity: Int) -> Int {
    let x = capacity - 1
    if x <= 0 { return 0 }
    return 1 << (Int.bitWidth - x.leadingZeroBitCount)
}
