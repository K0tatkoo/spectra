import Foundation
import Testing
import SpectraFixtures
@testable import SpectraCore

/// The tube, against the Kotlin engine's pixels (IosScopesFixtures.kt), and the
/// Kotlin unit tests (app/src/test/.../paint/CrtTest.kt): fed the way the app
/// feeds it, the capture's bursts into a feed and a surface drawing sixty times
/// a (simulated) second.
@Suite struct CrtTests {

    private let rate = 48000

    @Test func colorsScalesAndLayoutsMatchKotlin() throws {
        let f = try Fixture.load("crt")
        let energies = f.f32("energies")
        for name in f.strings("phosphors") {
            let p = Phosphor(rawValue: name)!
            // The tone curve is Double arithmetic rounded to a byte; exp/pow in
            // Darwin's libm and the JVM's agree far inside half a step.
            #expect(energies.map { Crt.colorOf(p, $0) } == f.argb("colorOf.\(name)"), "\(name) tone curve")
        }
        let s = f.f32("scaleCases")
        #expect((0..<(s.count / 3)).map { Crt.internalScale(faceW: s[3 * $0], faceH: s[3 * $0 + 1], density: s[3 * $0 + 2]) }
            == f.f32("internalScale"))
        let c = f.f32("layoutCases")
        var got: [Float] = []
        for k in 0..<(c.count / 7) {
            let lay = CrtLayout()
            lay.compute(left: c[7 * k], top: c[7 * k + 1], right: c[7 * k + 2], bottom: c[7 * k + 3], density: c[7 * k + 4],
                        mode: c[7 * k + 5] == 0 ? .xy : .yt, compact: c[7 * k + 6] != 0)
            got += [lay.faceL, lay.faceT, lay.faceR, lay.faceB, lay.gratL, lay.gratT, lay.gratR, lay.gratB,
                    Float(lay.divisionsX), Float(lay.divisionsY), lay.radius, lay.readoutY, lay.density]
        }
        // Float arithmetic only, same order: bit for bit (NaN readouts included).
        #expect(mismatch(got, f.f32("layouts"), abs: 0) == nil)
    }

    @Test(arguments: ["xy", "yt"])
    func tubeMatchesKotlin(run name: String) throws {
        let f = try Fixture.load("crt")
        func settings(_ tag: String) -> Settings {
            var s = Settings()
            s.oscMode = OscMode(rawValue: f.string("\(name).\(tag).oscMode"))!
            s.oscPhosphor = Phosphor(rawValue: f.string("\(name).\(tag).oscPhosphor"))!
            s.oscIntensity = f.float("\(name).\(tag).oscIntensity")
            s.oscPersistenceMs = f.float("\(name).\(tag).oscPersistenceMs")
            s.oscGlow = f.float("\(name).\(tag).oscGlow")
            s.oscZoom = f.float("\(name).\(tag).oscZoom")
            s.oscTimeDivMs = f.float("\(name).\(tag).oscTimeDivMs")
            return s
        }
        let before = settings("before")
        let after = settings("after")
        let switchAt = f.int("\(name).switchAt")
        let box = f.doubles("\(name).layout").map(Float.init)
        let compact = f.bool("\(name).compact")
        let keep = f.ints("\(name).keep")
        let left = f.f32("\(name).left")
        let right = f.f32("\(name).right")
        let writtenAt = f.i64("\(name).written")
        let want = f.argb("\(name).pixels")

        let feed = StereoFeed(capacityFrames: 32768)
        let crt = Crt()
        let layout = CrtLayout()
        var now: Int64 = 0
        var returned: [Int32] = []
        var triggered: [Int32] = []
        var kept: [UInt32] = []
        for k in 0..<f.int("\(name).frames") {
            let s = k < switchAt ? before : after
            layout.compute(left: box[0], top: box[1], right: box[2], bottom: box[3], density: box[4], mode: s.oscMode, compact: compact)
            now += 16_666_667
            while feed.written < writtenAt[k] {
                let at = Int(feed.written)
                feed.write(left: Array(left[at ..< at + 1024]), right: Array(right[at ..< at + 1024]), count: 1024)
            }
            returned.append(crt.render(feed: feed, rate: rate, settings: s, layout: layout, nowNanos: now, glowAllowed: !compact) ? 1 : 0)
            triggered.append(crt.triggered ? 1 : 0)
            if keep.contains(k) { kept += Array(crt.pixels) }
        }
        #expect(crt.width == f.int("\(name).width") && crt.height == f.int("\(name).height"))
        #expect(returned == f.i32("\(name).returned"), "\(name): which frames drew")
        #expect(triggered == f.i32("\(name).triggered"), "\(name): trigger state")
        try #require(kept.count == want.count)

        // The tube is float arithmetic in the same order as the Kotlin, so the
        // energy agrees but for libm's exp in the fade (Darwin's against the
        // JVM's, an ulp at most). An ulp can carry an energy across one of the
        // tone table's buckets — 256 to a doubling — and the neighbouring entry
        // is at most one step of a channel away. More than ±1 anywhere would be
        // a different beam path, a different fade or a different table.
        var off = 0
        var worst = 0
        for i in kept.indices where kept[i] != want[i] {
            off += 1
            for shift in [16, 8, 0] {
                worst = max(worst, abs(Int(kept[i] >> UInt32(shift) & 0xFF) - Int(want[i] >> UInt32(shift) & 0xFF)))
            }
        }
        print("CRT \(name): \(off) of \(kept.count) pixels differ from the Kotlin's, by at most \(worst) per channel")
        #expect(worst <= 1, "\(name): \(off) pixels differ, worst by \(worst)")
        #expect(kept.allSatisfy { $0 >> 24 == 0xFF }, "every pixel opaque")
    }

    // ---- CrtTest.kt ------------------------------------------------------------

    /// One surface: a tube, its feed and a simulated clock.
    private final class Rig {
        let rate = 48000
        let settings: Settings
        let source: (Int64) -> (Float, Float)
        let feed = StereoFeed(capacityFrames: 32768)
        let crt = Crt()
        let layout = CrtLayout()
        private var l = [Float](repeating: 0, count: 1024)
        private var r = [Float](repeating: 0, count: 1024)
        var written: Int64 = 0
        var captured: Int64 = 0
        var now: Int64 = 0

        init(_ settings: Settings, _ source: @escaping (Int64) -> (Float, Float)) {
            self.settings = settings
            self.source = source
            layout.compute(left: 0, top: 0, right: 1000, bottom: 1100, density: 2.8, mode: settings.oscMode, compact: false)
        }

        func run(_ frames: Int, capturing: Bool = true) {
            for _ in 0..<frames {
                now += 16_666_667
                if capturing {
                    captured += 16_666_667
                    while written + 1024 <= captured * Int64(rate) / 1_000_000_000 {
                        for i in 0..<1024 {
                            let (x, y) = source(written + Int64(i))
                            l[i] = x
                            r[i] = y
                        }
                        feed.write(left: l, right: r, count: 1024)
                        written += 1024
                    }
                }
                crt.render(feed: feed, rate: rate, settings: settings, layout: layout, nowNanos: now)
            }
        }

        /// Green channel at a point on the glass, in the layout's pixels.
        func green(_ x: Float, _ y: Float) -> Int {
            let px = coerceIn(ktToInt(x / 1000 * Float(crt.width)), 0, crt.width - 1)
            let py = coerceIn(ktToInt(y / 1100 * Float(crt.height)), 0, crt.height - 1)
            return Palette.green(crt.pixels[py * crt.width + px])
        }

        /// Where full-scale (x, y) lands on the glass, in the layout's pixels.
        func at(_ x: Float, _ y: Float) -> (Float, Float) {
            let cx = (layout.gratL + layout.gratR) / 2
            let cy = (layout.gratT + layout.gratB) / 2
            return (cx + x * (layout.gratR - layout.gratL) / 2, cy - y * (layout.gratB - layout.gratT) / 2)
        }

        /// The brightest green within a few pixels of full-scale (x, y).
        func peakNear(_ x: Float, _ y: Float) -> Int {
            let (gx, gy) = at(x, y)
            var best = 0
            for dy in -6...6 { for dx in -6...6 { best = max(best, green(gx + Float(dx), gy + Float(dy))) } }
            return best
        }
    }

    @Test func brightnessIsTimeTheBeamsDwellPointsOutshineItsJumps() {
        // A square wave across: the beam rests at each end and flies between.
        let rig = Rig(Settings()) { n in ((n / 120) % 2 == 0 ? -0.5 : 0.5, 0) }
        rig.run(60)
        let end = rig.peakNear(0.5, 0)
        let middle = rig.peakNear(0, 0)
        print("dwell point \(end), mid-jump \(middle)")
        #expect(end > 200)
        #expect(Double(middle) < Double(end) * 0.6, "a jump as bright as a dwell (\(middle) vs \(end))")
    }

    @Test func aCircleComesOutAsARingOfEvenBrightness() {
        let rig = Rig(Settings()) { n in
            let t = Double(n) / 48000
            return (Float(0.6 * cos(2 * Double.pi * 220 * t)), Float(0.6 * sin(2 * Double.pi * 220 * t)))
        }
        rig.run(60)
        let ring = (0..<32).map { k in
            let a = 2 * Double.pi * Double(k) / 32
            return rig.peakNear(Float(0.6 * cos(a)), Float(0.6 * sin(a)))
        }
        let centre = rig.peakNear(0, 0)
        print("ring \(ring.min()!)..\(ring.max()!), centre \(centre)")
        #expect(ring.min()! > 120)
        #expect(Double(ring.min()!) > Double(ring.max()!) * 0.8, "uneven ring: \(ring.min()!)..\(ring.max()!)")
        #expect(centre < ring.min()! / 3, "the centre of a ring lit up (\(centre))")
    }

    @Test func silenceRestsTheBeamInTheMiddle() {
        let rig = Rig(Settings()) { _ in (0, 0) }
        rig.run(30)
        #expect(rig.peakNear(0, 0) > 240)
        #expect(rig.peakNear(0.3, 0.3) < 40)
    }

    @Test func aPausedCaptureFreezesThePictureInsteadOfFadingIt() {
        let rig = Rig(Settings()) { n in
            let t = Double(n) / 48000
            return (Float(0.5 * sin(2 * Double.pi * 150 * t)), Float(0.5 * sin(2 * Double.pi * 225 * t)))
        }
        rig.run(60)
        rig.run(10, capturing: false)
        let frozen = Array(rig.crt.pixels)
        rig.run(30, capturing: false)
        #expect(!rig.crt.changed)
        #expect(frozen == Array(rig.crt.pixels))
        #expect(frozen.filter { Palette.green($0) > 150 }.count > 500)
    }

    @Test func aYTSineStandsStillFromFrameToFrame() {
        var s = Settings()
        s.oscMode = .yt
        s.oscTimeDivMs = 1
        let rig = Rig(s) { n in
            let v = Float(0.6 * sin(2 * Double.pi * 440 * Double(n) / 48000))
            return (v, v)
        }
        rig.run(60)
        var worst = 0.0
        var worstLit = 0.0
        for _ in 0..<40 {
            let before = Array(rig.crt.pixels)
            rig.run(1)
            let after = rig.crt.pixels
            var diff = 0
            var lit = 0
            var litDiff = 0
            for i in before.indices {
                let a = Palette.green(before[i])
                let b = Palette.green(after[i])
                diff += abs(a - b)
                if a > 120 {
                    lit += a
                    litDiff += abs(a - b)
                }
            }
            worst = max(worst, Double(diff) / Double(before.count))
            worstLit = max(worstLit, Double(litDiff) / Double(lit))
        }
        print("Y-T sine: worst mean change per pixel between frames \(worst) / 255; on the trace itself \(worstLit * 100) %")
        // What does change is brightness, not position: 88 sweeps a second
        // against 60 frames means some frames get one new sweep and some two,
        // and the 25 ms afterglow smooths that beat to about 7 %. A real tube
        // has the same beat, only faster than the eye.
        #expect(worst < 1.5, "the trace moves (\(worst))")
        #expect(worstLit < 0.10, "the trace flickers (\(worstLit * 100) %)")
        #expect(rig.crt.triggered)
    }
}
