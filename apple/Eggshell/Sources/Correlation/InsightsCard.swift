import SwiftUI
import TransitionCore

/// « Ce qui va ensemble » — the links the engine found, strongest first.
///
/// **Every string here is co-occurrence, never cause.** The engine compares
/// averages inside one person's own record over a few weeks; it cannot separate
/// "the missed dose wrecked the sleep" from "the bad week did both". The
/// wording is « va avec » / « le lendemain », never « à cause de » — and the
/// sample counts sit on every row, unhidden, because « 6 relevés contre 9 » and
/// « 40 contre 52 » are very different claims wearing the same sentence.
///
/// Mirrors Android's `InsightsCard`, including which metrics get no verdict
/// colour: libido and dream intensity are not achievements, and painting one
/// green would be the app deciding what a good night looks like for someone
/// else.
struct InsightsCard: View {
    @Environment(\.palette) private var palette

    let insights: [Insight]

    /// Past this the card stops being a summary. The engine already ranks by
    /// size, so the tail is the weakest findings — and a wall of them is how a
    /// reader starts believing all of it.
    private let maxShown = 5

    var body: some View {
        if insights.isEmpty {
            EmptyView()
        } else {
            EggCard(variant: .low, paddingH: 18, paddingV: 14, spacing: 0) {
                Text("Ce qui va ensemble")
                    .font(EggFont.titleS)
                    .foregroundStyle(palette.onSurface)
                MicroLabel("Repéré dans tes propres relevés, du plus net au moins net.")
                    .padding(.top, 2)

                ForEach(Array(insights.prefix(maxShown).enumerated()), id: \.offset) { pair in
                    if pair.offset > 0 { CardRule(opacity: 0.14) }
                    row(pair.element)
                }

                CardRule(opacity: 0.14).padding(.top, 4)
                Text("Ce sont des coïncidences relevées dans ton journal, pas des causes. Beaucoup d’autres choses bougent en même temps — à regarder comme une piste, pas comme une explication.")
                    .font(EggFont.bodyS)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 10)
            }
        }
    }

    private func row(_ insight: Insight) -> some View {
        let rising = insight.delta > 0
        let tint: Color = {
            // Neutral metrics get no verdict colour at all.
            guard insight.valence != .neutral else { return palette.onSurfaceVariant }
            return insight.favourable ? palette.success : palette.error
        }()

        return HStack(alignment: .top, spacing: Spacing.m) {
            Image(systemName: rising ? "arrow.up" : "arrow.down")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 2) {
                Text(sentence(for: insight))
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                // The counts are the honesty of the row. Never folded away
                // behind a "confidence" badge: the reader can weigh 6-vs-9
                // themselves, and one word could not carry the same thing.
                MicroLabel("Sur \(insight.sampleWith) relevés contre \(insight.sampleWithout)")
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 12)
    }

    /// « Ton sommeil est plus haut les jours où tout a été pris à l'heure :
    /// +2,1 en moyenne. »
    ///
    /// Assembled from two halves so the metric and the condition stay
    /// independently translatable — one format string per combination would be
    /// sixty strings, and the sixty-first would be forgotten.
    private func sentence(for insight: Insight) -> String {
        let metric = Self.metricLabel(insight.metricKey)
        let condition = Self.conditionLabel(insight.againstKey)
        let amount = MeasureFormat.value(abs(insight.delta), digits: 2)
        return insight.delta > 0
            ? "\(metric) est plus haut \(condition) : +\(amount) en moyenne."
            : "\(metric) est plus bas \(condition) : −\(amount) en moyenne."
    }

    /// Names the metric exactly as its slider does. A finding that said
    /// « Qualité du sommeil » while the editor said `sleep_quality` would read
    /// as two unrelated things.
    static func metricLabel(_ key: String) -> String {
        switch key {
        case "mood": return "Ton humeur"
        case "dysphoria": return "Ta dysphorie"
        case "euphoria": return "Ton euphorie"
        case "energy": return "Ton énergie"
        case "libido": return "Ta libido"
        case "sleep_quality": return "La qualité de ton sommeil"
        case "recall": return "La netteté de tes souvenirs"
        case "vividness": return "L’intensité de tes rêves"
        case "emotional_tone": return "La tonalité de tes rêves"
        default: return "Cet indicateur"
        }
    }

    static func conditionLabel(_ key: String) -> String {
        switch key {
        case "missed_dose": return "les jours avec une prise oubliée"
        case "late_dose": return "les jours avec une prise en retard"
        case "clean_day": return "les jours où tout a été pris à l’heure"
        case "lucid_dream": return "le lendemain d’un rêve lucide"
        case "any_dream": return "le lendemain d’une nuit avec rêve"
        default: return "dans ces cas"
        }
    }
}
