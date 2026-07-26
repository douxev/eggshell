import SwiftUI

// Pre-refonte building blocks, kept while the screens are rewritten one by one
// against the new kit (`EggKit.swift`, `ListRows.swift`, `Controls.swift`,
// `Charts.swift`). ~120 call sites still reach for `SectionCard`, so these types
// stay until the last screen has moved; new code should use the kit.

/// Status for add/edit forms. Mirrors the Android Idle/Submitting/Done/Error pattern.
enum FormStatus: Equatable {
    case idle, submitting, done
    case error(String)
    var isSubmitting: Bool { self == .submitting }
    var errorText: String? { if case .error(let m) = self { return m } else { return nil } }
}

/// Title row reused on every tab root, with a gear that pushes Settings.
struct ScreenHeader: View {
    @Environment(\.palette) private var palette
    let title: String
    var showSettings: Bool = true

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.eggDisplay)
                .foregroundStyle(palette.onSurface)
            Spacer()
            if showSettings {
                NavigationLink(value: Route.settingsHub) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                        .padding(8)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, Spacing.l)
        .padding(.top, Spacing.s)
    }
}

/// Glass-backed section container.
struct SectionCard<Content: View>: View {
    var padding: CGFloat = Spacing.l
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .glassCard(cornerRadius: Corner.large)
    }
}

struct EmptyStateCard: View {
    @Environment(\.palette) private var palette
    let text: String
    var systemImage: String? = nil
    var body: some View {
        VStack(spacing: Spacing.s) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 28))
                    .foregroundStyle(palette.onSurfaceVariant.opacity(0.7))
            }
            Text(text)
                .font(.eggCallout)
                .foregroundStyle(palette.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(Spacing.xl)
        .glassCard(cornerRadius: Corner.large)
    }
}

struct ErrorBanner: View {
    @Environment(\.palette) private var palette
    let message: String
    var body: some View {
        Text(message)
            .font(.eggCallout)
            .foregroundStyle(palette.error)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct Pill: View {
    @Environment(\.palette) private var palette
    let text: String
    var tint: Color? = nil
    var body: some View {
        Text(text)
            .font(.eggLabel)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, 4)
            .background((tint ?? palette.secondaryContainer), in: Capsule())
            .foregroundStyle(palette.onSurface)
    }
}

/// Selectable chip used in forms (kind/route/unit choices).
struct ChoiceChip: View {
    @Environment(\.palette) private var palette
    let label: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.eggLabel)
                .padding(.horizontal, Spacing.m)
                .padding(.vertical, Spacing.s)
                .background(selected ? palette.primaryContainer : palette.surfaceContainerHigh, in: Capsule())
                .foregroundStyle(selected ? palette.onPrimaryContainer : palette.onSurface)
        }
        .buttonStyle(.plain)
    }
}

/// Scrollable tab-root scaffold: gradient content layer + header + content.
struct TabScaffold<Content: View>: View {
    @Environment(\.palette) private var palette
    let title: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack {
            LinearGradient(colors: [palette.surface, palette.surfaceContainerLow],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    ScreenHeader(title: title)
                    content()
                        .padding(.horizontal, Spacing.l)
                    // Reserves the band an anchored action bar lives in.
                    Color.clear.frame(height: Metrics.actionBarHeight)
                }
            }
        }
        // Route destinations are registered once, on the root stack (AppShell).
    }
}
