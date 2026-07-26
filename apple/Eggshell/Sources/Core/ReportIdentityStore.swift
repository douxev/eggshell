import Foundation
import SwiftUI

// The two identity fields of the doctor report's boxed header (§7.4.2): who the
// document is about, and their date of birth.
//
// They are the only two things the app knows that name a person, so they are the
// two that could never live in `UserDefaults` alongside the theme and the
// feature toggles — an app that ships a decoy mode cannot leave a real name and
// a date of birth readable on disk. They live in the vault's own `app_settings`
// table instead: encrypted with everything else, gone when the vault is wiped.
//
// Nothing else in the app reads them. They are edited on « Rapport médecin »,
// where handing the document over is the decision being made, and read back by
// `DoctorReportBuilder`.

/// Where the two fields live and how they are shaped.
///
/// Deliberately not on the store: the builder runs off the main actor and has to
/// apply exactly the same rules the screen does, or the two would disagree about
/// whether the box gets printed.
enum ReportIdentityFields {
    /// Written verbatim on both platforms, so one vault opened by either app
    /// agrees on what it holds.
    static let personKey = "report.identity.person"
    static let birthKey = "report.identity.birth"

    /// The name as it counts: trimmed, and absent when there is nothing left.
    /// The core keeps `""` as a *value*, so blank has to be normalised here —
    /// otherwise a field the user cleared would read as filled in forever.
    static func name(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty
        else { return nil }
        return trimmed
    }

    /// The date of birth's on-disk shape: ISO-8601 `YYYY-MM-DD`.
    ///
    /// It is stored as text and read back to be reformatted — for the row's
    /// subtitle and for the page — so it must never carry the locale that wrote
    /// it. A « 03/02/1996 » typed on a French device would be ambiguous to the
    /// parser and unreadable to Android, hence `en_US_POSIX`, which is also the
    /// only locale guaranteed to stay Gregorian whatever the user picked.
    static func iso(_ date: Date) -> String { formatter().string(from: date) }

    /// nil for anything that is not a plain ISO day, the empty string included:
    /// a box cannot be printed around a date that is not one.
    static func parse(_ raw: String?) -> Date? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty
        else { return nil }
        return formatter().date(from: trimmed)
    }

    /// « 3 février 1996 » — through the document's own date policy, so the row on
    /// screen and the box on the page can never disagree about the same day.
    static func long(_ date: Date, _ f: ReportFormats = ReportFormats()) -> String {
        f.prose(Int64(date.timeIntervalSince1970 * 1000))
    }

    /// Built per call rather than cached: a cached formatter freezes the time
    /// zone it was created in, and the picker hands over a day in the device's
    /// *current* one. Two calls per screen is not worth a stale offset.
    private static func formatter() -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }
}

/// The identity block's state, as the export screen sees it.
@MainActor
final class ReportIdentityStore: ObservableObject {
    /// The name, trimmed, or nil when the key is absent — never `""`, so « is it
    /// filled in » is answered by presence alone.
    @Published private(set) var person: String?
    @Published private(set) var birth: Date?
    /// False until the vault has been read once, so a row can hold its tongue
    /// instead of announcing « non renseignée » before it knows.
    @Published private(set) var loaded = false

    /// The both-or-nothing rule of §7.4.2. One field alone prints nothing, so
    /// nothing on screen may promise a box the document will not draw.
    var isComplete: Bool { person != nil && birth != nil }

    /// One of the two but not both — a state the sheet lets you reach, and which
    /// has to be named rather than passed off as « renseignée ».
    var isPartial: Bool { (person != nil || birth != nil) && !isComplete }

    func load(_ session: VaultService) async {
        person = ReportIdentityFields.name(await read(session, ReportIdentityFields.personKey))
        birth = ReportIdentityFields.parse(await read(session, ReportIdentityFields.birthKey))
        loaded = true
    }

    /// Writes both fields at once. A field left blank is **deleted**, never
    /// stored as `""`.
    func save(person name: String, birth date: Date?, _ session: VaultService) async throws {
        let trimmed = ReportIdentityFields.name(name)
        if let trimmed {
            try await session.setSetting(ReportIdentityFields.personKey, trimmed)
        } else {
            try await session.deleteSetting(ReportIdentityFields.personKey)
        }
        if let date {
            try await session.setSetting(
                ReportIdentityFields.birthKey, ReportIdentityFields.iso(date))
        } else {
            try await session.deleteSetting(ReportIdentityFields.birthKey)
        }
        person = trimmed
        birth = date
    }

    /// Forgets both. Deleting the rows is the point — overwriting them with empty
    /// strings would leave the length of a name behind in the database.
    func erase(_ session: VaultService) async throws {
        try await session.deleteSetting(ReportIdentityFields.personKey)
        try await session.deleteSetting(ReportIdentityFields.birthKey)
        person = nil
        birth = nil
    }

    private func read(_ session: VaultService, _ key: String) async -> String? {
        // A setting that cannot be read is an absent setting: an optional field
        // must never be able to stop the export screen from opening.
        (try? await session.getSetting(key)) ?? nil
    }
}
