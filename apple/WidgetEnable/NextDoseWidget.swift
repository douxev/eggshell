import WidgetKit
import SwiftUI

// =============================================================================
// NextDoseWidget — extension WidgetKit "prochaine dose".
// =============================================================================
//
// PARITÉ ANDROID (EggshellWidgetProvider) : affiche jusqu'à 3 rappels à venir,
// lus depuis un miroir EN CLAIR partagé via le conteneur App Group
// (`group.com.douxev.eggshell`). Le widget tourne dans un processus séparé qui
// n'a JAMAIS la clé du coffre : il ne lit donc que ce miroir, jamais vault.db.
//
// CONFIDENTIALITÉ : le miroir ne contient un nom de médicament en clair que si
// l'app l'a écrit en mode `.name` (opt-in). En mode leurre, l'app appelle
// `WidgetMirror.clear()` → le miroir est vide → le widget montre "Aucun rappel".
//
// → Ce fichier est prêt à COPIER dans la CIBLE WIDGET (EggshellWidget). Il est
//   volontairement AUTONOME (ne dépend ni de TransitionCore ni des sources de
//   l'app) : un mini-lecteur de miroir est inclus ci-dessous. Si vous préférez,
//   ajoutez plutôt WidgetMirror.swift à la cible widget ET à la cible app et
//   supprimez le mini-lecteur local — mais l'autonomie évite tout couplage.
//
// Tout le texte d'UI est en FRANÇAIS.

// MARK: - Mini-lecteur de miroir (autonome, miroir de WidgetMirror)

private enum NextDoseMirror {
    static let appGroupId = "group.com.douxev.eggshell"
    static let fileName = "next_dose_mirror.json"

    struct Entry: Codable, Hashable {
        var title: String
        var dueAtMs: Int64
        var systemImage: String?
    }
    struct Payload: Codable {
        var schema: Int
        var mode: String
        var updatedAtMs: Int64
        var entries: [Entry]
    }

    static func read() -> Payload {
        guard let dir = FileManager.default
                .containerURL(forSecurityApplicationGroupIdentifier: appGroupId),
              let data = try? Data(contentsOf: dir.appendingPathComponent(fileName)),
              let payload = try? JSONDecoder().decode(Payload.self, from: data) else {
            return Payload(schema: 1, mode: "generic", updatedAtMs: 0, entries: [])
        }
        return payload
    }
}

/// Libellé relatif FR compact, calqué sur android relativeLabel /
/// l'app `relativeDueLabel` : heure aujourd'hui, "demain", "dans N j", sinon date.
private func relativeDueLabel(_ ms: Int64, now: Date = Date()) -> String {
    let date = Date(timeIntervalSince1970: Double(ms) / 1000)
    let cal = Calendar.current
    let diff = date.timeIntervalSince(now)
    if diff <= 0 { return "maintenant" }
    if cal.isDate(date, inSameDayAs: now) {
        let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "HH:mm"
        return f.string(from: date)
    }
    if let tomorrow = cal.date(byAdding: .day, value: 1, to: now),
       cal.isDate(date, inSameDayAs: tomorrow) {
        return "demain"
    }
    let days = cal.dateComponents([.day],
                                  from: cal.startOfDay(for: now),
                                  to: cal.startOfDay(for: date)).day ?? 0
    if days > 1 && days < 7 { return "dans \(days) j" }
    let f = DateFormatter(); f.locale = Locale(identifier: "fr_FR"); f.dateFormat = "d MMM"
    return f.string(from: date)
}

// MARK: - TimelineEntry

struct NextDoseTimelineEntry: TimelineEntry {
    let date: Date
    let rows: [NextDoseMirror.Entry]
}

// MARK: - TimelineProvider

struct NextDoseProvider: TimelineProvider {
    func placeholder(in context: Context) -> NextDoseTimelineEntry {
        NextDoseTimelineEntry(
            date: Date(),
            rows: [NextDoseMirror.Entry(
                title: "Prochaine prise",
                dueAtMs: Int64(Date().addingTimeInterval(3600).timeIntervalSince1970 * 1000),
                systemImage: "pills")])
    }

    func getSnapshot(in context: Context, completion: @escaping (NextDoseTimelineEntry) -> Void) {
        completion(makeEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NextDoseTimelineEntry>) -> Void) {
        let entry = makeEntry()
        // Rafraîchir au prochain rappel à venir (sinon dans 1 h). Le système
        // peut limiter le budget de rafraîchissement ; l'app force par ailleurs
        // un rechargement via WidgetCenter quand le miroir change.
        let nextRefresh: Date
        if let soonest = entry.rows.map(\.dueAtMs).min() {
            let due = Date(timeIntervalSince1970: Double(soonest) / 1000)
            nextRefresh = max(due, Date().addingTimeInterval(60))
        } else {
            nextRefresh = Date().addingTimeInterval(3600)
        }
        completion(Timeline(entries: [entry], policy: .after(nextRefresh)))
    }

    private func makeEntry() -> NextDoseTimelineEntry {
        let payload = NextDoseMirror.read()
        // Ne montrer que les échéances présentes / futures, triées.
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let rows = payload.entries
            .sorted { $0.dueAtMs < $1.dueAtMs }
            .filter { $0.dueAtMs >= nowMs - 60_000 }   // tolérance d'1 min
        return NextDoseTimelineEntry(date: Date(), rows: rows)
    }
}

// MARK: - Vue

struct NextDoseWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NextDoseTimelineEntry

    private var rowLimit: Int {
        switch family {
        case .systemSmall: return 1
        case .systemMedium: return 2
        default: return 3
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "bell.fill")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text("Rappels")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }

            if entry.rows.isEmpty {
                Spacer(minLength: 0)
                Text("Aucun rappel")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            } else {
                ForEach(Array(entry.rows.prefix(rowLimit).enumerated()), id: \.offset) { _, row in
                    rowView(row)
                }
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        // Deep-link : ouvre l'app. (Schéma à gérer côté app via onOpenURL.)
        .widgetURL(URL(string: "eggshell://open"))
        .containerBackground(for: .widget) {
            Color(.systemBackground)
        }
    }

    @ViewBuilder
    private func rowView(_ row: NextDoseMirror.Entry) -> some View {
        HStack(spacing: 8) {
            Image(systemName: row.systemImage ?? "pills.fill")
                .font(.footnote)
                .foregroundStyle(.tint)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(row.title)
                    .font(.footnote.weight(.medium))
                    .lineLimit(1)
                Text(relativeDueLabel(row.dueAtMs))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Widget

struct NextDoseWidget: Widget {
    let kind = "NextDoseWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: NextDoseProvider()) { entry in
            NextDoseWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Prochaine dose")
        .description("Affiche vos prochains rappels.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Bundle de widgets

@main
struct EggshellWidgetBundle: WidgetBundle {
    var body: some Widget {
        NextDoseWidget()
    }
}
