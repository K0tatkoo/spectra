import Foundation
import Testing
import SpectraCore
@testable import SpectraStems

extension StemsTests {
    /// The whole Stems page pipeline as the engine drives it: 48 kHz capture blocks
    /// in, resampled, separated by the real model, and held still lane by lane.
    @Suite(.enabled(if: StemsFiles.stemModel != nil, Comment(rawValue: StemsFiles.noStemModel)))
    struct ScopeRunnerTests {

        @Test func aSyntheticBandComesOutAsHeldStillStems() throws {
            let model = StemsFiles.stemModel!
            let runner = ScopeRunner(rate: 48_000, modelURL: { model })
            let player = Player(runner, wants: .init(stems: true, hold: true, nes: false))
            var allHeld = Settings()
            allHeld.holdVocals = true
            allHeld.holdOther = true
            allHeld.holdBass = true
            allHeld.holdDrums = true

            let frames = player.play(allHeld, 4.0)
            try #require(!frames.isEmpty, "the runner published nothing")
            let info = try #require(frames.last!.stemsInfo)
            print("stems: \(info.state) load \(info.load) · \(info.msPerHop) ms/hop · skips \(info.skips)")
            #expect(info.state == .running)

            // Judge the last second, once the model and the locks have settled.
            let settled = Array(frames.suffix(40))

            let bass = lane(settled, "Bass")
            let bassLocked = bass.filter { $0.mode == .pitch && $0.note == "A1" }.count
            print("bass: \(bassLocked)/\(bass.count) frames locked to A1 · last \(bass.last!.note) \(bass.last!.hz) Hz")
            #expect(Double(bassLocked) > Double(bass.count) * 0.8)

            let vocals = lane(settled, "Vocals")
            let vocalsLocked = vocals.filter { $0.mode == .pitch && $0.note == "E4" }.count
            print("vocals: \(vocalsLocked)/\(vocals.count) frames locked to E4 · last \(vocals.last!.note) \(vocals.last!.hz) Hz")
            #expect(Double(vocalsLocked) > Double(vocals.count) * 0.8)

            let drums = lane(settled, "Drums")
            let drumHits = drums.filter { $0.mode == .hit }.count
            print("drums: \(drumHits)/\(drums.count) frames frozen on a hit")
            #expect(Double(drumHits) > Double(drums.count) * 0.6)

            // And the mix, for the Waveform page's "hold still": the loudest
            // periodic thing in it is the bass.
            let hold = settled.compactMap(\.hold)
            print("hold: \(hold.last!.mode) \(hold.last!.note) \(hold.last!.hz) Hz")
            #expect(hold.filter { $0.mode == .pitch }.count > hold.count / 2)

            // The defaults hold only the bass. Every other lane scrolls: never a
            // note, never a frozen hit — but still showing its stem, not silence.
            let defaults = Array(player.play(Settings(), 1.5).suffix(40))
            let bassAlone = lane(defaults, "Bass").filter { $0.mode == .pitch && $0.note == "A1" }.count
            print("defaults: bass \(bassAlone)/\(defaults.count) locked to A1")
            #expect(Double(bassAlone) > Double(defaults.count) * 0.8)
            for name in ["Vocals", "Other", "Drums"] {
                let traces = lane(defaults, name)
                #expect(traces.allSatisfy { $0.mode != .pitch && $0.mode != .hit }, "\(name) is held still with hold off")
                #expect(traces.allSatisfy { !$0.stale }, "\(name) shows a held-over picture with hold off")
            }
            let vocalsFree = lane(defaults, "Vocals").filter { $0.mode == .free }.count
            print("defaults: vocals free-running in \(vocalsFree)/\(defaults.count) frames")
            #expect(Double(vocalsFree) > Double(defaults.count) * 0.8)

            // Switched back on, a lane locks again rather than keeping the free picture.
            var vocalsHeld = Settings()
            vocalsHeld.holdVocals = true
            let back = Array(player.play(vocalsHeld, 1.5).suffix(40))
            let relocked = lane(back, "Vocals").filter { $0.mode == .pitch && $0.note == "E4" }.count
            print("vocals held again: \(relocked)/\(back.count) locked to E4")
            #expect(Double(relocked) > Double(back.count) * 0.8)
            runner.release()
        }

