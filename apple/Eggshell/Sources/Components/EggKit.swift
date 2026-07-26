import SwiftUI

// The component kit of the refonte, in iOS grammar.
//
// Android has the same kit in `ui/components/Cards.kt` + `Rows.kt` +
// `Controls.kt` + `Charts.kt`; the vocabulary (variants, radii, states) is
// shared, the rendering is not — see handoff §4. Everything here reads its
// colours from `\.palette`: the app ships 14 palettes and a literal hex would
// break thirteen of them.

/// The type scale of §3.3, in the sizes iOS actually needs on top of the seven
/// `Font.egg*` faces. A namespace rather than a `Font` extension so it can
/// never collide with a future addition to `Theme/Typography.swift`.
enum EggFont {
    /// `t-label-s` — the all-caps micro label. Pair with `.tracking(0.5)`.
    static let micro = Font.system(size: 11, weight: .semibold)
    /// `t-label` — buttons, pills, tile captions.
    static let label = Font.system(size: 13, weight: .semibold)
    /// `t-body-s` — secondary sentences inside a card.
    static let bodyS = Font.system(size: 13, weight: .regular)
    /// `t-title-s` — a row title.
    static let titleS = Font.system(size: 15, weight: .semibold)
    /// `t-title-l` — the headline of a card.
    static let titleL = Font.system(size: 20, weight: .semibold)
    /// The home's large title: 22 pt bold, one line (§4).
    static let screenTitle = Font.system(size: 22, weight: .bold, design: .rounded)
}

/// Tonal tiers a card can wear. Surfaces differentiate by tier, never by
/// elevation — nothing in the refonte casts a shadow.
enum CardVariant {
    case primary, tertiary, secondary, low, outlined, error
}

/// The content card: flat, tonal, radius 20, no shadow.
struct EggCard<Content: View>: View {
    @Environment(\.palette) private var palette

    var variant: CardVariant = .low
    var paddingH: CGFloat = Metrics.cardPadding
    var paddingV: CGFloat = Metrics.cardPadding
    var cornerRadius: CGFloat = Radius.card
    var spacing: CGFloat = Spacing.m
    /// Overrides the variant's container. The home cards use it to sit one tier
    /// higher than `low` so the controls they host can still step above them.
    var container: Color? = nil
    var action: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        if let action {
            Button(action: action) { surface }
                .buttonStyle(.plain)
        } else {
            surface
        }
    }

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    }

    private var surface: some View {
        VStack(alignment: .leading, spacing: spacing, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, paddingH)
            .padding(.vertical, paddingV)
            .background(shape.fill(container ?? containerColor))
            .overlay {
                if variant == .outlined {
                    shape.stroke(palette.outlineVariant, lineWidth: 1)
                }
            }
            .foregroundStyle(onContainerColor)
    }

    private var containerColor: Color {
        switch variant {
        case .primary:   return palette.primaryContainer
        case .tertiary:  return palette.tertiaryContainer
        case .secondary: return palette.secondaryContainer
        case .low:       return palette.surfaceContainerLow
        case .outlined:  return palette.surface
        case .error:     return palette.errorContainer
        }
    }

    private var onContainerColor: Color {
        switch variant {
        case .primary:              return palette.onPrimaryContainer
        case .tertiary:             return palette.onTertiaryContainer
        case .secondary:            return palette.onSecondaryContainer
        case .low, .outlined:       return palette.onSurface
        case .error:                return palette.onErrorContainer
        }
    }
}

/// The 1 pt hairline inside a card, at 20 % of the ink the card already set.
/// `.foreground` resolves to the inherited foreground style, so the rule works
/// on every variant without knowing which one it is in.
struct CardRule: View {
    var opacity: Double = 0.20
    var body: some View {
        Rectangle()
            .fill(.foreground)
            .opacity(opacity)
            .frame(height: 1)
            .frame(maxWidth: .infinity)
    }
}

/// Small tinted tile used as the leading slot of a row or an identity card.
struct IconTile<Content: View>: View {
    @Environment(\.palette) private var palette

    var size: CGFloat = 44
    var cornerRadius: CGFloat = Radius.iconTile
    var container: Color? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .frame(width: size, height: size)
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(container ?? palette.surfaceContainerHigh)
            )
    }
}

/// The one empty state of the app (§5.3): a low card, a sentence in the second
/// person, and a button that starts the thing. Never a blank screen.
struct EmptyStateView: View {
    @Environment(\.palette) private var palette

    let message: String
    var systemImage: String? = nil
    var actionLabel: String? = nil
    var action: (() -> Void)? = nil

    init(
        _ message: String,
        systemImage: String? = nil,
        actionLabel: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.message = message
        self.systemImage = systemImage
        self.actionLabel = actionLabel
        self.action = action
    }

    var body: some View {
        EggCard(variant: .low) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 26))
                    .foregroundStyle(palette.onSurfaceVariant)
            }
            Text(message)
                .font(.eggBody)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            if let actionLabel, let action {
                Button(action: action) {
                    Text(actionLabel)
                        .font(EggFont.label)
                        .foregroundStyle(palette.primary)
                }
                .buttonStyle(.plain)
                .frame(minHeight: Metrics.touchTarget, alignment: .leading)
            }
        }
    }
}

/// Errors live in the flow as an error-container card with an explicit message
/// and a way to retry — never a toast the user can miss (§5.3).
struct ErrorCardView: View {
    @Environment(\.palette) private var palette

    let message: String
    var retryLabel: String? = nil
    var retry: (() -> Void)? = nil

    init(_ message: String, retryLabel: String? = nil, retry: (() -> Void)? = nil) {
        self.message = message
        self.retryLabel = retryLabel
        self.retry = retry
    }

    var body: some View {
        EggCard(variant: .error, spacing: Spacing.s) {
            HStack(alignment: .top, spacing: Spacing.m) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 18))
                Text(message)
                    .font(.eggBody)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let retryLabel, let retry {
                Button(action: retry) {
                    Text(retryLabel).font(EggFont.label)
                }
                .buttonStyle(.plain)
                .frame(minHeight: Metrics.touchTarget, alignment: .leading)
            }
        }
    }
}

/// Loading placeholder shaped like the real content (§5.3). Never a full-screen
/// spinner: the page keeps its silhouette while the vault query runs.
struct SkeletonBlock: View {
    @Environment(\.palette) private var palette

    let height: CGFloat
    var cornerRadius: CGFloat = Radius.field

    init(height: CGFloat, cornerRadius: CGFloat = Radius.field) {
        self.height = height
        self.cornerRadius = cornerRadius
    }

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(palette.surfaceContainerHigh)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .accessibilityHidden(true)
    }
}
