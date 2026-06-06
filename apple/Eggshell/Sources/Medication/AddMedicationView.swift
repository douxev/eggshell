import SwiftUI
import TransitionCore
import UIKit

// ===========================================================================
// PUSHED screen — create OR edit a medication.
//   • init(editId:) nil  → create a new medication, then chain into its
//     schedule setup (router.push addSchedule).
//   • init(editId:) set  → load the medication, prefill the form, save via
//     updateMedication AND record dose/unit/route edits as timestamped
//     TreatmentChange audit rows (for the correlation timeline), then pop.
//   Parity with Android AddMedicationScreen. All UI strings in French.
// ===========================================================================

@MainActor
final class AddMedicationViewModel: ObservableObject {
    @Published var loading = true
    @Published var error: String?
    @Published var status: FormStatus = .idle

    // Editable fields
    @Published var name = ""
    @Published var kind = MedCatalog.kinds.first ?? "hrt"
    @Published var route = MedCatalog.routes.first ?? "oral"
    @Published var doseText = ""
    @Published var unit = ""
    @Published var colorEnabled = false
    @Published var pickedColor: Color = Color(hex: 0x6A4FA3)   // default lavender
    @Published var notes = ""

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
                    if let c = med.color {
                        colorEnabled = true
                        pickedColor = MedColor.color(fromArgb: c)
                    }
                    notes = med.notes ?? ""
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
        let parsedColor: Int64? = colorEnabled ? MedColor.argb(from: pickedColor) : nil
        let med = NewMedication(
            name: trimmed,
            kind: kind,
            route: route,
            defaultDose: parsedDose,
            defaultDoseUnit: unit.isEmpty ? nil : unit,
            color: parsedColor,
            notes: notes.isEmpty ? nil : notes)
        do {
            if let id = editId {
                try await session.updateMedication(id, med)
                await logTreatmentChanges(session, id: id, new: med)
                status = .done
                return id
            } else {
                let created = try await session.addMedication(med)
                status = .done
                return created.id
            }
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
            VStack(alignment: .leading, spacing: Spacing.l) {
                if vm.loading {
                    ProgressView().tint(palette.primary).frame(maxWidth: .infinity).padding()
                } else {
                    nameCard
                    kindCard
                    routeCard
                    doseCard
                    colorCard
                    notesCard

                    if case let .error(message) = vm.status {
                        ErrorBanner(message: message)
                    } else if let e = vm.error {
                        ErrorBanner(message: e)
                    }

                    saveButton
                }
            }
            .padding(Spacing.l)
        }
        .navigationTitle(vm.isEditing ? "Modifier le traitement" : "Nouveau traitement")
        .task { if let s = app.session { await vm.load(s) } }
    }

    private var nameCard: some View {
        SectionCard {
            Text("Nom").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Nom du traitement", text: $vm.name)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
        }
    }

    private var kindCard: some View {
        SectionCard {
            Text("Type").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            FlowChips {
                ForEach(MedCatalog.kinds, id: \.self) { value in
                    ChoiceChip(label: MedCatalog.kindLabel(value), selected: vm.kind == value) {
                        vm.kind = value
                    }
                }
            }
        }
    }

    private var routeCard: some View {
        SectionCard {
            Text("Voie d'administration").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            FlowChips {
                ForEach(MedCatalog.routes, id: \.self) { value in
                    ChoiceChip(label: MedCatalog.routeLabel(value), selected: vm.route == value) {
                        vm.route = value
                    }
                }
            }
        }
    }

    private var doseCard: some View {
        SectionCard {
            Text("Dose par défaut").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            HStack(spacing: Spacing.m) {
                TextField("0", text: $vm.doseText)
                    .keyboardType(.decimalPad)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                TextField("Unité", text: $vm.unit)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
            }
        }
    }

    private var colorCard: some View {
        SectionCard {
            Toggle(isOn: $vm.colorEnabled) {
                Text("Couleur").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            }
            .tint(palette.primary)
            if vm.colorEnabled {
                ColorPicker(selection: $vm.pickedColor, supportsOpacity: false) {
                    HStack(spacing: Spacing.s) {
                        Circle().fill(vm.pickedColor).frame(width: 22, height: 22)
                            .overlay(Circle().stroke(palette.outlineVariant, lineWidth: 1))
                        Text("Choisir une couleur").font(.eggBody).foregroundStyle(palette.onSurface)
                    }
                }
            }
        }
    }

    private var notesCard: some View {
        SectionCard {
            Text("Notes").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
            TextField("Notes", text: $vm.notes, axis: .vertical)
                .font(.eggBody)
                .foregroundStyle(palette.onSurface)
                .lineLimit(3...6)
        }
    }

    private var saveButton: some View {
        Button {
            save()
        } label: {
            if vm.isSubmitting {
                ProgressView().tint(palette.onPrimary).frame(maxWidth: .infinity)
            } else {
                Text(vm.isEditing ? "Enregistrer" : "Créer").frame(maxWidth: .infinity)
            }
        }
        .glassProminentButton()
        .tint(palette.primary)
        .disabled(trimmedName.isEmpty || vm.isSubmitting)
    }

    private func save() {
        guard !trimmedName.isEmpty, let session = app.session else { return }
        let editing = vm.isEditing
        Task {
            guard let id = await vm.save(session) else { return }
            await app.refreshNotifications()
            dismiss()
            // New medication → chain straight into its schedule setup.
            if !editing { router.push(.addSchedule(medId: id)) }
        }
    }
}

// Medication accent color <-> stored Int. Stored as opaque ARGB (0xFFRRGGBB) so
// Android's `Color(it.toInt())` (which reads ARGB) renders the same swatch from
// the shared DB.
private enum MedColor {
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

// Private layout helper: wraps chips so the routes flow onto multiple lines.
private struct FlowChips: Layout {
    var spacing: CGFloat = Spacing.s

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        var totalWidth: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth > 0, rowWidth + spacing + size.width > maxWidth {
                totalHeight += rowHeight + spacing
                totalWidth = max(totalWidth, rowWidth)
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += (rowWidth > 0 ? spacing : 0) + size.width
                rowHeight = max(rowHeight, size.height)
            }
        }
        totalHeight += rowHeight
        totalWidth = max(totalWidth, rowWidth)
        return CGSize(width: maxWidth.isFinite ? totalWidth : rowWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
