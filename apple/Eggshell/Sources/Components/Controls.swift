import SwiftUI

// Pills, the segmented selector and the anchored action bar.
//
// iOS never gets a FAB (§4): the main action of a screen lives in a bar
// anchored to the bottom, and that bar **reserves** its band — the scrollable
// area stops `Metrics.actionBarHeight` before the bottom edge so the button can
// never hide the last launcher row or the last history line.

/// Selectable period / filter pill: 36 high, radius 100, 13.5/600.
struct PillView: View {
    @Environment(\.palette) private var palette

    let label: String
    var selected: Bool
    var enabled: Bool = true
    let action: () -> Void

    init(_ label: String, selected: Bool, enabled: Bool = true, action: @escaping () -> Void) {
        self.label = label
        self.selected = selected
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(selected ? palette.onPrimary : palette.onSurfaceVariant)
                .padding(.horizontal, 15)
                .frame(height: 36)
                .background(
                    selected ? palette.primary : palette.surfaceContainerHigh,
                    in: Capsule()
                )
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// Read-only status pill. Punctuality states use it with three distinct
/// containers, but the word is always there too: no information is ever carried
/// by colour alone (§10).
struct StatusPillView: View {
    let label: String
    var systemImage: String? = nil
    let container: Color
    let content: Color

    init(_ label: String, systemImage: String? = nil, container: Color, content: Color) {
        self.label = label
        self.systemImage = systemImage
        self.container = container
        self.content = content
    }

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage {
                Image(systemName: systemImage).font(.system(size: 10, weight: .bold))
            }
            Text(label).font(EggFont.micro)
        }
        .foregroundStyle(content)
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(container, in: Capsule())
    }
}

/// The native segmented control, wrapped so screens declare options once.
struct SegmentedSelector: View {
    let options: [String]
    @Binding var selection: Int
    var accessibilityLabel: String? = nil

    init(options: [String], selection: Binding<Int>, accessibilityLabel: String? = nil) {
        self.options = options
        self._selection = selection
        self.accessibilityLabel = accessibilityLabel
    }

    var body: some View {
        Picker(accessibilityLabel ?? "", selection: $selection) {
            ForEach(Array(options.enumerated()), id: \.offset) { pair in
                Text(pair.element).tag(pair.offset)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }
}

/// The anchored bottom bar. Sits on `surfaceContainer` behind a 1 pt top rule
/// and occupies a fixed 84 pt band — attach it with `.eggActionBar { … }` so the
/// content above it is inset by exactly that much.
struct ActionBar<Content: View>: View {
    @Environment(\.palette) private var palette
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(palette.outlineVariant)
                .frame(height: 1)
            content()
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 18)
                .padding(.top, 10)
                .padding(.bottom, 8)
        }
        .frame(height: Metrics.actionBarHeight)
        .background(palette.surfaceContainer)
    }
}

/// The main action of a screen: full width, filled, radius 100, 46 high.
struct ActionBarButton: View {
    @Environment(\.palette) private var palette

    let label: String
    var systemImage: String? = nil
    var enabled: Bool = true
    let action: () -> Void

    init(
        _ label: String,
        systemImage: String? = nil,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.label = label
        self.systemImage = systemImage
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: Spacing.s) {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 17, weight: .semibold))
                }
                Text(label).font(.system(size: 15.5, weight: .semibold))
            }
            .foregroundStyle(palette.onPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .background(palette.primary.opacity(enabled ? 1 : 0.4), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

extension View {
    /// Anchors an action bar to the bottom and reserves its band.
    func eggActionBar<C: View>(@ViewBuilder _ content: @escaping () -> C) -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) {
            ActionBar(content: content)
        }
    }
}

/// The « Enregistré ✓ » confirmation of §5.4. Lives at the root of a screen so
/// it survives a pop; it is never the only trace of a save.
struct SnackbarView: View {
    @Environment(\.palette) private var palette
    let message: String
    var actionLabel: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: Spacing.m) {
            Text(message)
                .font(EggFont.label)
                .foregroundStyle(palette.surface)
            if let actionLabel, let action {
                Button(action: action) {
                    Text(actionLabel)
                        .font(EggFont.label)
                        .foregroundStyle(palette.primary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, Metrics.cardPadding)
        .padding(.vertical, Spacing.m)
        .background(palette.onSurface, in: Capsule())
        .shadow(color: palette.scrim.opacity(0.18), radius: 8, y: 2)
    }
}
