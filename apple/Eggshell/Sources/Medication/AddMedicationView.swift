import SwiftUI
import TransitionCore
import UIKit

// ===========================================================================
// Médics — create or edit a treatment (handoff §6.4, action bar « Ajouter un
// traitement »).
//
//   • init(editId:) nil  → create, then chain straight into its first reminder
//     (a treatment nobody reminds you of is a treatment you forget).
//   • init(editId:) set  → load, prefill, save via updateMedication AND record
//     dose/unit/route edits as timestamped TreatmentChange rows, so the detail
//     screen and the correlation timeline can say when the dose moved.
//
// Everything the old form could do it still does: the accent colour (shared with
// Android through the vault) and the notification alias are both here (D5).
// ===========================================================================

@MainActor
final class AddMedicationViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?
    @Published var status: FormStatus = .idle

    // Editable fields
    @Published var name = ""
    @Published var kind = MedCatalog.kinds.first ?? "estrogen"
    @Published var route = MedCatalog.routes.first ?? "oral"
    @Published var doseText = ""
    @Published var unit = ""
    /// Opaque ARGB, or nil for « aucune couleur ».
    @Published var argb: Int64?
    @Published var notes = ""
    /// The decoy name shown in reminders. Lives in plain UserDefaults on
    /// purpose: it is a label you chose precisely so nothing real leaks.
    @Published var alias = ""

    let editId: Int64?
    private var original: Medication?
    private var seeded = false

    init(editId: Int64?) {
        self.editId = editId
    }

    var isEditing: Bool { editId != nil }

    var isSubmitting: Bool {
        if case .submitting = status { return true }
        return false
    }

    func load(_ session: VaultService) async {
        loading = true
        do {
            if let id = editId {
                let med = try await session.getMedication(id)
                original = med
                if let med, !seeded {
                    name = med.name
                    kind = med.kind
                    route = med.route
                    doseText = med.defaultDose.map { formatDose($0) } ?? ""
                    unit = med.defaultDoseUnit ?? ""
                    argb = med.color
                    notes = med.notes ?? ""
                    alias = NotifPrefs.alias(for: id) ?? ""
                    seeded = true
                }
            }
        } catch {
            self.error = describe(error)
        }
        loading = false
    }

    /// Save (create or update). Returns the medication id on success, nil on
    /// failure, so the caller can chain (create) or just pop (edit).
    func save(_ session: VaultService) async -> Int64? {
        status = .submitting
        error = nil
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsedDose = Double(doseText.replacingOccurrences(of: ",", with: "."))
        let med = NewMedication(
            name: trimmed,
            kind: kind,
            route: route,
            defaultDose: parsedDose,
            defaultDoseUnit: unit.isEmpty ? nil : unit,
            color: argb,
            notes: notes.isEmpty ? nil : notes)
        do {
            let id: Int64
            if let editId {
                try await session.updateMedication(editId, med)
                await logTreatmentChanges(session, id: editId, new: med)
                id = editId
            } else {
                id = try await session.addMedication(med).id
            }
            NotifPrefs.setAlias(alias, for: id)
            status = .done
            return id
        } catch {
            self.error = describe(error)
            status = .error(describe(error))
            return nil
        }
    }

    /// Record dose/unit/route edits as audit rows for the correlation timeline.
    /// No-op when nothing dose-related changed. Best-effort (never blocks save).
    private func logTreatmentChanges(_ session: VaultService, id: Int64, new: NewMedication) async {
        guard let old = original else { return }
        let now = Time.nowMs()
        func change(_ field: String, _ oldV: String?, _ newV: String?) async {
            guard oldV != newV else { return }
            _ = try? await session.logTreatmentChange(NewTreatmentChange(
                medicationId: id,
                atMs: now,
                field: field,
                oldValue: oldV,
                newValue: newV,
                note: nil))
        }
        await change("dose", old.defaultDose.map { formatDose($0) }, new.defaultDose.map { formatDose($0) })
        await change("unit", old.defaultDoseUnit, new.defaultDoseUnit)
        await change("route", old.route, new.route)
    }

    private func formatDose(_ value: Double) -> String {
        if value == value.rounded() { return String(Int(value)) }
        return String(format: "%g", value)
    }
}

