import SwiftUI

// Lavender palette ported 1:1 from the Android Material 3 theme
// (android/.../ui/theme/Color.kt). Other 14 palettes get ported later; this is
// the default that ships in the first build.
struct Palette: Sendable {
    let primary: Color
    let onPrimary: Color
    let primaryContainer: Color
    let onPrimaryContainer: Color
    let secondary: Color
    let secondaryContainer: Color
    let tertiary: Color
    let tertiaryContainer: Color
    let onTertiaryContainer: Color
    let surface: Color
    let onSurface: Color
    let surfaceContainerLow: Color
    let surfaceContainer: Color
    let surfaceContainerHigh: Color
    let outline: Color
    let outlineVariant: Color
    let error: Color
    let success: Color

    static let lavenderLight = Palette(
        primary: Color(hex: 0x6A4FA3),
        onPrimary: .white,
        primaryContainer: Color(hex: 0xEBDDFF),
        onPrimaryContainer: Color(hex: 0x250059),
        secondary: Color(hex: 0x635B70),
        secondaryContainer: Color(hex: 0xE9DEF8),
        tertiary: Color(hex: 0x98455F),
        tertiaryContainer: Color(hex: 0xFFD9E2),
        onTertiaryContainer: Color(hex: 0x3E0721),
        surface: Color(hex: 0xFDF7FF),
        onSurface: Color(hex: 0x1C1B1F),
        surfaceContainerLow: Color(hex: 0xF7F1FB),
        surfaceContainer: Color(hex: 0xF2EBF6),
        surfaceContainerHigh: Color(hex: 0xECE5F0),
        outline: Color(hex: 0x7A757F),
        outlineVariant: Color(hex: 0xCBC4CF),
        error: Color(hex: 0xBA1A1A),
        success: Color(hex: 0x3F6A3F)
    )

    static let lavenderDark = Palette(
        primary: Color(hex: 0xD4BBFF),
        onPrimary: Color(hex: 0x3B1C71),
        primaryContainer: Color(hex: 0x523689),
        onPrimaryContainer: Color(hex: 0xEBDDFF),
        secondary: Color(hex: 0xCFC0E8),
        secondaryContainer: Color(hex: 0x4A4458),
        tertiary: Color(hex: 0xFFB1C8),
        tertiaryContainer: Color(hex: 0x7A2E48),
        onTertiaryContainer: Color(hex: 0xFFD9E2),
        surface: Color(hex: 0x141218),
        onSurface: Color(hex: 0xE6E0E9),
        surfaceContainerLow: Color(hex: 0x1C1B20),
        surfaceContainer: Color(hex: 0x211F25),
        surfaceContainerHigh: Color(hex: 0x2B292F),
        outline: Color(hex: 0x948F99),
        outlineVariant: Color(hex: 0x49454F),
        error: Color(hex: 0xFFB4AB),
        success: Color(hex: 0xA6D388)
    )
}

// Environment plumbing so any view can read `@Environment(\.palette)`.
private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette.lavenderLight
}
extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
