import Foundation

/// The Ricoh 2A03's triangle channel, as arithmetic.
///
/// Everything the analyser needs to know about the chip lives here, and it is all
/// exact rather than approximate — the triangle is not "a triangle wave", it is a
/// 32-entry sequence clocked by a divider off the CPU clock, and the whole point
/// of the display it feeds is to show that difference.
///
/// Three facts drive the rest of the code:
///
///  1. **It is 4 bits.** The sequencer walks a fixed 32-step table of DAC levels
///     15…0, 0…15. There are only sixteen output values, and both extremes are
///     held for two steps, so the waveform is a staircase with flats at the top
///     and bottom — never a straight ramp.
///  2. **It is clocked by the CPU, not the APU.** The pulse channels' timers are
///     clocked every *other* CPU cycle; the triangle's is clocked every cycle.
///     That is why its period is 32·(t+1) CPU cycles rather than 16·(t+1)·2, and
///     why the same timer value plays an octave lower on the pulse channels.
///  3. **It has no volume.** The linear counter gates it on or off and that is
///     all, so amplitude carries no information — which is what makes it safe for
///     the display to normalise the captured level away entirely.
public enum Nes2A03 {

    /// The 32 DAC levels the sequencer walks, in order.
    public static let sequence: [Int] = [
        15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    ]

    public static let steps = 32
    public static let levels = 16

    /// `sequence` centred and scaled to −1…+1.
    ///
    /// Level 0 maps to −1 and level 15 to +1, so the mid-point sits at 7.5 — a
    /// value the DAC can never actually output. That asymmetry is real: the chip
    /// has no zero, only two levels either side of it.
    public static let wave: [Float] = sequence.map { (Float($0) - 7.5) / 7.5 }

    /// RMS of `wave`, used to fit a captured level to the chip's own scale.
    public static let waveRms: Float = {
        var s = 0.0
        for w in wave { s += Double(w * w) }
        return Float((s / Double(steps)).squareRoot())
    }()

    /// CPU clocks. The APU divides these, so a region change retunes every
    /// channel — the same $400A/$400B pair is about 7.6 % flat on PAL.
    public enum Region: String, CaseIterable, Codable, Sendable {
        case ntsc = "NTSC"
        case pal = "PAL"
        case dendy = "DENDY"

        public var label: String {
            switch self {
            case .ntsc: "NTSC"
            case .pal: "PAL"
            case .dendy: "Dendy"
            }
        }

        public var cpuHz: Double {
            switch self {
            case .ntsc: 21477272.0 / 12.0
            case .pal: 26601712.0 / 16.0
            case .dendy: 26601712.0 / 15.0
            }
        }
    }

    /// The 11-bit timer's range.
    public static let timerMax = 2047

    /// Emulators mute the channel below this timer value.
    ///
    /// At t < 2 the sequencer advances faster than the DAC can be heard to move
    /// and real hardware emits a mostly-inaudible click, so nearly every emulator
    /// silences it instead. A "note" detected down there is not a note.
    public static let timerMinAudible = 2

    /// f = CPU / (32·(t+1)).
    public static func frequency(_ region: Region, timer: Int) -> Double {
        region.cpuHz / (Double(steps) * (Double(timer) + 1.0))
    }

    /// The timer the chip would need to play `hz`, clamped to the 11-bit register.
    public static func timerFor(_ region: Region, hz: Double) -> Int {
        if hz <= 0.0 { return timerMax }
        let t = ktRoundToLong(region.cpuHz / (Double(steps) * hz)) - 1
        return Int(coerceIn(t, 0, Int64(timerMax)))
    }

    /// Lowest and highest notes the channel can play in this region.
    public static func lowestHz(_ region: Region) -> Double { frequency(region, timer: timerMax) }
    public static func highestHz(_ region: Region) -> Double { frequency(region, timer: timerMinAudible) }

    /// The chip's output at a phase in 0…1, where 0 is the first step of the
    /// sequence — the top of the wave, level 15.
    public static func levelAt(_ phase: Float) -> Float {
        var u = phase.truncatingRemainder(dividingBy: 1)
        if u < 0 { u += 1 }
        let k = coerceIn(ktToInt(u * Float(steps)), 0, steps - 1)
        return wave[k]
    }

    /// The raw 0…15 DAC level at a phase in 0…1.
    public static func dacAt(_ phase: Float) -> Int {
        var u = phase.truncatingRemainder(dividingBy: 1)
        if u < 0 { u += 1 }
        return sequence[coerceIn(ktToInt(u * Float(steps)), 0, steps - 1)]
    }

    /// The −1…+1 value of DAC level `level` (0…15).
    public static func valueOfLevel(_ level: Int) -> Float { (Float(level) - 7.5) / 7.5 }

    /// How many captured samples fall inside one DAC step.
    ///
    /// This is the number that decides whether the staircase is visible at all.
    /// Each step lasts (t+1) CPU cycles, so at 48 kHz an A1 (t = 1016) holds each
    /// level for about 27 samples and every corner survives resampling, while an
    /// A4 holds it for 3 and the whole thing arrives as a smooth triangle. It is
    /// the reason this display is useful for bass and misleading for melody.
    public static func samplesPerStep(_ region: Region, timer: Int, sampleRate: Int) -> Double {
        Double(sampleRate) * (Double(timer) + 1.0) / region.cpuHz
    }

    // ---- note naming -------------------------------------------------------

    private static let noteNames = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

    /// Equal-tempered note number for `hz`, fractional, A4 = 440 = 69.
    public static func midiOf(_ hz: Double) -> Double { 69.0 + 12.0 * log(hz / 440.0) / log(2.0) }

    /// e.g. "A1" for 55 Hz.
    public static func noteName(_ hz: Double) -> String {
        if hz <= 0.0 { return "—" }
        let midi = ktRoundToInt(midiOf(hz))
        let name = noteNames[((midi % 12) + 12) % 12]
        return "\(name)\(midi / 12 - 1)"
    }

    /// Signed cents from `hz` to the nearest equal-tempered note.
    public static func centsOff(_ hz: Double) -> Double {
        if hz <= 0.0 { return 0.0 }
        let midi = midiOf(hz)
        return (midi - Double(ktRoundToInt(midi))) * 100.0
    }
}
