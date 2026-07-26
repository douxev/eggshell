import SwiftUI

// Rows, section headers and the micro label. On iOS a settings list is inset
// grouped (radius 20, separators inset 59 pt), so a row is a member of a
// `ListGroup` rather than a free-floating card the way Android draws it (§4).

/// The all-caps micro label of the refonte (« PROCHAINE PRISE », « TON SUIVI »).
/// The caller passes an already-uppercase string: a translation must be able to
/// opt out, so no text transform is applied.
struct MicroLabel: View {
    @Environment(\.palette) private var palette
    let text: String
    var color: Color? = nil

    init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(EggFont.micro)
            .tracking(0.5)
            .foregroundStyle(color ?? palette.onSurfaceVariant)
    }
}

/// The small kind pill sitting next to a row title (« Œstrogène »).
struct TypeBadgeView: View {
    @Environment(\.palette) private var palette
    let label: String

    init(_ label: String) { self.label = label }

    var body: some View {
        Text(label)
            .font(EggFont.micro)
            .foregroundStyle(palette.onSurfaceVariant)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, 2)
            .background(palette.surfaceContainerHighest, in: Capsule())
    }
}

/// Compact section header: a 24 pt row, title on the left, an optional action
/// word in `primary` on the right. iOS writes the home's title in the micro
/// label style (`TON SUIVI`); `prominent` switches to the sentence-case title.
struct SectionTitleView: View {
    @Environment(\.palette) private var palette

    let title: String
    var action: String? = nil
    var onAction: (() -> Void)? = nil
    var prominent: Bool = false

    init(
        _ title: String,
        action: String? = nil,
        onAction: (() -> Void)? = nil,
        prominent: Bool = false
    ) {
        self.title = title
        self.action = action
        self.onAction = onAction
        self.prominent = prominent
    }

    var body: some View {
        HStack(alignment: .center) {
            if prominent {
                Text(title)
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
            } else {
                MicroLabel(title, color: palette.onSurface)
            }
            Spacer(minLength: Spacing.s)
            if let action, let onAction {
                Button(action: onAction) {
                    Text(action)
                        .font(EggFont.micro)
                        .tracking(0.5)
                        .foregroundStyle(palette.primary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(minHeight: 24)
    }
}

/// Inset-grouped container: one rounded 20 pt block, its rows separated by
/// hairlines the rows themselves draw (see `ListRowView.showsSeparator`).
struct ListGroup<Content: View>: View {
    @Environment(\.palette) private var palette
    var container: Color? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0, content: content)
            .background(
                RoundedRectangle(cornerRadius: Radius.listGroup, style: .continuous)
                    .fill(container ?? palette.surfaceContainerLow)
            )
            .clipShape(RoundedRectangle(cornerRadius: Radius.listGroup, style: .continuous))
    }
}

/// The list line of the refonte. Leading icon tile (44), title + optional type
/// badge, subtitle, then either a trailing value or the disclosure chevron.
/// The separator is inset 59 pt: 16 (margin) + 44 (tile) − 1, so it starts
/// exactly under the text column.
struct ListRowView: View {
    @Environment(\.palette) private var palette

    let title: String
    var subtitle: String? = nil
    var badge: String? = nil
    var systemImage: String? = nil
    var iconContainer: Color? = nil
    var iconTint: Color? = nil
    var trailingText: String? = nil
    var showsChevron: Bool = false
    var showsSeparator: Bool = false
    var action: (() -> Void)? = nil

    /// Where the hairline between two rows starts (§4).
    static let separatorInset: CGFloat = 59

    var body: some View {
        if let action {
            Button(action: action) { row }
                .buttonStyle(.plain)
        } else {
            row
        }
    }

    private var row: some View {
        VStack(spacing: 0) {
            HStack(spacing: Spacing.m) {
                if let systemImage {
                    IconTile(size: 44, container: iconContainer) {
                        Image(systemName: systemImage)
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(iconTint ?? palette.onSurfaceVariant)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: Spacing.s) {
                        Text(title)
                            .font(EggFont.titleS)
                            .foregroundStyle(palette.onSurface)
                        if let badge { TypeBadgeView(badge) }
                    }
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(EggFont.bodyS)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: Spacing.s)
                if let trailingText {
                    Text(trailingText)
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                if showsChevron || (action != nil && trailingText == nil) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(palette.outline)
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.vertical, Spacing.m)
            .frame(minHeight: 56)
            .contentShape(Rectangle())

            if showsSeparator {
                Rectangle()
                    .fill(palette.outlineVariant)
                    .frame(height: 1)
                    .padding(.leading, Self.separatorInset)
            }
        }
    }
}
