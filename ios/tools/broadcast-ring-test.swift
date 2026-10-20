// The broadcast ring, tested on the Mac: broadcast extensions do not run in the
// simulator, so this is the only check the extension-to-app path gets before a
// real iPhone. From the repo root:
//
//   swiftc -import-objc-header ios/Shared/SpectraShared.h ios/Shared/BroadcastRing.swift \
//     ios/tools/broadcast-ring-test.swift -o /tmp/ringtest && /tmp/ringtest

import Foundation
// Writer (the extension's side) and reader (the app's side) on one file.
let path = NSTemporaryDirectory() + "ring-test-\(getpid())"
guard let writer = BroadcastRing(writer: true, path: path), let reader = BroadcastRing(writer: false, path: path) else {
    print("FAIL: could not open"); exit(1)
}
precondition(reader.silenceMs == .max, "no heartbeat before the first write")
// 16-bit big-endian interleaved stereo, as ReplayKit can deliver it, converted the way AudioConversion does.
var raw = [UInt16](repeating: 0, count: 2048 * 2)
for i in 0..<2048 { raw[i * 2] = UInt16(bitPattern: Int16(i * 8)).byteSwapped; raw[i * 2 + 1] = UInt16(bitPattern: Int16(-i * 8)).byteSwapped }
writer.write(frames: 2048, sampleRate: 44100) { i, c in Float(Int16(bitPattern: raw[i * 2 + c].byteSwapped)) / 32768 }
precondition(reader.sampleRate == 44100 && reader.written == 2048 && reader.silenceMs < 1000)
var out = [Float](repeating: 0, count: 4)
precondition(reader.read(from: 100, frames: 2, into: &out))
let expected: [Float] = [800, -800, 808, -808].map { $0 / 32768 }; precondition(out == expected, "got \(out)")
precondition(!reader.read(from: 2047, frames: 2, into: &out), "not written yet")
// Wrap the ring several times over and read the newest frames.
for _ in 0..<200 { writer.write(frames: 4096, sampleRate: 44100) { i, c in Float(i) * (c == 0 ? 1 : -1) } }
let w = reader.written
precondition(reader.read(from: w - 2, frames: 2, into: &out))
precondition(out == [4094, -4094, 4095, -4095], "got \(out)")
precondition(!reader.read(from: w - UInt64(BroadcastRing.capacity) - 10, frames: 2, into: &out), "overwritten")
writer.markStopped()
precondition(reader.silenceMs == .max, "stopped reads as silence for good")
unlink(path)
print("OK: ring round-trips, wraps, refuses stale and future reads, heartbeat and stop work")
