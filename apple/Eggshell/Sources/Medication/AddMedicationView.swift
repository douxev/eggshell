import SwiftUI
import TransitionCore

struct AddMedicationView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var router: Router
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var kind = "hrt"
    @State private var route = "oral"
    @State private var doseText = ""
    @State private var unit = ""
    @State private var notes = ""
    @State private var status: FormStatus = .idle

    private let kinds: [(value: String, label: String)] = [
        ("hrt", "THS"),
        ("blocker", "Bloqueur"),
        ("supplement", "Complément"),
        ("other", "Autre"),
    ]

    private let routes: [(value: String, label: String)] = [
        ("oral", "Orale"),
        ("injection_im", "Injection IM"),
        ("injection_sc", "Injection SC"),
        ("transdermal", "Transdermique"),
        ("topical", "Topique"),
        ("sublingual", "Sublinguale"),
        ("other", "Autre"),
    ]

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isSubmitting: Bool {
        if case .submitting = status { return true }
        return false
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                SectionCard {
                    Text("Nom").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    TextField("Nom du médicament", text: $name)
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                }

                SectionCard {
                    Text("Type").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    FlowChips {
                        ForEach(kinds, id: \.value) { item in
                            ChoiceChip(label: item.label, selected: kind == item.value) {
                                kind = item.value
                            }
                        }
                    }
                }

                SectionCard {
                    Text("Voie d'administration").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    FlowChips {
                        ForEach(routes, id: \.value) { item in
                            ChoiceChip(label: item.label, selected: route == item.value) {
                                route = item.value
                            }
                        }
                    }
                }

                SectionCard {
                    Text("Dose par défaut").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    HStack(spacing: Spacing.m) {
                        TextField("0", text: $doseText)
                            .keyboardType(.decimalPad)
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface)
                        TextField("Unité", text: $unit)
                            .font(.eggBody)
                            .foregroundStyle(palette.onSurface)
                    }
                }

                SectionCard {
                    Text("Notes").font(.eggLabel).foregroundStyle(palette.onSurface.opacity(0.6))
                    TextField("Notes", text: $notes, axis: .vertical)
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                        .lineLimit(3...6)
                }

                if case let .error(message) = status {
                    ErrorBanner(message: message)
                }

                Button {
                    save()
                } label: {
                    if isSubmitting {
                        ProgressView().tint(palette.onPrimary).frame(maxWidth: .infinity)
                    } else {
                        Text("Enregistrer").frame(maxWidth: .infinity)
                    }
                }
                .glassProminentButton()
                .tint(palette.primary)
                .disabled(trimmedName.isEmpty || isSubmitting)
            }
            .padding(Spacing.l)
        }
        .navigationTitle("Nouveau médicament")
    }

    private func save() {
        guard !trimmedName.isEmpty, let session = app.session else { return }
        status = .submitting
        let parsedDose = Double(doseText.replacingOccurrences(of: ",", with: "."))
        let newMed = NewMedication(
            name: trimmedName,
            kind: kind,
            route: route,
            defaultDose: parsedDose,
            defaultDoseUnit: unit.isEmpty ? nil : unit,
            color: nil,
            notes: notes.isEmpty ? nil : notes)
        Task {
            do {
                let med = try await session.addMedication(newMed)
                status = .done
                dismiss()
                router.push(.addSchedule(medId: med.id))
            } catch {
                status = .error(describe(error))
            }
        }
    }
}

// Private layout helper: wraps chips so the 7 routes flow onto multiple lines.
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
