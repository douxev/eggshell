import SwiftUI
import TransitionCore

@MainActor
final class SportActivitiesViewModel: ObservableObject {
    @Published var activities: [SportActivity] = []

    func load(session: VaultService) async {
        activities = await SportStore.activities(session, includeArchived: true)
    }

    func add(session: VaultService, name: String, kind: SportKind) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        _ = try? await session.addSportActivity(
            NewSportActivity(name: trimmed, kind: kind.rawValue, color: nil), nowMs: Time.nowMs())
        await load(session: session)
    }

    func setArchived(session: VaultService, id: Int64, archived: Bool) async {
        try? await session.setSportActivityArchived(id, archived)
        await load(session: session)
    }

    func delete(session: VaultService, id: Int64) async {
        try? await session.deleteSportActivity(id)
        await load(session: session)
    }
}

/// The module's settings: the pedometer and its goal, then the activity
/// catalogue.
///
/// The pedometer lives here rather than on the dashboard because it is a
/// setting one flips once. Archiving is offered first and delete second,
/// because they differ in a way that matters: archiving hides a type and
/// leaves every session pointing at it, while deleting detaches them
/// permanently. The core makes deletion safe — the sessions survive with no
/// type rather than cascading away — but "safe" is not "reversible", so the
/// confirmation says exactly what will happen.
struct SportActivitiesView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var sportPrefs: SportPrefs
    @Environment(\.palette) private var palette
    @StateObject private var vm = SportActivitiesViewModel()
    @ObservedObject private var health = HealthKitImporter.shared

    @State private var newName = ""
    @State private var newKind: SportKind = .cardio
    @State private var customGoal = ""
    @State private var pendingDelete: SportActivity?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Metrics.blockGap) {
                pedometerCard

                // Only in a build that carries the HealthKit entitlement: a
                // switch that cannot work is worse than no switch.
                if HealthKitImporter.isEnabledInBuild {
                    healthCard
                }

                SectionTitleView("TYPES D'ACTIVITÉ")
                    .padding(.top, Spacing.xs)

                EggCard(variant: .low, spacing: Spacing.s) {
                    Text("Nouveau type")
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    TextField("Nom", text: $newName)
                        .font(.eggBody)
                        .foregroundStyle(palette.onSurface)
                        .padding(.horizontal, Spacing.m)
                        .padding(.vertical, 10)
                        .frame(minHeight: Metrics.touchTarget)
                        .background(
                            palette.surfaceContainerHigh,
                            in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                    MicroLabel("CATÉGORIE")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(SportKind.allCases) { kind in
                                ChoiceChip(label: kind.label, selected: newKind == kind) { newKind = kind }
                            }
                        }
                    }
                    Button("Enregistrer") {
                        guard let session = app.session else { return }
                        let name = newName
                        newName = ""
                        Task { await vm.add(session: session, name: name, kind: newKind) }
                    }
                    .font(EggFont.label)
                    .foregroundStyle(palette.primary)
                    .buttonStyle(.plain)
                    .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    .frame(minHeight: Metrics.touchTarget, alignment: .leading)
                }

                ForEach(vm.activities, id: \.id) { activity in
                    EggCard(variant: activity.archived ? .outlined : .low, paddingH: 18, paddingV: 14, spacing: 0) {
                        HStack(spacing: Spacing.s) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(activity.name)
                                    .font(EggFont.titleS)
                                    .foregroundStyle(palette.onSurface)
                                Text(SportKind.of(activity.kind).label)
                                    .font(EggFont.bodyS)
                                    .foregroundStyle(palette.onSurfaceVariant)
                            }
                            Spacer(minLength: Spacing.s)
                            Button(activity.archived ? "Réactiver" : "Archiver") {
                                guard let session = app.session else { return }
                                Task {
                                    await vm.setArchived(
                                        session: session, id: activity.id, archived: !activity.archived)
                                }
                            }
                            .font(EggFont.label)
                            .foregroundStyle(palette.primary)
                            .buttonStyle(.plain)
                            Button {
                                pendingDelete = activity
                            } label: {
                                Image(systemName: "trash")
                                    .font(.system(size: 15))
                                    .foregroundStyle(palette.error)
                                    .frame(minWidth: Metrics.touchTarget, minHeight: Metrics.touchTarget)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Supprimer « \(activity.name) »")
                        }
                    }
                }
            }
            .padding(.horizontal, Metrics.screenMargin)
            .padding(.bottom, Metrics.blockGap)
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle("Types et podomètre")
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            "Supprimer ce type ?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            Button("Supprimer", role: .destructive) {
                guard let session = app.session, let target = pendingDelete else { return }
                pendingDelete = nil
                Task { await vm.delete(session: session, id: target.id) }
            }
            Button("Annuler", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("Tes séances sont conservées : elles perdent seulement leur type. Archiver le masque sans rien détacher.")
        }
        .task {
            guard let session = app.session else { return }
            await vm.load(session: session)
        }
        .onAppear {
            if !SportPrefs.presets.contains(sportPrefs.dailyGoal) {
                customGoal = String(sportPrefs.dailyGoal)
            }
        }
    }

    /// The pedometer switch and, once it is on, the daily goal: four usual
    /// values as chips and a field for any other. The field applies as soon as
    /// it holds a number the prefs accept, so there is no save button to
    /// forget. Motion access is requested by the system at the first read,
    /// which is the consequence of a choice the user just made, not a greeting.
    private var pedometerCard: some View {
        let available = StepCounter.isAvailable
        let denied = StepCounter.isDenied
        return EggCard(variant: .low, spacing: Spacing.m) {
            Toggle(isOn: Binding(
                get: { sportPrefs.pedometer && available },
                set: { on in
                    sportPrefs.pedometer = on
                    // Read at once: the Motion prompt follows the switch the
                    // user just flipped, rather than surprising them on the
                    // next visit, and the last seven days land straight away.
                    guard on, let session = app.session else { return }
                    Task { await StepCounter().sync(into: session) }
                }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Compter mes pas")
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    Text(pedometerSubtitle(available: available, denied: denied))
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(palette.primary)
            .disabled(!available)

            if sportPrefs.pedometer && available {
                MicroLabel("OBJECTIF QUOTIDIEN")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SportPrefs.presets, id: \.self) { preset in
                            ChoiceChip(
                                label: "\(SportFormat.integer(preset)) pas",
                                selected: sportPrefs.dailyGoal == preset
                            ) {
                                sportPrefs.dailyGoal = preset
                                customGoal = ""
                            }
                        }
                    }
                }
                TextField("Autre objectif (pas)", text: $customGoal)
                    .keyboardType(.numberPad)
                    .font(.eggBody)
                    .foregroundStyle(palette.onSurface)
                    .padding(.horizontal, Spacing.m)
                    .padding(.vertical, 10)
                    .frame(minHeight: Metrics.touchTarget)
                    .background(
                        palette.surfaceContainerHigh,
                        in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                    .onChange(of: customGoal) { _, typed in
                        let digits = String(typed.filter(\.isNumber).prefix(5))
                        if digits != typed { customGoal = digits; return }
                        if let value = Int(digits), (SportPrefs.minGoal...SportPrefs.maxGoal).contains(value) {
                            sportPrefs.dailyGoal = value
                        }
                    }
            }
        }
    }

    // MARK: - Santé

    /// « Importer depuis Santé (Apple) »: off until the user turns it on, and
    /// turning it on is what asks Santé for read access. After that it runs by
    /// itself on unlock and on opening the module; the button is for now.
    private var healthCard: some View {
        let available = HealthKitImporter.isAvailable
        return EggCard(variant: .low, spacing: Spacing.m) {
            Toggle(isOn: Binding(
                get: { sportPrefs.healthImport && available },
                set: { on in
                    sportPrefs.healthImport = on
                    guard on else { return }
                    Task {
                        guard await health.requestAccess(), let session = app.session else { return }
                        await health.sync(session: session)
                    }
                }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Importer depuis Santé (Apple)")
                        .font(EggFont.titleS)
                        .foregroundStyle(palette.onSurface)
                    Text(available
                         ? "Tes entraînements, tes pas et ta fréquence cardiaque, lus dans Santé. Lecture seule : rien n'est écrit dans Santé, et rien ne quitte le téléphone — tout va dans ton coffre chiffré."
                         : "Santé n'est pas disponible sur cet appareil.")
                        .font(EggFont.bodyS)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(palette.primary)
            .disabled(!available)

            if sportPrefs.healthImport && available {
                Text(healthStatusLine)
                    .font(EggFont.bodyS)
                    .foregroundStyle(healthStatusIsError ? palette.error : palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Synchroniser maintenant") {
                    guard let session = app.session else { return }
                    Task { await health.sync(session: session) }
                }
                .font(EggFont.label)
                .foregroundStyle(palette.primary)
                .buttonStyle(.plain)
                .disabled(health.status == .syncing)
                .frame(minHeight: Metrics.touchTarget, alignment: .leading)
            }
        }
    }

    private var healthStatusIsError: Bool {
        if case .failed = health.status { return true }
        return false
    }

    private var healthStatusLine: String {
        switch health.status {
        case .syncing:
            return "Import en cours…"
        case .needsAuthorization:
            return "Santé n'a pas encore donné son accord : désactive puis réactive l'option pour le demander."
        case .failed(let reason):
            return "L'import n'a pas abouti : \(reason). Il sera retenté au prochain déverrouillage."
        case .done(let sessions, let days):
            let what = "\(sessions) séance\(sessions > 1 ? "s" : "") ajoutée\(sessions > 1 ? "s" : ""), \(days) jour\(days > 1 ? "s" : "") de pas lu\(days > 1 ? "s" : "")"
            return "Dernier import : \(Self.lastSyncLabel()) · \(what)."
        case .idle:
            // Santé lets nobody see whether reading was refused — a refusal
            // just looks like no data — so the line says where to check.
            if SportPrefs.healthLastSync() != nil {
                return "Dernier import : \(Self.lastSyncLabel()). Rien n'arrive ? Vérifie l'accès dans Santé › Partage › Apps."
            }
            return "Pas encore importé. Rien n'arrive ? Vérifie l'accès dans Santé › Partage › Apps."
        }
    }

    private static func lastSyncLabel() -> String {
        guard let date = SportPrefs.healthLastSync() else { return "jamais" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: date)
    }

    private func pedometerSubtitle(available: Bool, denied: Bool) -> String {
        if !available { return "Cet appareil n'a pas de capteur de pas." }
        if denied {
            return "L'accès au mouvement est refusé. Autorise-le dans Réglages › Confidentialité › Mouvement et forme."
        }
        return "Utilise le capteur de l'appareil. Rien ne sort du téléphone, et le comptage s'arrête dès que tu désactives cette option."
    }
}
