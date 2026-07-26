import SwiftUI

/// Saisie rapide (§6.3) — one gesture, from anywhere.
///
/// Six tiles, each rendered only when its module is on: the grid reflows, it
/// never leaves a hole. iOS uses `.presentationDetents` and the native grabber
/// instead of the 34 × 4 handle (§4).
struct QuickLogSheet: View {
    @EnvironmentObject private var features: FeaturesStore
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    /// Whether any treatment is taken by injection — the Injection tile only
    /// makes sense then.
    var hasInjectable: Bool = false

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Noter rapidement")
                .font(EggFont.titleL)
                .foregroundStyle(palette.onSurface)
            Text("Un geste, depuis n'importe quel écran.")
                .font(EggFont.bodyS)
                .foregroundStyle(palette.onSurfaceVariant)
                .padding(.top, 2)

            LazyVGrid(columns: columns, spacing: 10) {
                ForEach(tiles) { tile in
                    QuickLogTile(spec: tile) { go(tile.route) }
                }
            }
            .padding(.top, 18)

            HStack(spacing: 7) {
                Image(systemName: "lock.fill").font(.system(size: 12))
                Text("Enregistré sur l'appareil, chiffré.")
                    .font(EggFont.micro)
                    .tracking(0.5)
            }
            .foregroundStyle(palette.onSurfaceVariant)
            .frame(maxWidth: .infinity)
            .padding(.top, 18)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, Metrics.cardPadding)
        .padding(.top, Spacing.m)
        .padding(.bottom, Spacing.xl)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceContainerHigh.ignoresSafeArea())
    }

    private func go(_ route: Route) {
        dismiss()
        router.push(route)
    }

    private var tiles: [QuickLogSpec] {
        var all: [QuickLogSpec] = []
        if features.journal {
            all.append(QuickLogSpec(
                id: "mood", label: "Ressenti", systemImage: "face.smiling",
                tone: .primary, route: .addJournal(id: nil)))
        }
        if features.medications {
            all.append(QuickLogSpec(
                id: "dose", label: "Prise", systemImage: "pills.fill",
                tone: .secondary, route: .medicationList))
            if hasInjectable {
                all.append(QuickLogSpec(
                    id: "injection", label: "Injection", systemImage: "syringe.fill",
                    tone: .tertiary, route: .medicationList))
            }
        }
        if features.hormones {
            all.append(QuickLogSpec(
                id: "lab", label: "Taux", systemImage: "cross.vial.fill",
                tone: .neutral, route: .addHormone))
        }
        if features.photos {
            all.append(QuickLogSpec(
                id: "photo", label: "Photo", systemImage: "camera.fill",
                tone: .neutral, route: .photos))
        }
        if features.voice {
            all.append(QuickLogSpec(
                id: "voice", label: "Voix", systemImage: "waveform",
                tone: .neutral, route: .voice))
        }
        return all
    }
}

private enum QuickLogTone {
    case primary, secondary, tertiary, neutral
}

private struct QuickLogSpec: Identifiable {
    let id: String
    let label: String
    let systemImage: String
    let tone: QuickLogTone
    let route: Route
}

private struct QuickLogTile: View {
    @Environment(\.palette) private var palette
    let spec: QuickLogSpec
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 9) {
                Image(systemName: spec.systemImage).font(.system(size: 22, weight: .semibold))
                Text(spec.label).font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(content)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .padding(.horizontal, 10)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous).fill(container))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var container: Color {
        switch spec.tone {
        case .primary:   return palette.primaryContainer
        case .secondary: return palette.secondaryContainer
        case .tertiary:  return palette.tertiaryContainer
        case .neutral:   return palette.surfaceContainerHighest
        }
    }

    private var content: Color {
        switch spec.tone {
        case .primary:   return palette.onPrimaryContainer
        case .secondary: return palette.onSecondaryContainer
        case .tertiary:  return palette.onTertiaryContainer
        case .neutral:   return palette.onSurface
        }
    }
}
