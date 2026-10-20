/// The Nebula 3D design tokens, as plain 0xAARRGGBB numbers — the same values as
/// Palette.kt, which the Android and Windows builds share, and as
/// `public/css/style.css` on n3d-store.com.
///
/// On iPhone the app's chrome is the system's own Liquid Glass and follows the
/// system colours; these are what the *graphs* are drawn in, so a spectrum or a
/// spectrogram is the same colours on all three products. A second copy of these
/// numbers is exactly how products stop matching, so the fixture test compares
/// the colour maps with Palette.kt's, entry by entry.
public struct Palette: Sendable {
    public let isDark: Bool
    public let bg: UInt32
    public let bgDeep: UInt32
    public let dark: UInt32
    public let light: UInt32
    public let line: UInt32
    public let text: UInt32
    public let textDim: UInt32
    public let textFaint: UInt32
    public let accent: UInt32
    public let accent2: UInt32
    /// Every filled control — and the graphs' deepest violet — is this ONE flat
    /// violet, never a gradient: the sites' `--accent-fill`. The graphs keep their
    /// colour fades and use it as their deepest violet.
    public let accentFill: UInt32
    public let good: UInt32
    public let warn: UInt32
    public let bad: UInt32

    /// Top-left end of the raised-surface gradient: 88 % bg, 12 % light.
    public var surfaceHigh: UInt32 { Palette.mix(bg, light, 0.12) }
    /// Bottom-right end. Without this a large card reads as a flat slab.
    public var surfaceLow: UInt32 { Palette.mix(bg, dark, 0.12) }

    private let luts: [ColorMap: [UInt32]]

    init(
        isDark: Bool, bg: UInt32, bgDeep: UInt32, dark: UInt32, light: UInt32, line: UInt32,
        text: UInt32, textDim: UInt32, textFaint: UInt32, accent: UInt32, accent2: UInt32,
        accentFill: UInt32, good: UInt32, warn: UInt32, bad: UInt32
    ) {
        self.isDark = isDark
        self.bg = bg
        self.bgDeep = bgDeep
        self.dark = dark
        self.light = light
        self.line = line
        self.text = text
        self.textDim = textDim
        self.textFaint = textFaint
        self.accent = accent
        self.accent2 = accent2
        self.accentFill = accentFill
        self.good = good
        self.warn = warn
        self.bad = bad
        luts = [
            // Deliberately starts at the well colour, so quiet passages read as an
            // empty well rather than as a black rectangle pasted into the UI.
            .nebula: Palette.ramp([bgDeep, 0x2A1B4D, 0x6A45FF, 0x9B7CFF, 0x34E3FF, 0xEAF6FF]),
            .magma: Palette.ramp([bgDeep, 0x1C1044, 0x711F81, 0xB63679, 0xF1605D, 0xFEC287, 0xFCFDBF]),
            .viridis: Palette.ramp([bgDeep, 0x440154, 0x3B528B, 0x21918C, 0x5EC962, 0xFDE725]),
            .ice: Palette.ramp([bgDeep, 0x06172B, 0x0B4F8A, 0x1BA9D8, 0x8BE9FD, 0xFFFFFF]),
            .mono: Palette.ramp([bgDeep, isDark ? 0xFFFFFF : 0x000000]),
        ]
    }

    /// The 256-entry colour map, 0xAARRGGBB.
    public func colorMap(_ map: ColorMap) -> [UInt32] { luts[map]! }

    public static let darkPalette = Palette(
        isDark: true,
        bg: 0xFF22252C, bgDeep: 0xFF1B1E24, dark: 0xFF171920, light: 0xFF2D313A, line: 0xFF33373F,
        text: 0xFFEEF1F7, textDim: 0xFFA3ABBD, textFaint: 0xFF737B8C,
        accent: 0xFF9B7CFF, accent2: 0xFF34E3FF, accentFill: 0xFF6A45FF,
        good: 0xFF4ADE80, warn: 0xFFF5C451, bad: 0xFFFF6B6B
    )

    public static let lightPalette = Palette(
        isDark: false,
        bg: 0xFFE7EAF1, bgDeep: 0xFFDDE1EA, dark: 0xFFC1C6D1, light: 0xFFFFFFFF, line: 0xFFD2D7E1,
        text: 0xFF1C2029, textDim: 0xFF545C6C, textFaint: 0xFF7B8394,
        accent: 0xFF5F43E8, accent2: 0xFF0A9FBD, accentFill: 0xFF5734DB,
        good: 0xFF17914D, warn: 0xFFA06F04, bad: 0xFFCF3535
    )

    public static func of(dark: Bool) -> Palette { dark ? darkPalette : lightPalette }

    public static func alpha(_ c: UInt32) -> Int { Int(c >> 24 & 0xFF) }
    public static func red(_ c: UInt32) -> Int { Int(c >> 16 & 0xFF) }
    public static func green(_ c: UInt32) -> Int { Int(c >> 8 & 0xFF) }
    public static func blue(_ c: UInt32) -> Int { Int(c & 0xFF) }

    public static func argb(_ a: Int, _ r: Int, _ g: Int, _ b: Int) -> UInt32 {
        UInt32(a & 0xFF) << 24 | UInt32(r & 0xFF) << 16 | UInt32(g & 0xFF) << 8 | UInt32(b & 0xFF)
    }

    public static func mix(_ a: UInt32, _ b: UInt32, _ t: Float) -> UInt32 {
        let inv = 1 - t
        return argb(
            ktToInt(Float(alpha(a)) * inv + Float(alpha(b)) * t),
            ktToInt(Float(red(a)) * inv + Float(red(b)) * t),
            ktToInt(Float(green(a)) * inv + Float(green(b)) * t),
            ktToInt(Float(blue(a)) * inv + Float(blue(b)) * t)
        )
    }

    public static func withAlpha(_ color: UInt32, _ alpha: Float) -> UInt32 {
        argb(ktToInt(coerceIn(alpha, 0, 1) * 255), red(color), green(color), blue(color))
    }

    /// Builds a 256-entry lookup by walking evenly through the given anchor
    /// colours. A LUT rather than a per-pixel interpolation because the
    /// spectrogram blits 256 rows every frame.
    private static func ramp(_ anchorsRgb: [UInt32]) -> [UInt32] {
        let anchors = anchorsRgb.map { $0 >> 24 == 0 ? $0 | 0xFF000000 : $0 }
        let segments = anchors.count - 1
        return (0..<256).map { i in
            let pos = Float(i) / 255 * Float(segments)
            let seg = min(ktToInt(pos), segments - 1)
            return mix(anchors[seg], anchors[seg + 1], pos - Float(seg))
        }
    }
}
