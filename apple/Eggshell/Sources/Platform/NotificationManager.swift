import Foundation
import UserNotifications
import TransitionCore

// Local reminders. Unlike Android (AlarmManager + a locked-vault metadata
// mirror), iOS local notifications are pre-scheduled and fire regardless of app
// state — so whenever the vault opens or schedules change we replace all pending
// reminders with the next several occurrences of each active schedule, advancing
// each one via `NextDueCalculator.advance` (iOS caps an app at 64 pending local
// notifications, so we fan out a bounded number).
//
// Content mode/alias/priority is read internally from `NotifPrefs` (the caller
// only passes schedules + a name resolver). The privacy default is generic copy:
// nothing about which medication is due reaches the lock screen.
//
// Category + userInfo wiring matches NotificationCoordinator so its delegate can
// reconstruct the "Pris"/"Passer" action. We DO NOT register categories or set
// the delegate here — NotificationCoordinator.configure() owns that.
enum NotificationManager {
    private static var center: UNUserNotificationCenter { .current() }

    /// How many future occurrences to pre-schedule per active schedule. iOS only
    /// keeps 64 pending notifications; this leaves room across several schedules.
    private static let occurrencesPerSchedule = 16

    static func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Replace all pending medication reminders with the next several occurrences
    /// of each active schedule. `nameFor` resolves a medication id → display name
    /// (used only in `.name` content mode). AppState calls this with exactly
    /// these two arguments.
    ///
    /// With « Grouper les rappels de traitement » on, the same occurrences are
    /// computed and then merged (see `clusterOccurrences`) before anything is
    /// scheduled. Merging happens here, at scheduling time, because iOS
    /// notifications are pre-scheduled and the app is not running when they
    /// fire. Every med change already comes back through this function, and it
    /// first removes every `sched-` request, merged ones included, so a changed
    /// or deleted schedule cannot leave a stale merged request behind.
    static func reschedule(
        schedules: [DoseSchedule],
        nameFor: (Int64) -> String
    ) async {
        guard await requestAuthorization() else { return }
        // Remove ONLY our own medication ("sched-") requests, not every pending
        // notification. A blanket removeAllPendingNotificationRequests() here
        // would also wipe the appointment ("appt-") and lab ("lab-") reminders,
        // which are owned by other schedulers and are NOT re-armed by this
        // medication path — so a routine med action (mark-taken, add/edit) would
        // silently destroy them. Mirror the targeted-removal the lab/appointment
        // schedulers already use.
        let existing = await center.pendingNotificationRequests()
        let staleSched = existing.map(\.identifier).filter { $0.hasPrefix("sched-") }
        if !staleSched.isEmpty { center.removePendingNotificationRequests(withIdentifiers: staleSched) }
        pruneSnoozedGroups(existing, activeIds: Set(schedules.filter(\.active).map(\.id)))

        let mode = NotifPrefs.contentMode
        let grouped = NotifPrefs.groupMedReminders
        let now = Date()
        var occurrences: [MedOccurrence] = []

        for s in schedules where s.active {
            // Seed from the stored next-due, never the past.
            var fire = NextDueCalculator.date(s.nextDueAtMs)
            if fire <= now { fire = now.addingTimeInterval(60) }

            for occurrence in 0..<occurrencesPerSchedule {
                if grouped {
                    occurrences.append(MedOccurrence(schedule: s, fire: fire))
                } else {
                    let content = makeMedContent(
                        for: s, mode: mode, highPriority: NotifPrefs.isMedPriority(s.id), nameFor: nameFor)

                    let comps = Calendar.current.dateComponents(
                        [.year, .month, .day, .hour, .minute], from: fire)
                    let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                    let req = UNNotificationRequest(
                        identifier: "sched-\(s.id)-\(occurrence)",
                        content: content,
                        trigger: trigger)
                    try? await center.add(req)
                }

                // Advance to the next occurrence. `advance` is phase-aware for
                // daily/days_interval and just adds the interval otherwise.
                let nextMs = NextDueCalculator.advance(s, now: fire)
                let next = NextDueCalculator.date(nextMs)
                guard next > fire else { break } // guard against a non-advancing schedule
                fire = next
            }
        }

        guard grouped else { return }
        // Concrete occurrences, never repeating triggers. The med path has no
        // repeating trigger to begin with: daily HH:MM, every-N-hours and
        // every-N-days schedules are all expanded into dated one-shots by
        // NextDueCalculator. And two repeating triggers cannot be merged
        // cleanly anyway: a daily 08:00 and an every-36-hours schedule meet
        // on some days only. Dated one-shots merge exactly, and the next
        // reschedule recomputes everything.
        for (index, members) in clusterOccurrences(occurrences).enumerated() {
            // As on Android: the group is prioritised when any member is.
            let content = makeGroupContent(
                members, mode: mode,
                highPriority: members.contains { NotifPrefs.isMedPriority($0.schedule.id) },
                nameFor: nameFor)
            let comps = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute], from: members[0].fire)
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            // `sched-` like the single ones, so the removal above takes merged
            // requests too.
            let req = UNNotificationRequest(
                identifier: "sched-group-\(index)", content: content, trigger: trigger)
            try? await center.add(req)
        }
    }

    // MARK: - Grouped medication reminders

    /// One dated occurrence of a schedule, before grouping.
    struct MedOccurrence {
        let schedule: DoseSchedule
        let fire: Date
    }

    /// Reminders due less than this long after the first one of a group join it.
    static let groupWindow: TimeInterval = 15 * 60

    /// Merge occurrences into groups.
    ///
    /// The rule, identical on Android: sorted by time, a group starts at the
    /// earliest reminder not yet placed and takes every reminder due less than
    /// `groupWindow` after it; the group fires at that first reminder's time.
    /// Anchored on the first reminder rather than on fixed clock slots, so
    /// 08:10 and 08:20 land together instead of being split by 08:15.
    ///
    /// A schedule appears at most once per group: two occurrences of one
    /// schedule are two doses, and « Tout pris » must not log both of them at
    /// once. The later one goes to the next group.
    static func clusterOccurrences(_ occurrences: [MedOccurrence]) -> [[MedOccurrence]] {
        let sorted = occurrences.sorted {
            ($0.fire, $0.schedule.id) < ($1.fire, $1.schedule.id)
        }
        var groups: [[MedOccurrence]] = []
        for o in sorted {
            let target = groups.firstIndex { group in
                o.fire.timeIntervalSince(group[0].fire) < groupWindow
                    && !group.contains { $0.schedule.id == o.schedule.id }
            }
            if let target { groups[target].append(o) } else { groups.append([o]) }
        }
        return groups
    }

    /// « Plus tard » on a grouped reminder: the same notification, members and
    /// actions included, `NotificationCoordinator.snoozeDelay` from now.
    ///
    /// `snooze-`, not `sched-`: a reschedule (any dose logged, any edit) must
    /// not swallow a reminder the user explicitly put off. Ones whose schedules
    /// went away are pruned by `pruneSnoozedGroups`.
    static func snoozeGroup(_ original: UNNotificationContent) async {
        await snooze(original, identifier: "snooze-group-\(UUID().uuidString)")
    }

    /// « Plus tard » on a single reminder — Android's `SNOOZE_MS` re-show. One
    /// pending snooze per schedule, as Android keeps one alarm per schedule:
    /// putting the same reminder off twice moves it, it does not double it.
    static func snoozeSingle(_ original: UNNotificationContent, scheduleId: Int64) async {
        await snooze(original, identifier: "snooze-sched-\(scheduleId)")
    }

    private static func snooze(_ original: UNNotificationContent, identifier: String) async {
        guard let content = original.mutableCopy() as? UNMutableNotificationContent else { return }
        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: NotificationCoordinator.snoozeDelay, repeats: false)
        let req = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        try? await center.add(req)
    }

    /// Drop snoozed reminders, single or grouped, that name a schedule which
    /// is no longer active — deleted or paused since the tap.
    private static func pruneSnoozedGroups(_ pending: [UNNotificationRequest], activeIds: Set<Int64>) {
        let stale = pending.filter { req in
            guard req.identifier.hasPrefix("snooze-") else { return false }
            let info = req.content.userInfo
            let ids = (info["scheduleIds"] as? [NSNumber])?.map(\.int64Value)
                ?? [(info["scheduleId"] as? NSNumber)?.int64Value].compactMap { $0 }
            return ids.isEmpty || !ids.allSatisfy(activeIds.contains)
        }
        if !stale.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: stale.map(\.identifier))
        }
    }

    /// Title counts, body names only what the content mode allows: the same
    /// per-medication resolution as a single reminder (`medicationLabel`),
    /// otherwise the generic copy.
    private static func makeGroupContent(
        _ members: [MedOccurrence],
        mode: NotifContentMode,
        highPriority: Bool,
        nameFor: (Int64) -> String
    ) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.sound = .default
        content.categoryIdentifier = NotificationCoordinator.groupCategoryId
        content.threadIdentifier = NotificationCoordinator.medicationThreadId
        content.userInfo = [
            "scheduleIds": members.map { NSNumber(value: $0.schedule.id) },
            "medIds": members.map { NSNumber(value: $0.schedule.medicationId) },
        ]
        content.interruptionLevel = highPriority ? .timeSensitive : .passive

        let count = members.count
        content.title = count == 1 ? "Traitement à prendre" : "\(count) traitements à prendre"

        let labels = members.compactMap { medicationLabel(for: $0.schedule, mode: mode, nameFor: nameFor) }
        if labels.isEmpty {
            content.body = "C'est l'heure de votre prise."
        } else {
            // In alias mode a medication without an alias is counted, never named.
            let unnamed = count - labels.count
            var body = labels.joined(separator: ", ")
            if unnamed == 1 { body += " et 1 autre" }
            if unnamed > 1 { body += " et \(unnamed) autres" }
            content.body = body
        }
        return content
    }

    /// How many upcoming occurrences of an every-N-days reminder to keep
    /// pending. iOS never tells the app a notification fired, so the dates are
    /// only moved on when the app runs (`LabReminderStore.refresh`); a few
    /// occurrences ahead keep the reminder alive through weeks of the app not
    /// being opened, without eating into the 64-request budget the medication
    /// reminders need.
    private static let labOccurrences = 3

    /// Lab/photo/voice/journal/dream/sport reminders. The user's free-text
    /// label is deliberately NOT included — the lock screen would otherwise
    /// show "Estradiol", "T4 libre"… in the clear. Only the category picks a
    /// (still innocuous) title; the body stays generic. Same stance as Android.
    ///
    /// Every pending `lab-` request is replaced on each call (idempotent).
    /// A daily reminder is one repeating trigger, which iOS re-fires by itself.
    /// An every-N-days reminder gets its next few occurrences, computed from
    /// its stored phase — never "now + 1 min": a reminder whose date has passed
    /// already fired then (or was missed while the phone was off), and
    /// re-firing it on every edit or launch is noise, not a reminder.
    static func scheduleLabReminders(_ items: [LabReminder]) async {
        // Drop only the lab identifiers we own, leaving med reminders intact.
        // First, and without asking for permission: a deleted or disabled
        // reminder must stop even if notifications were refused since.
        let pending = await center.pendingNotificationRequests()
        let stale = pending.map(\.identifier).filter { $0.hasPrefix("lab-") }
        if !stale.isEmpty { center.removePendingNotificationRequests(withIdentifiers: stale) }

        let active = items.filter(\.enabled)
        guard !active.isEmpty else { return }
        guard await requestAuthorization() else { return }

        let calendar = Calendar.current
        let now = Date()

        for r in active {
            let content = UNMutableNotificationContent()
            content.sound = .default
            content.title = labTitle(for: r.kind)
            content.body = "Un rappel est arrivé à échéance."
            content.interruptionLevel = r.isPriority ? .timeSensitive : .passive

            if let hour = r.dailyHour, let minute = r.dailyMinute {
                let trigger = UNCalendarNotificationTrigger(
                    dateMatching: DateComponents(hour: hour, minute: minute), repeats: true)
                let req = UNNotificationRequest(identifier: "lab-\(r.id)-daily", content: content, trigger: trigger)
                try? await center.add(req)
                continue
            }

            var fire = r.nextOccurrence(after: now, calendar: calendar)
            for occurrence in 0..<labOccurrences {
                let comps = calendar.dateComponents(
                    [.year, .month, .day, .hour, .minute], from: fire)
                let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                let req = UNNotificationRequest(
                    identifier: "lab-\(r.id)-\(occurrence)", content: content, trigger: trigger)
                try? await center.add(req)
                guard let next = calendar.date(byAdding: .day, value: max(1, r.intervalDays), to: fire),
                      next > fire else { break }
                fire = next
            }
        }
    }

    /// Per-category notification title, mirroring android ReminderNotifications
    /// .showLab. Innocuous by design — never the user's own label.
    private static func labTitle(for kind: String) -> String {
        switch kind {
        case LabReminderKind.photo:   return "Photo à prendre"
        case LabReminderKind.voice:   return "Enregistrement à faire"
        case LabReminderKind.journal: return "Moment journal"
        case LabReminderKind.lab:     return "Analyse à prévoir"
        // Same wording as Android's sport_reminder_title: says nothing about
        // what the session is.
        case LabReminderKind.sport:   return "C'est l'heure"
        default:                      return "Rappel"
        }
    }

    /// One pending notification per appointment that has a future reminder.
    /// Generic copy: the lock screen never shows which appointment / professional.
    /// Replaces all `appt-` notifications on each call (idempotent reschedule);
    /// call on unlock and after any appointment add/edit/delete.
    static func scheduleAppointmentReminders(_ items: [Appointment]) async {
        guard await requestAuthorization() else { return }
        let pending = await center.pendingNotificationRequests()
        let stale = pending.map(\.identifier).filter { $0.hasPrefix("appt-") }
        if !stale.isEmpty { center.removePendingNotificationRequests(withIdentifiers: stale) }

        let highPriority = NotifPrefs.highPriority
        let now = Date()

        for a in items {
            guard let reminderMs = a.reminderAtMs else { continue }
            let fire = Date(timeIntervalSince1970: Double(reminderMs) / 1000)
            guard fire > now else { continue }

            let content = UNMutableNotificationContent()
            content.sound = .default
            content.title = "Rappel de rendez-vous"
            content.body = "Tu as un rendez-vous à venir."
            content.interruptionLevel = highPriority ? .timeSensitive : .passive

            let comps = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute], from: fire)
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
            let req = UNNotificationRequest(identifier: "appt-\(a.id)", content: content, trigger: trigger)
            try? await center.add(req)
        }
    }

    static func cancelAll() {
        center.removeAllPendingNotificationRequests()
    }

    // MARK: - Content

    private static func makeMedContent(
        for s: DoseSchedule,
        mode: NotifContentMode,
        highPriority: Bool,
        nameFor: (Int64) -> String
    ) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.sound = .default
        content.categoryIdentifier = NotificationCoordinator.categoryId
        content.threadIdentifier = NotificationCoordinator.medicationThreadId
        content.userInfo = [
            "scheduleId": NSNumber(value: s.id),
            "medId": NSNumber(value: s.medicationId),
        ]
        content.interruptionLevel = highPriority ? .timeSensitive : .passive

        // Generic mode, or alias mode with no alias set: « Rappel » — never
        // the real name.
        content.title = medicationLabel(for: s, mode: mode, nameFor: nameFor) ?? "Rappel"
        content.body = "C'est l'heure de votre prise."
        return content
    }

    /// What a reminder may call this schedule's medication, or nil when the
    /// content mode allows nothing. Shared by single and grouped reminders so
    /// the two can never disagree about what reaches a lock screen.
    ///
    /// The reminder's own free text ("Aller chercher le traitement") wins
    /// over the med name/alias when the mode allows showing anything at
    /// all — same resolution as Android's ScheduleRepository.
    private static func medicationLabel(
        for s: DoseSchedule,
        mode: NotifContentMode,
        nameFor: (Int64) -> String
    ) -> String? {
        let customLabel = s.label?.isEmpty == false ? s.label : nil
        switch mode {
        case .generic:
            // Privacy default: nothing identifying, not even the custom label.
            return nil
        case .name:
            return customLabel ?? nameFor(s.medicationId)
        case .alias:
            return customLabel ?? NotifPrefs.alias(for: s.medicationId)
        }
    }
}
