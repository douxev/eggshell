import SwiftUI

/// The sport module's reminders, reached from the module itself — the way a
/// treatment's reminders are reached from the treatment.
///
/// The same entries the reminders hub in Réglages lists under « Sport »: one
/// store, two doors. They are session reminders in the generic
/// non-medication store (`LabReminderKind.sport`), so creating, editing and
/// deleting are exactly what the hub offers, through the same editor.
struct SportRemindersView: View {
    @EnvironmentObject private var labReminders: LabReminderStore
    @Environment(\.palette) private var palette
    @State private var editor: LabEditorTarget?

    private var items: [LabReminder] {
        labReminders.items
            .filter { $0.kind == LabReminderKind.sport }
            .sorted { $0.nextDueMs < $1.nextDueMs }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                EggCard(variant: .low) {
                    Text("Une notification discrète à l'échéance choisie, sans dire de quoi il s'agit. Tu retrouves aussi ces rappels dans Réglages › Rappels.")
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }

                SectionTitleView("SPORT", action: "Ajouter", onAction: {
                    editor = .new(kind: LabReminderKind.sport)
                })

                if items.isEmpty {
                    EmptyStateView(
                        "Aucun rappel de séance.",
                        systemImage: "bell",
                        actionLabel: "Ajouter un rappel de séance"
                    ) { editor = .new(kind: LabReminderKind.sport) }
                } else {
                    ListGroup {
                        ForEach(Array(items.enumerated()), id: \.element.id) { index, reminder in
                            HStack(spacing: 0) {
                                ListRowView(
                                    title: reminder.label,
                                    subtitle: reminder.cadenceLabel,
                                    systemImage: LabReminderKind.systemImage(reminder.kind),
                                    action: { editor = .edit(reminder) })
                                Button {
                                    labReminders.delete(id: reminder.id)
                                    reschedule()
                                } label: {
                                    Image(systemName: "trash")
                                        .font(.system(size: 15))
                                        .foregroundStyle(palette.error)
                                        .padding(.trailing, Metrics.screenMargin)
                                        .frame(minHeight: Metrics.touchTarget)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Supprimer « \(reminder.label) »")
                            }
                            if index != items.count - 1 {
                                Rectangle()
                                    .fill(palette.outlineVariant)
                                    .frame(height: 1)
                                    .padding(.leading, ListRowView.separatorInset)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Rappels de séance")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editor) { target in
            LabReminderEditor(
                target: target,
                onSave: { saved in
                    labReminders.upsert(saved)
                    reschedule()
                    editor = nil
                },
                onCancel: { editor = nil })
        }
    }

    /// Through the store, not straight to the notification centre: `refresh`
    /// also moves overdue dates forward before scheduling them.
    private func reschedule() {
        labReminders.refresh()
    }
}
