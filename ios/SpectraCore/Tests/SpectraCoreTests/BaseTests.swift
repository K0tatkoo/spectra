import Testing
import SpectraFixtures
@testable import SpectraCore

/// The pieces everything else stands on, against what the Kotlin engine
/// computed (IosBaseFixtures.kt).
@Suite struct BaseTests {

    @Test(arguments: [1024, 4096])
    func fftMatchesKotlin(size: Int) throws {
        let f = try Fixture.load("fft_\(size)")
        var re = f.f32("re")
        var im = f.f32("im")
        Fft(size: size).transform(&re, &im)
        // Peak magnitude is ~0.4·size; a few float ulps of that is the noise.
        let tol = Float(size) * 2e-6
        #expect(mismatch(re, f.f32("outRe"), abs: tol) == nil, "real part")
        #expect(mismatch(im, f.f32("outIm"), abs: tol) == nil, "imaginary part")
    }

    @Test func windowsMatchKotlin() throws {
        let f = try Fixture.load("windows")
        for name in f.strings("names") {
            let built = WindowFunction(rawValue: name)!.build(f.int("n"))
            #expect(mismatch(built.samples, f.f32("\(name).samples"), abs: 1e-7) == nil, "\(name) samples")
            #expect(mismatch([built.coherentGain, built.enbw, built.sumSquares], f.f32("\(name).constants"),
                             abs: 0, rel: 1e-6) == nil, "\(name) constants")
        }
    }

    @Test func biquadsMatchKotlin() throws {
        let f = try Fixture.load("biquads")
        let input = f.f32("input")
        let filters: [String: Biquad] = [
            "lowPass48k1k": .lowPass(sampleRate: 48000, freq: 1000),
            "lowPass44k80q2": .lowPass(sampleRate: 44100, freq: 80, q: 2),
            "highPass48k20": .highPass(sampleRate: 48000, freq: 20),
            "kShelf48k": .kWeightingShelf(sampleRate: 48000),
            "kShelf44k": .kWeightingShelf(sampleRate: 44100),
            "kHighPass48k": .kWeightingHighPass(sampleRate: 48000),
        ]
        for name in f.strings("names") {
            let biquad = try #require(filters[name])
            let out = input.map { biquad.process(Double($0)) }
            #expect(mismatch(out, f.f64(name), abs: 1e-12, rel: 1e-9) == nil, "\(name)")
        }
        #expect(mismatch(Biquad.butterworthQs(order: 8), f.f64("butterworthQs8"), abs: 1e-15) == nil)
    }

    @Test func monoRingMatchesKotlin() throws {
        let f = try Fixture.load("monoRing")
        let feed = f.f32("feed")
        let ring = MonoRing(size: 1000)
        var latest: [Float] = []
        var decimated: [Float] = []
        var pending: [Int32] = []
        var at = 0
        for c in f.ints("chunks") {
            ring.write(feed, offset: at, count: c)
            at += c
            pending.append(Int32(ring.pending))
            ring.consume(300)
            var l = [Float](repeating: 0, count: 1000)
            ring.copyLatest(&l)
            latest += l
            var d = [Float](repeating: 0, count: 64)
            ring.copyNewestDecimated(&d, count: 512, stride: 8)
            decimated += d
        }
        #expect(pending == f.i32("pending"))
        #expect(latest == f.f32("latest"))
        #expect(decimated == f.f32("decimated"))
    }

    @Test func nes2a03MatchesKotlin() throws {
        let f = try Fixture.load("nes2a03")
        let hz = f.f64("hz")
        for region in Nes2A03.Region.allCases {
            let timers = hz.map { Int32(Nes2A03.timerFor(region, hz: $0)) }
            #expect(timers == f.i32("\(region.rawValue).timerFor"), "\(region) timers")
            #expect(mismatch(timers.map { Nes2A03.frequency(region, timer: Int($0)) },
                             f.f64("\(region.rawValue).frequency"), abs: 0, rel: 1e-15) == nil)
            #expect(mismatch(timers.map { Nes2A03.samplesPerStep(region, timer: Int($0), sampleRate: 48000) },
                             f.f64("\(region.rawValue).samplesPerStep"), abs: 0, rel: 1e-15) == nil)
        }
        #expect(hz.map(Nes2A03.noteName) == f.strings("noteNames"))
        #expect(mismatch(hz.map(Nes2A03.centsOff), f.f64("centsOff"), abs: 1e-9) == nil)
        let phases = f.f32("phases")
        #expect(phases.map(Nes2A03.levelAt) == f.f32("levelAt"))
        #expect(phases.map { Int32(Nes2A03.dacAt($0)) } == f.i32("dacAt"))
        #expect(Nes2A03.waveRms == f.float("waveRms"))
    }

    @Test func paletteMatchesKotlin() throws {
        let f = try Fixture.load("palette")
        for (label, p) in [("dark", Palette.darkPalette), ("light", Palette.lightPalette)] {
            for map in ColorMap.allCases {
                #expect(p.colorMap(map) == f.argb("\(label).\(map.rawValue)"), "\(label) \(map)")
            }
            #expect([p.surfaceHigh, p.surfaceLow, Palette.withAlpha(p.accent, 0.37)] == f.argb("\(label).surfaces"))
        }
    }

    // ---- behaviour the fixtures don't cover ------------------------------------

    @Test func historyRingReadsWhatWasWritten() {
        let ring = HistoryRing(capacity: 1000)
        #expect(ring.capacity == 1024)
        let src = (0..<3000).map(Float.init)
        ring.write(src, offset: 0, count: 3000)
        var dst = [Float](repeating: -1, count: 10)
        #expect(ring.read(end: 3000, into: &dst, count: 10))
        #expect(dst == (2990..<3000).map(Float.init))
        #expect(!ring.read(end: 1000, into: &dst, count: 10), "overwritten")
        #expect(!ring.read(end: 3001, into: &dst, count: 10), "ahead of the writer")
        ring.advanceTo(5000)
        #expect(ring.written == 5000)
        #expect(ring.read(end: 5000, into: &dst, count: 10))
        #expect(dst == [Float](repeating: 0, count: 10))
    }

    @Test func stereoFeedReadsPlanar() {
        let feed = StereoFeed(capacityFrames: 100)
        #expect(feed.capacity == 128)
        let l = (0..<50).map(Float.init)
        let r = l.map { -$0 }
        feed.write(left: l, right: r, count: 50)
        var dst = [Float](repeating: 0, count: 8)
        #expect(feed.readPlanar(from: 10, count: 4, into: &dst))
        #expect(dst == [10, 11, 12, 13, -10, -11, -12, -13])
        #expect(!feed.readPlanar(from: 48, count: 4, into: &dst))
    }

    @Test func scrollAndSpectrogramBuffersWrap() {
        let s = ScrollBuffer(capacity: 4, initial: -1)
        for v: Float in [1, 2, 3, 4, 5] { s.push(v) }
        var out = [Float](repeating: 0, count: 4)
        s.snapshot(&out)
        #expect(out == [2, 3, 4, 5])

        let g = SpectrogramBuffer(rows: 2, columns: 3)
        for c: UInt8 in [1, 2, 3, 4] { g.push([c, c &+ 100]) }
        #expect(g.head == 1)
        #expect(g.filled == 3)
        #expect(Array(g.data) == [4, 104, 2, 102, 3, 103])
    }

    @Test func kotlinRoundingIsHalfUp() {
        #expect(ktRoundToInt(Float(-2.5)) == -2)
        #expect(ktRoundToInt(Float(2.5)) == 3)
        #expect(ktRoundToInt(Float.nan) == 0)
        #expect(ktToInt(Float.infinity) == Int(Int32.max))
        #expect(ktToInt(Float(-3.9)) == -3)
        #expect(nextPowerOfTwo(atLeast: 32768) == 32768)
        #expect(nextPowerOfTwo(atLeast: 1000) == 1024)
    }
}
