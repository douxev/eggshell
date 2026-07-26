import SwiftUI

// Lavender palette ported 1:1 from the Android Material 3 theme
// (android/.../ui/theme/Color.kt + Tokens.kt). Every colour a screen uses is a
// token: the app ships 14 palettes and none of them may be broken by a
// hard-coded hex.
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

    // Tokens the refonte needs that the first port did not carry. Every screen
    // was approximating `onSurfaceVariant` with `onSurface.opacity(0.6)` in 142
    // places; the eyebrow labels, the punctuality pills, the sheet scrim and
    // the Évolution family tile all need real roles.
    let onSurfaceVariant: Color
    let surfaceContainerLowest: Color
    let surfaceContainerHighest: Color
    let onSecondaryContainer: Color
    let errorContainer: Color
    let onErrorContainer: Color
    let successContainer: Color
    let onSuccessContainer: Color
    let onSecondary: Color
    let onTertiary: Color
    let onError: Color
    let scrim: Color

    /// Grid lines, in every chart of the app (handoff §5.1).
    var chartGrid: Color { outlineVariant }

    /// Sage — the Évolution family tile. Raw `successContainer` is too
    /// saturated next to the lavender and the rose, so it is desaturated
    /// towards the neutral of the same lightness (handoff §3.2).
    var evolutionContainer: Color { successContainer.mix(with: surfaceContainerHighest, by: 0.45) }
    var onEvolutionContainer: Color { onSuccessContainer }

    /// The new tokens default to the value the screens were already
    /// approximating, so a palette that has not been revisited renders exactly
    /// as it does today. Palettes override them one at a time.
    init(
        primary: Color,
        onPrimary: Color,
        primaryContainer: Color,
        onPrimaryContainer: Color,
        secondary: Color,
        secondaryContainer: Color,
        tertiary: Color,
        tertiaryContainer: Color,
        onTertiaryContainer: Color,
        surface: Color,
        onSurface: Color,
        surfaceContainerLow: Color,
        surfaceContainer: Color,
        surfaceContainerHigh: Color,
        outline: Color,
        outlineVariant: Color,
        error: Color,
        success: Color,
        onSurfaceVariant: Color? = nil,
        surfaceContainerLowest: Color? = nil,
        surfaceContainerHighest: Color? = nil,
        onSecondaryContainer: Color? = nil,
        errorContainer: Color? = nil,
        onErrorContainer: Color? = nil,
        successContainer: Color? = nil,
        onSuccessContainer: Color? = nil,
        onSecondary: Color? = nil,
        onTertiary: Color? = nil,
        onError: Color? = nil,
        scrim: Color? = nil
    ) {
        self.primary = primary
        self.onPrimary = onPrimary
        self.primaryContainer = primaryContainer
        self.onPrimaryContainer = onPrimaryContainer
        self.secondary = secondary
        self.secondaryContainer = secondaryContainer
        self.tertiary = tertiary
        self.tertiaryContainer = tertiaryContainer
        self.onTertiaryContainer = onTertiaryContainer
        self.surface = surface
        self.onSurface = onSurface
        self.surfaceContainerLow = surfaceContainerLow
        self.surfaceContainer = surfaceContainer
        self.surfaceContainerHigh = surfaceContainerHigh
        self.outline = outline
        self.outlineVariant = outlineVariant
        self.error = error
        self.success = success

        self.onSurfaceVariant = onSurfaceVariant ?? onSurface.opacity(0.6)
        self.surfaceContainerLowest = surfaceContainerLowest ?? surface
        self.surfaceContainerHighest = surfaceContainerHighest ?? surfaceContainerHigh
        self.onSecondaryContainer = onSecondaryContainer ?? onSurface
        self.errorContainer = errorContainer ?? error.opacity(0.18)
        self.onErrorContainer = onErrorContainer ?? error
        self.successContainer = successContainer ?? success.opacity(0.20)
        self.onSuccessContainer = onSuccessContainer ?? success
        self.onSecondary = onSecondary ?? surface
        self.onTertiary = onTertiary ?? surface
        self.onError = onError ?? surface
        self.scrim = scrim ?? .black
    }

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
        outlineVariant: Color(hex: 0xCAC4CF),
        error: Color(hex: 0xBA1A1A),
        success: Color(hex: 0x3F6A3F),
        onSurfaceVariant: Color(hex: 0x49454E),
        surfaceContainerLowest: Color(hex: 0xFFFFFF),
        surfaceContainerHighest: Color(hex: 0xE6DFEA),
        onSecondaryContainer: Color(hex: 0x1F182B),
        errorContainer: Color(hex: 0xFFDAD6),
        onErrorContainer: Color(hex: 0x410002),
        successContainer: Color(hex: 0xC2F0BF),
        onSuccessContainer: Color(hex: 0x0A2A0A),
        onSecondary: .white,
        onTertiary: .white,
        onError: .white,
        scrim: .black
    )

    static let lavenderDark = Palette(
        primary: Color(hex: 0xD4BBFF),
        onPrimary: Color(hex: 0x3B1C71),
        primaryContainer: Color(hex: 0x523689),
        onPrimaryContainer: Color(hex: 0xEBDDFF),
        secondary: Color(hex: 0xCDC2DB),
        secondaryContainer: Color(hex: 0x4B4358),
        tertiary: Color(hex: 0xFFB1C7),
        tertiaryContainer: Color(hex: 0x7B2949),
        onTertiaryContainer: Color(hex: 0xFFD9E2),
        surface: Color(hex: 0x141218),
        onSurface: Color(hex: 0xE6E0E9),
        surfaceContainerLow: Color(hex: 0x1C1B20),
        surfaceContainer: Color(hex: 0x211F25),
        surfaceContainerHigh: Color(hex: 0x2B292F),
        outline: Color(hex: 0x948F99),
        outlineVariant: Color(hex: 0x49454E),
        error: Color(hex: 0xFFB4AB),
        success: Color(hex: 0xA6D3A0),
        onSurfaceVariant: Color(hex: 0xCAC4CF),
        surfaceContainerLowest: Color(hex: 0x0E0D13),
        surfaceContainerHighest: Color(hex: 0x36343A),
        onSecondaryContainer: Color(hex: 0xE9DEF8),
        errorContainer: Color(hex: 0x93000A),
        onErrorContainer: Color(hex: 0xFFDAD6),
        successContainer: Color(hex: 0x265225),
        onSuccessContainer: Color(hex: 0xC2F0BF),
        onSecondary: Color(hex: 0x342D41),
        onTertiary: Color(hex: 0x5E1133),
        onError: Color(hex: 0x690005),
        scrim: .black
    )
}

/// The brand mark. Fixed for ever: the egg on cream is never re-tinted by a
/// theme (handoff §11), so these are the one place a literal colour is correct.
enum Brand {
    static let egg = Color(hex: 0xF4AA7E)
    static let eggShade = Color(hex: 0xF29266)
    static let eggHighlight = Color(hex: 0xF9CBAC)
    static let shell = Color(hex: 0xFCF6D0)
    static let shellBright = Color(hex: 0xFCF7EE)
    static let shellDim = Color(hex: 0xFCF6C8)
}

/// Corner radii of the refonte (§3.4), in the iOS variants of §4.
enum Radius {
    static let card: CGFloat = 20
    static let listGroup: CGFloat = 20
    static let sheet: CGFloat = 28
    static let launcherTile: CGFloat = 16
    static let iconTile: CGFloat = 12
    static let pill: CGFloat = 100
    static let field: CGFloat = 16
}

/// The 8-grid of the refonte (§3.5).
enum Metrics {
    static let screenMargin: CGFloat = 16
    static let cardPadding: CGFloat = 20
    static let blockGap: CGFloat = 12
    /// An anchored action bar reserves its band; it never floats over content.
    static let actionBarHeight: CGFloat = 84
    static let touchTarget: CGFloat = 44
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