struct AddMedicationView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: AddMedicationViewModel

    init(editId: Int64? = nil) {
        _vm = StateObject(wrappedValue: AddMedicationViewModel(editId: editId))
    }

    private var trimmedName: String {
        vm.name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                if case let .error(message) = vm.status {
                    ErrorCardView(message, retryLabel: "Réessayer") { save() }
                } else if let e = vm.error {
                    ErrorCardView(e)
                }

                if vm.loading {
                    SkeletonBlock(height: 96)
                    SkeletonBlock(height: 120)
                    SkeletonBlock(height: 120)
                } else {
                    identityBlock
                    kindBlock
                    routeBlock
                    doseBlock
                    noteBlock
                    notificationBlock
                    colorBlock
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Metrics.blockGap)
        }
        .medsScreen(vm.isEditing ? "Modifier le traitement" : "Nouveau traitement")
        .eggActionBar {
            ActionBarButton(
                vm.isEditing ? "Enregistrer" : "Créer",
                systemImage: vm.isEditing ? "checkmark" : "plus",
                enabled: !trimmedName.isEmpty && !vm.isSubmitting
            ) { save() }
        }
        .task { if let s = app.session { await vm.load(s) } }
    }

    // MARK: - Blocks

    private var identityBlock: some View {
        MedsFormBlock("LE TRAITEMENT") {
            MedsField(placeholder: "Nom du traitement", text: $vm.name)
        }
    }

    private var kindBlock: some View {
        MedsFormBlock("LE TYPE") {
            MedsChipRow(
                options: MedCatalog.kinds,
                selected: vm.kind,
                label: MedCatalog.kindLabel,
                onSelect: { vm.kind = $0 })
        }
    }

    private var routeBlock: some View {
        MedsFormBlock("LA VOIE") {
            MedsChipRow(
                options: MedCatalog.routes,
                selected: vm.route,
                label: MedCatalog.routeLabel,
                onSelect: { vm.route = $0 })
        }
    }

    private var doseBlock: some View {
        MedsFormBlock("LA DOSE", footnote: "Ta dose habituelle : elle est proposée d'avance chaque fois que tu notes une prise.") {
            HStack(spacing: Spacing.m) {
                MedsField(placeholder: "0", text: $vm.doseText, keyboard: .decimalPad)
                MedsField(placeholder: "unité", text: $vm.unit)
                    .frame(maxWidth: 130)
            }
        }
    }

    private var noteBlock: some View {
        MedsFormBlock("UN MOT") {
            MedsField(
                placeholder: "Ce que tu veux garder en tête (facultatif)",
                text: $vm.notes,
                multiline: true)
        }
    }

    private var notificationBlock: some View {
        MedsFormBlock(
            "DANS LES NOTIFICATIONS",
            footnote: aliasFootnote
        ) {
            MedsField(placeholder: "Ex. : Vitamines", text: $vm.alias, maxLength: 40)
        }
    }

    /// Says whether the alias will actually be seen — the content mode lives in
    /// Réglages → Rappels, and a field that quietly does nothing is worse than
    /// no field at all.
    private var aliasFootnote: String {
        switch NotifPrefs.contentMode {
        case .alias:
            return "C'est ce nom-là que tes rappels afficheront, à la place du vrai."
        case .name:
            return "Tes rappels affichent le vrai nom pour l'instant. Passe en « Alias » dans Réglages → Rappels pour montrer celui-ci."
        case .generic:
            return "Tes rappels ne disent rien du traitement pour l'instant. Passe en « Alias » dans Réglages → Rappels pour montrer ce nom-là."
        }
    }

    private var colorBlock: some View {
        MedsFormBlock(
            "SA COULEUR",
            footnote: "Elle te sert à le repérer d'un coup d'œil, dans la liste et sur le calendrier."
        ) {
            MedColorPicker(argb: $vm.argb)
        }
    }

    // MARK: - Saving

    private func save() {
        guard !trimmedName.isEmpty, let session = app.session else { return }
        let editing = vm.isEditing
        Task {
            guard let id = await vm.save(session) else { return }
            await app.refreshNotifications()
            dismiss()
            // A new treatment goes straight to its first reminder: that is the
            // difference between a list and a follow-up.
            if !editing { router.push(.addSchedule(medId: id)) }
        }
    }
}

// Medication accent color <-> stored Int. Stored as opaque ARGB (0xFFRRGGBB) so
// Android's `Color(it.toInt())` (which reads ARGB) renders the same swatch from
// the shared DB. Internal (not private): the journal calendar and the hormones
// chart reuse `color(fromArgb:)` to tint per-med dose markers.
enum MedColor {
    static func argb(from color: Color) -> Int64 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        func byte(_ v: CGFloat) -> Int64 { Int64((max(0, min(1, v)) * 255).rounded()) }
        return Int64(0xFF00_0000) | (byte(r) << 16) | (byte(g) << 8) | byte(b)
    }

    static func color(fromArgb v: Int64) -> Color {
        Color(.sRGB,
              red: Double((v >> 16) & 0xFF) / 255,
              green: Double((v >> 8) & 0xFF) / 255,
              blue: Double(v & 0xFF) / 255,
              opacity: 1)
    }
}
