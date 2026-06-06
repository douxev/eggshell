import SwiftUI

// Curated editor/desktop palettes ported from android/.../ui/theme/Palettes.kt,
// mapped onto the app's 18-token `Palette`. Android's Material schemes don't
// carry a `success` token, so we use a fixed readable green per luminance tier.

private let lightSuccess: UInt32 = 0x3F6A3F
private let darkSuccess: UInt32 = 0xA6D388

extension Palette {
    // ---------- Catppuccin Latte (light) ----------
    static let catppuccinLatte = Palette(
        primary: Color(hex: 0x8839EF), onPrimary: Color(hex: 0xFFFFFF),
        primaryContainer: Color(hex: 0xE8D9FF), onPrimaryContainer: Color(hex: 0x3F1B7B),
        secondary: Color(hex: 0x209FB5), secondaryContainer: Color(hex: 0xC9EBF1),
        tertiary: Color(hex: 0xEA76CB), tertiaryContainer: Color(hex: 0xFCDDF1), onTertiaryContainer: Color(hex: 0x421036),
        surface: Color(hex: 0xEFF1F5), onSurface: Color(hex: 0x4C4F69),
        surfaceContainerLow: Color(hex: 0xE6E9EF), surfaceContainer: Color(hex: 0xDCE0E8), surfaceContainerHigh: Color(hex: 0xCCD0DA),
        outline: Color(hex: 0x7C7F93), outlineVariant: Color(hex: 0xBCC0CC),
        error: Color(hex: 0xD20F39), success: Color(hex: lightSuccess))

    // ---------- Catppuccin Mocha (dark) ----------
    static let catppuccinMocha = Palette(
        primary: Color(hex: 0xCBA6F7), onPrimary: Color(hex: 0x1E1E2E),
        primaryContainer: Color(hex: 0x45475A), onPrimaryContainer: Color(hex: 0xCBA6F7),
        secondary: Color(hex: 0x89B4FA), secondaryContainer: Color(hex: 0x313244),
        tertiary: Color(hex: 0xF5C2E7), tertiaryContainer: Color(hex: 0x45475A), onTertiaryContainer: Color(hex: 0xF5C2E7),
        surface: Color(hex: 0x1E1E2E), onSurface: Color(hex: 0xCDD6F4),
        surfaceContainerLow: Color(hex: 0x181825), surfaceContainer: Color(hex: 0x1E1E2E), surfaceContainerHigh: Color(hex: 0x313244),
        outline: Color(hex: 0x6C7086), outlineVariant: Color(hex: 0x45475A),
        error: Color(hex: 0xF38BA8), success: Color(hex: darkSuccess))

    // ---------- Gruvbox Light ----------
    static let gruvboxLight = Palette(
        primary: Color(hex: 0xAF3A03), onPrimary: Color(hex: 0xFBF1C7),
        primaryContainer: Color(hex: 0xFFD9A6), onPrimaryContainer: Color(hex: 0x3C1402),
        secondary: Color(hex: 0xB57614), secondaryContainer: Color(hex: 0xF9EFCB),
        tertiary: Color(hex: 0x79740E), tertiaryContainer: Color(hex: 0xE1E0A6), onTertiaryContainer: Color(hex: 0x252608),
        surface: Color(hex: 0xFBF1C7), onSurface: Color(hex: 0x3C3836),
        surfaceContainerLow: Color(hex: 0xF3E7BD), surfaceContainer: Color(hex: 0xEBDBB2), surfaceContainerHigh: Color(hex: 0xD5C4A1),
        outline: Color(hex: 0x7C6F64), outlineVariant: Color(hex: 0xBDAE93),
        error: Color(hex: 0x9D0006), success: Color(hex: lightSuccess))

    // ---------- Gruvbox Dark ----------
    static let gruvboxDark = Palette(
        primary: Color(hex: 0xFE8019), onPrimary: Color(hex: 0x282828),
        primaryContainer: Color(hex: 0x504945), onPrimaryContainer: Color(hex: 0xFABD2F),
        secondary: Color(hex: 0xFABD2F), secondaryContainer: Color(hex: 0x3C3836),
        tertiary: Color(hex: 0xB8BB26), tertiaryContainer: Color(hex: 0x504945), onTertiaryContainer: Color(hex: 0xB8BB26),
        surface: Color(hex: 0x282828), onSurface: Color(hex: 0xEBDBB2),
        surfaceContainerLow: Color(hex: 0x282828), surfaceContainer: Color(hex: 0x32302F), surfaceContainerHigh: Color(hex: 0x3C3836),
        outline: Color(hex: 0x665C54), outlineVariant: Color(hex: 0x3C3836),
        error: Color(hex: 0xFB4934), success: Color(hex: darkSuccess))

