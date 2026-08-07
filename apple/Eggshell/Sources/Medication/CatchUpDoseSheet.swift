import SwiftUI

/// Confirms catching up a missed dose, with the time open to correction before
/// anything is written.
///
/// The proposed instant is the prescribed one plus the user's usual delay, not
/// "now": a dose missed on Tuesday was not taken on Friday afternoon when the
/// user finally opened the app, and defaulting to the current time would write
/// an offset of several days into the very punctuality figures this screen
/// reports. Defaulting to the prescribed minute would be the opposite lie — a
/// perfect record the user never had — so the habit already measured is the
/// honest starting guess, and it stays a guess the user can move.
///
/// The picker refuses future instants: an intake that has not happened yet is
/// not one to catch up.
struct CatchUpDoseSheet: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    let plannedAtMs: Int64
    let meanDelayMin: Int
    let onConfirm: (Int64) -> Void

    @State private var taken = Date()
    @State private var seeded = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Cette prise était prévue le \(MedFormat.dayAndTime(plannedAtMs)). Ajuste l’heure si besoin avant de valider.")
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurfaceVariant)
                }

                Section {
                    DatePicker(
                        "Prise effectuée le",
                        selection: $taken,
                        in: ...Date(),
                        displayedComponents: [.date, .hourAndMinute])
                } footer: {
                    if meanDelayMin != 0 {
                        Text("Heure proposée d’après ton retard moyen (\(meanDelayMin) min).")
                    }
                }
            }
            .navigationTitle("Noter cette prise ?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Noter") {
                        onConfirm(Int64(taken.timeIntervalSince1970 * 1000))
                        dismiss()
                    }
                }
            }
            .onAppear {
                // Seeded once: recomputing on every redraw would drag the
                // picker back to the proposal after each edit the user makes.
                guard !seeded else { return }
                seeded = true
                let proposed = plannedAtMs + Int64(meanDelayMin) * 60_000
                let clamped = min(proposed, Time.nowMs())
                taken = Date(timeIntervalSince1970: Double(clamped) / 1000)
            }
        }
    }
}