        /// The Stems page with a synth splitter behind the real stem model. The
        /// splitter's network is a stand-in that calls everything in vocals synth —
        /// so the vibrato tone the model puts in vocals must move to the synth lane,
        /// held still on its note, and leave the vocals lane empty.
        @Test func theSynthLaneShowsWhatTheSplitterTakesOut() throws {
            let model = StemsFiles.stemModel!
            let layout = try SplitLayout(sampleRate: StemSeparator.sampleRate, nFft: 2048, hop: 512,
                                         bandEdges: (0..<129).map { $0 * 8 } + [1025], inputs: [.vocals, .other])
            let runner = ScopeRunner(rate: 48_000, modelURL: { model },
                                     synthModel: { SplitModel(layout: layout, net: VocalsAreSynth(bands: layout.bands)) })
            let player = Player(runner, wants: .init(stems: true, hold: false, nes: false))
            var held = Settings()
            held.holdVocals = true
            held.holdSynth = true
            let frames = Array(player.play(held, 4.0).suffix(40))
            let names = frames.last!.stems.map(\.name)
            print("lanes: \(names) · synth \(String(describing: frames.last!.stemsInfo?.synth?.state))")
            #expect(names == ["Vocals", "Synth", "Other", "Bass", "Drums"])
            #expect(frames.last!.stemsInfo?.synth?.state == .running)

            let synth = lane(frames, "Synth")
            let synthLocked = synth.filter { $0.mode == .pitch && $0.note == "E4" }.count
            print("synth: \(synthLocked)/\(synth.count) frames locked to E4 · last \(synth.last!.note) \(synth.last!.hz) Hz")
            #expect(Double(synthLocked) > Double(synth.count) * 0.8)
            let vocals = lane(frames, "Vocals")
            print("vocals after the split: \(Dictionary(grouping: vocals, by: { "\($0.mode)" }).mapValues(\.count))")
            #expect(vocals.allSatisfy { $0.mode != .pitch }, "vocals still shows a note")
            // Every lane keeps its own colour slot, whichever lanes are showing.
            #expect(frames.last!.stems[1].slot == Stem.synth.ordinal)

            // Switched off, the page is the model's four stems again.
            var off = held
            off.stemSynth = false
            let last = player.play(off, 1.0).last!
            #expect(last.stems.map(\.name) == ["Vocals", "Other", "Bass", "Drums"])
            #expect(last.stemsInfo?.synth == nil)
            runner.release()
        }
    }

    /// No model needed: the Waveform page's two modes, and switching between them.
    @Suite struct ScopeRunnerModesTests {

        @Test func switchingFrom2A03ToHoldStillKeepsDrawing() throws {
            let rate = 48_000
            let block = 1024
            let runner = ScopeRunner(rate: rate, modelURL: { throw StemsError("stems are not wanted here") })
            let settings = Settings()
            let blockMs = Float(block) * 1000 / Float(rate)
            var buf = [Float](repeating: 0, count: block)
            var t = 0
            func play(_ wants: ScopeRunner.Wants, _ seconds: Double) {
                let until = t + Int(seconds * Double(rate))
                while t < until {
                    for i in 0..<block { buf[i] = Float(0.4 * TestSignals.nesTriangle(55.0, Double(t + i) / Double(rate))) }
                    runner.process(buf, nil, frames: block, wants: wants, settings: settings, blockMs: blockMs)
                    t += block
                }
            }
            play(.init(stems: false, hold: false, nes: true), 1.5)
            let nes = try #require(runner.latestNes, "2A03 mode found no triangle")
            #expect(nes.note == "A1")

            play(.init(stems: false, hold: true, nes: false), 1.0)
            let hold = try #require(runner.latest?.hold, "hold still drew nothing after the switch")
            #expect(hold.mode == .pitch)
            #expect(hold.note == "A1")
        }

        @Test func aMissingStemModelIsShownNotRetried() throws {
            var asked = 0
            let runner = ScopeRunner(rate: 48_000, modelURL: {
                asked += 1
                throw StemsError("no stemgen-rt-hop128.onnx in the bundle")
            })
            let block = [Float](repeating: 0, count: 1024)
            for _ in 0..<10 {
                runner.process(block, block, frames: 1024, wants: .init(stems: true, hold: false, nes: false),
                               settings: Settings(), blockMs: 21.3)
            }
            let info = try #require(runner.latest?.stemsInfo)
            #expect(info.state == .failed)
            #expect(info.message == "The stem model is missing from this build: no stemgen-rt-hop128.onnx in the bundle")
            #expect(asked == 1)
            #expect(ScopeRunner.lanesFor(synth: true) == [.vocals, .synth, .other, .bass, .drums])
            #expect(Settings().holdingStill(.vocals, true).holdsStill(.vocals))
            #expect(!Settings().holdingStill(.bass, false).holdsStill(.bass))
        }
    }
}

/// Plays the test band into a runner in 48 kHz blocks at roughly real time —
/// the worker has to keep up with a capture, not with a loop that hands it
/// four seconds at once — and collects what it publishes.
private final class Player {
    private let runner: ScopeRunner
    private let wants: ScopeRunner.Wants
    private let rate = 48_000
    private let block = 1024
    private var t = 0
    private var left: [Float]

    init(_ runner: ScopeRunner, wants: ScopeRunner.Wants) {
        self.runner = runner
        self.wants = wants
        left = [Float](repeating: 0, count: block)
    }

    func play(_ settings: Settings, _ seconds: Double) -> [ScopeFrame] {
        var frames: [ScopeFrame] = []
        let blockMs = Float(block) * 1000 / Float(rate)
        let until = t + Int(seconds * Double(rate))
        while t < until {
            for i in 0..<block {
                let s = Double(t + i) / Double(rate)
                left[i] = Float(0.3 * TestSignals.nesTriangle(55.0, s) + 0.2 * TestSignals.vibrato(330.0, s) + 0.4 * TestSignals.kick(s))
            }
            runner.process(left, left, frames: block, wants: wants, settings: settings, blockMs: blockMs)
            t += block
            if let f = runner.latest { frames.append(f) }
            Thread.sleep(forTimeInterval: Double(Int(blockMs)) / 1000)
        }
        return frames
    }
}

private func lane(_ frames: [ScopeFrame], _ name: String) -> [ScopeTrace] {
    frames.map { f in f.stems.first { $0.name == name }!.trace }
}