    // ---------- Tokyo Night (dark) ----------
    static let tokyoNight = Palette(
        primary: Color(hex: 0x7AA2F7), onPrimary: Color(hex: 0x1A1B26),
        primaryContainer: Color(hex: 0x3D59A1), onPrimaryContainer: Color(hex: 0xC0CAF5),
        secondary: Color(hex: 0xBB9AF7), secondaryContainer: Color(hex: 0x414868),
        tertiary: Color(hex: 0x7DCFFF), tertiaryContainer: Color(hex: 0x414868), onTertiaryContainer: Color(hex: 0x7DCFFF),
        surface: Color(hex: 0x1A1B26), onSurface: Color(hex: 0xC0CAF5),
        surfaceContainerLow: Color(hex: 0x1A1B26), surfaceContainer: Color(hex: 0x1F2335), surfaceContainerHigh: Color(hex: 0x24283B),
        outline: Color(hex: 0x565F89), outlineVariant: Color(hex: 0x24283B),
        error: Color(hex: 0xF7768E), success: Color(hex: darkSuccess))

    // ---------- Dracula (dark) ----------
    static let dracula = Palette(
        primary: Color(hex: 0xBD93F9), onPrimary: Color(hex: 0x282A36),
        primaryContainer: Color(hex: 0x44475A), onPrimaryContainer: Color(hex: 0xBD93F9),
        secondary: Color(hex: 0x8BE9FD), secondaryContainer: Color(hex: 0x44475A),
        tertiary: Color(hex: 0xFF79C6), tertiaryContainer: Color(hex: 0x44475A), onTertiaryContainer: Color(hex: 0xFF79C6),
        surface: Color(hex: 0x282A36), onSurface: Color(hex: 0xF8F8F2),
        surfaceContainerLow: Color(hex: 0x282A36), surfaceContainer: Color(hex: 0x333546), surfaceContainerHigh: Color(hex: 0x44475A),
        outline: Color(hex: 0x6272A4), outlineVariant: Color(hex: 0x44475A),
        error: Color(hex: 0xFF5555), success: Color(hex: darkSuccess))

    // ---------- Nord (dark) ----------
    static let nord = Palette(
        primary: Color(hex: 0x88C0D0), onPrimary: Color(hex: 0x2E3440),
        primaryContainer: Color(hex: 0x434C5E), onPrimaryContainer: Color(hex: 0xECEFF4),
        secondary: Color(hex: 0x81A1C1), secondaryContainer: Color(hex: 0x3B4252),
        tertiary: Color(hex: 0xB48EAD), tertiaryContainer: Color(hex: 0x4C566A), onTertiaryContainer: Color(hex: 0xB48EAD),
        surface: Color(hex: 0x2E3440), onSurface: Color(hex: 0xECEFF4),
        surfaceContainerLow: Color(hex: 0x2E3440), surfaceContainer: Color(hex: 0x3B4252), surfaceContainerHigh: Color(hex: 0x434C5E),
        outline: Color(hex: 0x616E88), outlineVariant: Color(hex: 0x434C5E),
        error: Color(hex: 0xBF616A), success: Color(hex: darkSuccess))

    // ---------- Rosé Pine (dark) ----------
    static let rosePine = Palette(
        primary: Color(hex: 0xEBBCBA), onPrimary: Color(hex: 0x191724),
        primaryContainer: Color(hex: 0x403A4D), onPrimaryContainer: Color(hex: 0xEBBCBA),
        secondary: Color(hex: 0xC4A7E7), secondaryContainer: Color(hex: 0x26233A),
        tertiary: Color(hex: 0xF6C177), tertiaryContainer: Color(hex: 0x403A4D), onTertiaryContainer: Color(hex: 0xF6C177),
        surface: Color(hex: 0x191724), onSurface: Color(hex: 0xE0DEF4),
        surfaceContainerLow: Color(hex: 0x191724), surfaceContainer: Color(hex: 0x1F1D2E), surfaceContainerHigh: Color(hex: 0x26233A),
        outline: Color(hex: 0x6E6A86), outlineVariant: Color(hex: 0x26233A),
        error: Color(hex: 0xEB6F92), success: Color(hex: darkSuccess))

    // ---------- Solarized Light ----------
    static let solarizedLight = Palette(
        primary: Color(hex: 0x268BD2), onPrimary: Color(hex: 0xFDF6E3),
        primaryContainer: Color(hex: 0xCEE3F4), onPrimaryContainer: Color(hex: 0x002B36),
        secondary: Color(hex: 0x859900), secondaryContainer: Color(hex: 0xE1E8C1),
        tertiary: Color(hex: 0xD33682), tertiaryContainer: Color(hex: 0xF7CCE0), onTertiaryContainer: Color(hex: 0x400720),
        surface: Color(hex: 0xFDF6E3), onSurface: Color(hex: 0x586E75),
        surfaceContainerLow: Color(hex: 0xFAF1D9), surfaceContainer: Color(hex: 0xEEE8D5), surfaceContainerHigh: Color(hex: 0xE0DAC5),
        outline: Color(hex: 0x93A1A1), outlineVariant: Color(hex: 0xD3CDB6),
        error: Color(hex: 0xDC322F), success: Color(hex: lightSuccess))

    // ---------- Solarized Dark ----------
    static let solarizedDark = Palette(
        primary: Color(hex: 0x268BD2), onPrimary: Color(hex: 0x002B36),
        primaryContainer: Color(hex: 0x073642), onPrimaryContainer: Color(hex: 0x93A1A1),
        secondary: Color(hex: 0x2AA198), secondaryContainer: Color(hex: 0x073642),
        tertiary: Color(hex: 0xB58900), tertiaryContainer: Color(hex: 0x073642), onTertiaryContainer: Color(hex: 0xB58900),
        surface: Color(hex: 0x002B36), onSurface: Color(hex: 0x93A1A1),
        surfaceContainerLow: Color(hex: 0x002B36), surfaceContainer: Color(hex: 0x053139), surfaceContainerHigh: Color(hex: 0x073642),
        outline: Color(hex: 0x586E75), outlineVariant: Color(hex: 0x073642),
        error: Color(hex: 0xDC322F), success: Color(hex: darkSuccess))

    // ---------- One Dark ----------
    static let oneDark = Palette(
        primary: Color(hex: 0xC678DD), onPrimary: Color(hex: 0x282C34),
        primaryContainer: Color(hex: 0x3E4451), onPrimaryContainer: Color(hex: 0xC678DD),
        secondary: Color(hex: 0x61AFEF), secondaryContainer: Color(hex: 0x3E4451),
        tertiary: Color(hex: 0x98C379), tertiaryContainer: Color(hex: 0x3E4451), onTertiaryContainer: Color(hex: 0x98C379),
        surface: Color(hex: 0x282C34), onSurface: Color(hex: 0xABB2BF),
        surfaceContainerLow: Color(hex: 0x282C34), surfaceContainer: Color(hex: 0x323844), surfaceContainerHigh: Color(hex: 0x3E4451),
        outline: Color(hex: 0x5C6370), outlineVariant: Color(hex: 0x3E4451),
        error: Color(hex: 0xE06C75), success: Color(hex: darkSuccess))

    // ---------- Mayukai Mirage (dark) ----------
    static let mayukaiMirage = Palette(
        primary: Color(hex: 0xA991F1), onPrimary: Color(hex: 0x1F2335),
        primaryContainer: Color(hex: 0x3A3D4A), onPrimaryContainer: Color(hex: 0xE0D8FF),
        secondary: Color(hex: 0xF49EC0), secondaryContainer: Color(hex: 0x3A3D4A),
        tertiary: Color(hex: 0x82AAFF), tertiaryContainer: Color(hex: 0x3A3D4A), onTertiaryContainer: Color(hex: 0x82AAFF),
        surface: Color(hex: 0x1F2335), onSurface: Color(hex: 0xE0E0E0),
        surfaceContainerLow: Color(hex: 0x1F2335), surfaceContainer: Color(hex: 0x252940), surfaceContainerHigh: Color(hex: 0x2C3043),
        outline: Color(hex: 0x5A5E72), outlineVariant: Color(hex: 0x2C3043),
        error: Color(hex: 0xFF6E6E), success: Color(hex: darkSuccess))
}

/// A selectable theme. `light`/`dark` are nil when the theme only ships one
/// variant (the picker then applies it regardless of the system appearance).
struct Theme: Identifiable {
    let id: String
    let label: String
    let light: Palette?
    let dark: Palette?

    /// Resolve the palette to use for the given appearance.
    func palette(dark isDark: Bool) -> Palette {
        if isDark { return dark ?? light ?? .lavenderDark }
        return light ?? dark ?? .lavenderLight
    }
    /// Swatch palette for the picker (prefers the variant matching appearance).
    func swatch(dark isDark: Bool) -> Palette { palette(dark: isDark) }
}

enum Themes {
    /// Registry mirroring the Android theme list. `lavender` is the default and
    /// adapts to the system light/dark scheme.
    static let all: [Theme] = [
        Theme(id: "lavender",   label: "Lavande",      light: .lavenderLight,    dark: .lavenderDark),
        Theme(id: "catppuccin", label: "Catppuccin",   light: .catppuccinLatte,  dark: .catppuccinMocha),
        Theme(id: "gruvbox",    label: "Gruvbox",      light: .gruvboxLight,     dark: .gruvboxDark),
        Theme(id: "solarized",  label: "Solarized",    light: .solarizedLight,   dark: .solarizedDark),
        Theme(id: "tokyonight", label: "Tokyo Night",  light: nil,               dark: .tokyoNight),
        Theme(id: "dracula",    label: "Dracula",      light: nil,               dark: .dracula),
        Theme(id: "nord",       label: "Nord",         light: nil,               dark: .nord),
        Theme(id: "rosepine",   label: "Rosé Pine",    light: nil,               dark: .rosePine),
        Theme(id: "onedark",    label: "One Dark",     light: nil,               dark: .oneDark),
        Theme(id: "mayukai",    label: "Mayukai",      light: nil,               dark: .mayukaiMirage),
    ]

    static func find(_ id: String) -> Theme { all.first { $0.id == id } ?? all[0] }
}

extension Palette {
    /// Resolve the active palette from a persisted theme id + appearance.
    static func resolve(themeId: String, dark: Bool) -> Palette {
        Themes.find(themeId).palette(dark: dark)
    }
}
