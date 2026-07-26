import Foundation
import TransitionCore

// Reads the vault and produces the `ReportDocument` the renderer draws, plus the
// per-module volumes the export screen quotes under each toggle.
//
// The rule that governs the whole file is that **no figure is invented**. A
// section with nothing to say is dropped rather than printed above an « Aucune
// donnée » line, an estimate says it is an estimate, and a statistic that cannot
// be computed is replaced by the sentence explaining why — a paper handed to a
// doctor is the last place for a reassuring zero.
//
// Mirror of the Android `data/pdf/ReportBuilder.kt`. iOS carries its French copy
// inline, like the other 67 files.

struct DoctorReportBuilder {
    private let session: VaultService
    /// Display unit per analyte, snapshotted on the main actor before the build:
    /// `HormoneUnitStore` is main-actor bound and this runs off it.
    private let units: [String: String]
    private let calendar: Calendar
    private let f: ReportFormats

    init(
        session: VaultService,
        units: [String: String],
        calendar: Calendar = .current,
        formats: ReportFormats = ReportFormats()
    ) {
        self.session = session
        self.units = units
        self.calendar = calendar
        self.f = formats
    }

    // MARK: - Document

    func build(range: ReportRange, modules: ReportModules) async -> ReportDocument {
        let from = range.fromMs
        let to = range.toMs

        var sections: [ReportSection] = []
        if modules.medications {
            if let s = await treatmentsSection() { sections.append(s) }
            if let s = await changesSection(from: from, to: to) { sections.append(s) }
            if let s = await regularitySection(from: from, to: to) { sections.append(s) }
        }
        if modules.hormones,
           let s = await hormonesSection(from: from, to: to, markers: modules.medications) {
            sections.append(s)
        }
        if modules.weight, let s = await weightSection(from: from, to: to) { sections.append(s) }
        if modules.feel, let s = await feelingsSection(range: range) { sections.append(s) }
        if modules.bleeding, let s = await bleedingSection(from: from, to: to) { sections.append(s) }
        if modules.voice, let s = await voiceSection(from: from, to: to) { sections.append(s) }
        if modules.questions, let s = await questionsSection() { sections.append(s) }
        if modules.photos, let s = await photosSection(from: from, to: to) { sections.append(s) }

        return ReportDocument(
            title: title(from: from, to: to),
            subtitle: "\(plural(range.days, "jour", "jours")) · document édité le "
                + f.prose(Time.nowMs())
                + " · établi à partir des saisies de la personne concernée",
            identity: await identity(),
            sections: sections,
            disclaimerLead: "Nature de ce document.",
            disclaimerBody:
                "Toutes les données sont saisies par la personne concernée dans une application "
                + "personnelle ; elles ne sont ni vérifiées ni horodatées par un tiers. Les analyses "
                + "biologiques sont recopiées ou lues automatiquement à partir des comptes rendus de "
                + "laboratoire, dont les originaux font foi. Ce document est une aide à la "
                + "consultation, il ne remplace ni un examen clinique ni un compte rendu de "
                + "laboratoire.",
            fileName: "suivi-\(f.iso(from))_\(f.iso(to)).pdf")
    }

    /// The boxed header of §7.4.2, read from the encrypted setting store the two
    /// fields are edited into on the export screen.
    ///
    /// Both or nothing. A box carrying one field would be the half-filled box the
    /// handoff forbids (« jamais de champ vide »), so a missing name — or a date
    /// that does not parse back — removes the block entirely rather than leaving
    /// a gap for someone to fill in by hand.
    ///
    /// The date is stored ISO-8601 and reformatted here: the page reads
    /// « 3 février 1996 » in the document's own locale, never the string on disk.
    private func identity() async -> ReportIdentity? {
        let name = ReportIdentityFields.name(await setting(ReportIdentityFields.personKey))
        let birth = ReportIdentityFields.parse(await setting(ReportIdentityFields.birthKey))
        guard let name, let birth else { return nil }
        return ReportIdentity(name: name, birthDate: ReportIdentityFields.long(birth, f))
    }

    /// An unreadable setting is an absent setting — the document is built
    /// regardless, minus the block that needed it.
    private func setting(_ key: String) async -> String? {
        (try? await session.getSetting(key)) ?? nil
    }

    private func title(from: Int64, to: Int64) -> String {
        let sameYear = calendar.component(.year, from: date(from))
            == calendar.component(.year, from: date(to))
        let start = sameYear ? f.proseNoYear(from) : f.prose(from)
        return "Période du \(start) au \(f.prose(to))"
    }

    // MARK: - Volumes (the live subtitles of §6.12.4)

    /// Real counts over the period. The subtitles announce a volume, so they must
    /// never announce one the document cannot deliver.
    func volumes(range: ReportRange) async -> ReportVolumes {
        var out = ReportVolumes()
        let from = range.fromMs
        let to = range.toMs

        let meds = (try? await session.listMedications(includeArchived: false)) ?? []
        out.molecules = meds.count
        // A skipped dose is a declared « je ne l'ai pas prise » — §3 drops it
        // from « Doses notées » (see `PlannedDoses.window`), so the subtitle
        // that announces the volume has to drop it too. Counting it here
        // promised the document more intakes than it goes on to print.
        let doses = ((try? await session.listDoseEventsBetween(fromMs: from, toMs: to)) ?? [])
            .filter { $0.status != "skipped" }
        out.doses = doses.count

        let analytes = ((try? await session.distinctHormones()) ?? [])
            .filter { $0 != HormoneCatalog.weight }
        var labs = 0
        for analyte in analytes {
            labs += (await measurements(analyte, from: from, to: to)).count
        }
        out.labs = labs
        out.weights = (await measurements(HormoneCatalog.weight, from: from, to: to)).count

        let journal = ((try? await session.listJournalEntries(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { range.contains($0.atMs) }
        out.feelEntries = journal.count

        out.bleedingDays = ((try? await session.listBleedingEntries(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { range.contains($0.atMs) }
            .count

        out.clips = ((try? await session.listVoiceClips(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { range.contains($0.atMs) && $0.pitchHz != nil }
            .count

        out.photos = ((try? await session.listPhotoRecords(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { range.contains($0.atMs) }
            .count

        if let next = await nextAppointment() {
            out.questions = appointmentTodoItems(next.todo).count
            out.questionsDate = f.proseNoYear(next.atMs)
        }
        return out
    }

    /// The bounds the period pills read: the last consultation already past, the
    /// oldest treatment sheet — and, for « Tout », the oldest datum of any kind.
    func anchors() async -> (lastVisitMs: Int64?, treatmentStartMs: Int64?, earliestMs: Int64?) {
        let now = Time.nowMs()
        let past = ((try? await session.listAppointments(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { $0.atMs <= now }
            .map(\.atMs)
            .max()
        let start = ((try? await session.listMedications(includeArchived: true)) ?? [])
            .map(\.createdAtMs)
            .min()
        return (past, start, await earliestDatum(now: now))
    }

    /// The oldest instant the vault holds, across **every** kind of record.
    ///
    /// This is what « Tout » has to start from, and nothing else will do. A floor
    /// of zero dates the H1, the subtitle, the recap line and the file name from
    /// 1 January 1970; a floor read off the medication sheets alone drops a lab
    /// result — or a back-dated intake — that predates the first prescription,
    /// which is a silent truncation in a document handed to a doctor.
    ///
    /// `nil` means the vault holds nothing at all.
    func earliestDatum(now: Int64 = Time.nowMs()) async -> Int64? {
        var stamps: [Int64] = []
        stamps += ((try? await session.listMedications(includeArchived: true)) ?? [])
            .map(\.createdAtMs)
        // Intakes are the record most often back-dated — « j'ai oublié de la
        // noter hier » — so they are exactly what a bound taken from the
        // medication sheets would cut away.
        stamps += ((try? await session.listDoseEventsBetween(fromMs: 0, toMs: now)) ?? [])
            .map(\.takenAtMs)
        stamps += ((try? await session.listTreatmentChanges(fromMs: 0, toMs: now)) ?? [])
            .map(\.atMs)
        for id in ((try? await session.distinctHormones()) ?? []) {
            stamps += ((try? await session.listHormoneMeasurements(
                hormone: id, offset: 0, limit: Self.measureLimit)) ?? []).map(\.atMs)
        }
        stamps += ((try? await session.listJournalEntries(offset: 0, limit: Self.entryLimit)) ?? [])
            .map(\.atMs)
        stamps += ((try? await session.listBleedingEntries(offset: 0, limit: Self.entryLimit)) ?? [])
            .map(\.atMs)
        stamps += ((try? await session.listVoiceClips(offset: 0, limit: Self.entryLimit)) ?? [])
            .map(\.atMs)
        stamps += ((try? await session.listPhotoRecords(offset: 0, limit: Self.entryLimit)) ?? [])
            .map(\.atMs)
        stamps += ((try? await session.listAppointments(offset: 0, limit: Self.entryLimit)) ?? [])
            .map(\.atMs)
        return stamps.min()
    }

    // MARK: - 1 — Traitements en cours

    private func treatmentsSection() async -> ReportSection? {
        let meds = (try? await session.listMedications(includeArchived: false)) ?? []
        guard !meds.isEmpty else { return nil }
        var rows: [[String]] = []
        for med in meds {
            let plan = (try? await session.listSchedulesForMedication(med.id, includeInactive: false)) ?? []
            rows.append([
                med.name,
                med.defaultDose.map { f.value($0, med.defaultDoseUnit) } ?? "",
                MedCatalog.routeLabel(med.route),
                rhythm(plan),
                f.slashed(med.createdAtMs),
            ])
        }
        return ReportSection(
            title: "TRAITEMENTS EN COURS",
            blocks: [
                .table(
                    columns: [
                        ReportColumn(title: "MOLÉCULE", weight: 1.45, strong: true),
                        ReportColumn(title: "DOSE", weight: 0.70),
                        ReportColumn(title: "VOIE", weight: 0.95),
                        ReportColumn(title: "RYTHME", weight: 1.15),
                        ReportColumn(title: "DEPUIS", weight: 0.85, alignRight: true),
                    ],
                    rows: rows,
                    rowPad: ReportGeo.px(11)),
                .paragraph(
                    text: "La colonne « depuis » donne la date de création de la fiche dans "
                        + "l'application, qui peut être postérieure au début réel du traitement.",
                    note: true),
            ])
    }

    /// « 2 × / j — 8 h, 20 h ». There is no stored text for this: the cadence is
    /// read back off the schedules, which is also why a treatment with no
    /// reminder says so instead of showing a blank cell.
    private func rhythm(_ plan: [DoseSchedule]) -> String {
        guard !plan.isEmpty else { return "Aucun rappel programmé" }
        let daily = plan.filter { $0.kind == "daily" }
        if daily.count == plan.count {
            let times = daily
                .sorted {
                    let a = (Int($0.dailyHour ?? 0), Int($0.dailyMinute ?? 0))
                    let b = (Int($1.dailyHour ?? 0), Int($1.dailyMinute ?? 0))
                    return a.0 != b.0 ? a.0 < b.0 : a.1 < b.1
                }
                .map { clock($0) }
                .joined(separator: ", ")
            return "\(daily.count) × / j — \(times)"
        }
        return plan.map { one -> String in
            switch one.kind {
            case "daily":
                return "1 × / j — \(clock(one))"
            case "days_interval":
                return "1 × tous les \(Int(one.intervalDays ?? 1)) j — \(clock(one))"
            case "interval":
                let minutes = Int(one.intervalMinutes ?? 0)
                if minutes > 0 && minutes % 60 == 0 { return "toutes les \(minutes / 60) h" }
                return "toutes les \(minutes) min"
            default:
                return "Aucun rappel programmé"
            }
        }
        .joined(separator: " · ")
    }

    private func clock(_ schedule: DoseSchedule) -> String {
        let hour = Int(schedule.dailyHour ?? 0)
        let minute = Int(schedule.dailyMinute ?? 0)
        return minute == 0 ? "\(hour) h" : String(format: "%d h %02d", hour, minute)
    }

    // MARK: - 2 — Modifications sur la période

    private func changesSection(from: Int64, to: Int64) async -> ReportSection? {
        let changes = (try? await session.listTreatmentChanges(fromMs: from, toMs: to)) ?? []
        guard !changes.isEmpty else { return nil }
        let meds = (try? await session.listMedications(includeArchived: true)) ?? []
        var names: [Int64: String] = [:]
        for med in meds { names[med.id] = med.name }
        let rows = changes
            .sorted { $0.atMs > $1.atMs }
            .map { change in
                (date: f.slashed(change.atMs),
                 text: sentence(change, name: names[change.medicationId] ?? ""))
            }
        return ReportSection(
            title: "MODIFICATIONS SUR LA PÉRIODE",
            blocks: [
                .datedList(rows),
                .paragraph(
                    text: "Les changements d'horaire de prise ne sont pas encore historisés par "
                        + "l'application : seules les évolutions de dose, d'unité et de voie "
                        + "figurent ici.",
                    note: true),
            ])
    }

    /// The sentences are composed here — the core stores fields, not prose.
    private func sentence(_ change: TreatmentChange, name: String) -> String {
        let unset = "non renseigné"
        func numeric(_ raw: String?) -> String {
            if let raw, let value = Double(raw) { return f.number(value) }
            if let raw, !raw.isEmpty { return raw }
            return unset
        }
        func plain(_ raw: String?) -> String {
            guard let raw, !raw.isEmpty else { return unset }
            return raw
        }
        func route(_ raw: String?) -> String {
            guard let raw, !raw.isEmpty else { return unset }
            return MedCatalog.routeLabel(raw)
        }
        switch change.field {
        case "dose":
            return "\(name) : dose passée de \(numeric(change.oldValue)) à \(numeric(change.newValue))."
        case "unit":
            return "\(name) : unité de dose passée de \(plain(change.oldValue)) à \(plain(change.newValue))."
        case "route":
            return "\(name) : voie d'administration passée de \(route(change.oldValue)) à \(route(change.newValue))."
        default:
            let field = change.field.replacingOccurrences(of: "_", with: " ")
            return "\(name) : \(field) passé de \(plain(change.oldValue)) à \(plain(change.newValue))."
        }
    }

    // MARK: - 3 — Régularité des prises

    private func regularitySection(from: Int64, to: Int64) async -> ReportSection? {
        let window = await PlannedDoses.window(session: session, fromMs: from, toMs: to)
        let occurrences = window.occurrences
        // Intakes measured against a projected occurrence carry a real offset,
        // so they belong in the figures; only what no schedule explains at all
        // is set aside and disclosed.
        let offGrid = window.offGrid
        let adHoc = window.withoutPlannedTime
        guard !occurrences.isEmpty || !offGrid.isEmpty || !adHoc.isEmpty else { return nil }

        let points = window.points
        let stats = window.stats
        let paired = points.filter { $0.deltaMin != nil }

        var rows: [ReportStat] = []
        rows.append(ReportStat(label: "Doses prévues", value: f.integer(occurrences.count)))
        rows.append(ReportStat(
            label: "Doses notées",
            value: occurrences.isEmpty
                ? f.integer(offGrid.count + adHoc.count)
                : "\(stats.loggedCount) · \(stats.adherencePercent) %"))
        rows.append(ReportStat(label: "Doses oubliées", value: f.integer(stats.missedCount)))
        if !paired.isEmpty {
            rows.append(ReportStat(label: "Retard moyen", value: delay(stats.meanDelayMin)))
            rows.append(ReportStat(
                label: "Prises > 2 h de retard",
                value: f.integer(paired.filter { ($0.deltaMin ?? 0) > Self.twoHoursMin }.count)))
        }

        // Only the most recent doses are plotted: past a certain density the dots
        // stop being readable, and the caption names the number shown.
        let shown = Array(points.suffix(Self.punctualityDots))
        let chart = shown.isEmpty ? nil : punctuality(shown)

        var note = "Les doses prévues sont rejouées depuis les schémas d'administration "
            + "enregistrés : c'est une estimation, pas un relevé. L'axe se cale sur le plus grand "
            + "retard de la période."
        if paired.isEmpty {
            note += " Aucune prise de la période n'a pu être rapprochée d'une heure prévue : les "
                + "écarts ne sont pas calculés, et aucun pourcentage n'est avancé."
        }
        if !adHoc.isEmpty {
            note += adHoc.count == 1
                ? " 1 prise notée sans heure prévue, exclue du calcul des écarts."
                : " \(adHoc.count) prises notées sans heure prévue, exclues du calcul des écarts."
        }

        return ReportSection(
            title: "RÉGULARITÉ DES PRISES",
            blocks: [
                .statChart(ReportStatChart(
                    stats: rows,
                    caption: chart == nil
                        ? nil
                        : "ÉCART À L'HEURE PRÉVUE, \(shown.count) DERNIÈRES PRISES",
                    chart: nil,
                    punctuality: chart,
                    note: note)),
            ])
    }

    private func punctuality(_ points: [DosePoint]) -> ReportPunctualitySpec {
        let axis = Punctuality.axis(points)
        let first = points.map(\.atMs).min() ?? 0
        let last = points.map(\.atMs).max() ?? first
        return ReportPunctualitySpec(
            width: ReportGeo.chartW,
            points: points,
            axis: axis,
            fromMs: first,
            toMs: last > first ? last : first + 1,
            tickLabels: axis.ticks.map { tick(Punctuality.axisLabel($0)) },
            missedLabel: "oubliées · \(axis.missedCount)")
    }

    /// Every gradation the axis can produce. `hoursMinutes` is reachable as soon
    /// as the period's worst delay quantises the axis top to a value whose half
    /// is not a whole hour — 90 minutes, say — and a blank gradation on a chart
    /// handed to a doctor is worse than a clumsy one, so no `default` here.
    private func tick(_ label: DeltaLabel) -> String {
        switch label {
        case .onTime:          return "à l'heure"
        case .missed:          return "oubliée"
        case .hours(let h):    return "+\(h) h"
        case .minutes(let m):  return "+\(m) min"
        case .early(let m):    return "−\(m) min"
        case .hoursMinutes(let h, let m):
            return m == 0 ? "+\(h) h" : "+\(h) h \(m)"
        }
    }

    private func delay(_ minutes: Int) -> String {
        let magnitude = abs(minutes)
        let body = magnitude >= 60
            ? String(format: "%d h %02d", magnitude / 60, magnitude % 60)
            : "\(magnitude) min"
        // The sign is part of the reading: a dose taken early is not a delay.
        return (minutes < 0 ? "−" : "+") + body
    }

    // MARK: - 4 — Taux hormonaux

    private struct Sample {
        let atMs: Int64
        let value: Double
        let unit: String
        let raw: HormoneMeasurement
    }

    private struct Analyte {
        let id: String
        let label: String
        let unit: String
        let points: [Sample]
    }

    private func measurements(_ id: String, from: Int64, to: Int64) async -> [HormoneMeasurement] {
        let raw = (try? await session.listHormoneMeasurements(
            hormone: id, offset: 0, limit: Self.measureLimit)) ?? []
        return raw.filter { $0.atMs >= from && $0.atMs <= to }.sorted { $0.atMs < $1.atMs }
    }

    private func analyte(_ id: String, from: Int64, to: Int64) async -> Analyte? {
        let raw = await measurements(id, from: from, to: to)
        guard !raw.isEmpty else { return nil }
        let target = units[id]
        let points = raw.map { m -> Sample in
            var value = m.value
            var unit = m.unit
            if let target, target != m.unit,
               let converted = convertHormoneValue(
                   value: m.value, fromUnit: m.unit, toUnit: target, hormone: id) {
                value = converted
                unit = target
            }
            return Sample(atMs: m.atMs, value: value, unit: unit, raw: m)
        }
        return Analyte(
            id: id,
            label: HormoneCatalog.kindLabel(id),
            unit: points[points.count - 1].unit,
            points: points)
    }

    private func hormonesSection(from: Int64, to: Int64, markers: Bool) async -> ReportSection? {
        let ids = ((try? await session.distinctHormones()) ?? [])
            .filter { $0 != HormoneCatalog.weight }
        var analytes: [Analyte] = []
        for id in ids {
            if let one = await analyte(id, from: from, to: to) { analytes.append(one) }
        }
        guard !analytes.isEmpty else { return nil }

        let ordered = analytes.sorted { $0.points.count > $1.points.count }
        let main = analytes.first { $0.id == "estradiol" } ?? ordered[0]
        let secondary = analytes.first { $0.id == "testosterone" && $0.id != main.id }
            ?? ordered.first { $0.id != main.id }

        var blocks: [ReportBlock] = []
        blocks.append(.headValues(left: head(main), right: secondary.map { head($0) }))

        let factor = scaleFactor(main: main, secondary: secondary)
        var series: [ReportChartSeries] = [
            ReportChartSeries(
                points: main.points.map { ReportTimedValue(atMs: $0.atMs, value: $0.value) },
                dashed: false, dots: true, secondary: false),
        ]
        if let secondary {
            series.append(ReportChartSeries(
                points: secondary.points.map {
                    ReportTimedValue(atMs: $0.atMs, value: $0.value * Double(factor))
                },
                dashed: true, dots: false, secondary: true))
        }
        let values = series.flatMap { $0.points }.map(\.value)
        if values.count >= 2 {
            var chartMarkers: [ReportChartMarker] = []
            if markers {
                let changes = (try? await session.listTreatmentChanges(fromMs: from, toMs: to)) ?? []
                chartMarkers = changes
                    .filter { $0.field == "dose" }
                    .map { ReportChartMarker(atMs: $0.atMs, label: "↑ dose \(f.dayMonth($0.atMs))") }
            }
            var legend: [ReportLegendItem] = [
                ReportLegendItem(
                    label: "\(main.label) (\(main.unit))", dashed: false, secondary: false),
            ]
            if let secondary {
                legend.append(ReportLegendItem(
                    label: factor > 1
                        ? "\(secondary.label) (\(secondary.unit), ×\(factor))"
                        : "\(secondary.label) (\(secondary.unit))",
                    dashed: true,
                    secondary: true))
            }
            blocks.append(.wideChart(
                chart: ReportChartSpec(
                    width: ReportGeo.contentW,
                    height: ReportGeo.px(224),
                    gutter: 0,
                    inset: ReportGeo.px(17),
                    plotTop: ReportGeo.px(20.6),
                    baseline: ReportGeo.px(203.4),
                    gridlines: [ReportGeo.px(44.8), ReportGeo.px(103.4), ReportGeo.px(162)],
                    fromMs: from,
                    toMs: to,
                    yMin: values.min() ?? 0,
                    yMax: values.max() ?? 1,
                    series: series,
                    markers: chartMarkers),
                legend: legend,
                legendTail: "Axe temporel proportionnel · \(f.monthShort(from)) → \(f.monthLong(to))"))
        }

        blocks.append(pairedTable(main: main, secondary: secondary))
        let others = analytes.filter { $0.id != main.id && $0.id != secondary?.id }
        if !others.isEmpty {
            blocks.append(.caption("AUTRES ANALYSES DE LA PÉRIODE"))
            blocks.append(.table(
                columns: [
                    ReportColumn(title: "PRÉLÈVEMENT", weight: 1.0),
                    ReportColumn(title: "ANALYSE", weight: 1.1, strong: true),
                    ReportColumn(title: "VALEUR", weight: 1.1, strong: true),
                    ReportColumn(title: "LABORATOIRE", weight: 1.3, alignRight: true, muted: true),
                ],
                rows: others.flatMap { a in
                    a.points.sorted { $0.atMs > $1.atMs }.map { p in
                        [f.slashed(p.atMs), a.label, f.value(p.value, p.unit), lab(p)]
                    }
                },
                rowPad: ReportGeo.px(10)))
        }
        blocks.append(.paragraph(
            text: "Valeurs reportées dans l'unité du compte rendu d'origine. Aucun intervalle de "
                + "référence n'est affiché : il dépend du laboratoire et de l'objectif thérapeutique.",
            note: true))
        return ReportSection(title: "TAUX HORMONAUX", blocks: blocks)
    }

    private func head(_ a: Analyte) -> ReportHeadValue {
        let last = a.points[a.points.count - 1]
        // The original reading is kept beside the converted one: the doctor's
        // laboratory report says one of the two, and it may not be ours.
        let conversion = last.unit != last.raw.unit
            ? "· " + f.value(last.raw.value, last.raw.unit)
            : nil
        return ReportHeadValue(
            caption: "\(a.label.uppercased()) — DERNIÈRE VALEUR",
            value: f.number(last.value),
            unit: last.unit,
            conversion: conversion)
    }

    /// The secondary curve shares the main axis, so it is multiplied by the power
    /// of ten that brings it closest without overtaking — and the legend prints
    /// the factor, because a hidden multiplier is a lie.
    private func scaleFactor(main: Analyte, secondary: Analyte?) -> Int {
        guard let secondary else { return 1 }
        let top = main.points.map(\.value).max() ?? 0
        let other = secondary.points.map(\.value).max() ?? 0
        guard other > 0, top > 0 else { return 1 }
        var factor = 1
        while factor < Self.maxScaleFactor && other * Double(factor) * 10 <= top { factor *= 10 }
        return factor
    }

    private func pairedTable(main: Analyte, secondary: Analyte?) -> ReportBlock {
        var byDay: [Int: [Sample?]] = [:]
        for p in main.points {
            var pair = byDay[epochDay(p.atMs)] ?? [nil, nil]
            pair[0] = p
            byDay[epochDay(p.atMs)] = pair
        }
        for p in secondary?.points ?? [] {
            var pair = byDay[epochDay(p.atMs)] ?? [nil, nil]
            pair[1] = p
            byDay[epochDay(p.atMs)] = pair
        }
        let empty = "—"
        var columns: [ReportColumn] = [ReportColumn(title: "PRÉLÈVEMENT", weight: 1.0)]
        columns.append(ReportColumn(title: main.label.uppercased(), weight: 1.1, strong: true))
        if let secondary {
            columns.append(
                ReportColumn(title: secondary.label.uppercased(), weight: 1.1, strong: true))
        }
        columns.append(
            ReportColumn(title: "LABORATOIRE", weight: 1.3, alignRight: true, muted: true))
        let rows = byDay.keys.sorted(by: >).map { day -> [String] in
            let pair = byDay[day] ?? [nil, nil]
            var row: [String] = []
            let stamp = pair.compactMap { $0 }.first?.atMs ?? 0
            row.append(f.slashed(stamp))
            row.append(pair[0].map { f.value($0.value, $0.unit) } ?? empty)
            if secondary != nil {
                row.append(pair[1].map { f.value($0.value, $0.unit) } ?? empty)
            }
            row.append(pair.compactMap { $0 }.first.map { lab($0) } ?? empty)
            return row
        }
        return .table(columns: columns, rows: rows, rowPad: ReportGeo.px(10))
    }

    private func lab(_ sample: Sample) -> String {
        guard let name = sample.raw.labName, !name.isEmpty else { return "Saisie manuelle" }
        return name
    }

    // MARK: - 5 — Poids

    private func weightSection(from: Int64, to: Int64) async -> ReportSection? {
        let raw = await measurements(HormoneCatalog.weight, from: from, to: to)
        guard !raw.isEmpty else { return nil }
        // Weight shares the hormone table but not its conversion: the Rust core
        // does not know kilograms, so the catalogue converts client-side.
        let unit = units[HormoneCatalog.weight] ?? raw[raw.count - 1].unit
        let points = raw.map { m in
            ReportTimedValue(
                atMs: m.atMs,
                value: HormoneCatalog.convertWeight(m.value, from: m.unit, to: unit) ?? m.value)
        }
        let first = points[0].value
        let last = points[points.count - 1].value
        let stats = [
            ReportStat(label: "Actuel", value: "\(f.score(last)) \(unit)"),
            ReportStat(label: "Début de période", value: "\(f.score(first)) \(unit)"),
            ReportStat(label: "Variation", value: f.signed(last - first, unit)),
        ]
        let chart: ReportChartSpec? = points.count < 2 ? nil : ReportChartSpec(
            width: ReportGeo.chartW,
            height: ReportGeo.px(74),
            gutter: 0,
            inset: ReportGeo.px(11),
            plotTop: ReportGeo.px(8),
            baseline: ReportGeo.px(66.6),
            fromMs: from,
            toMs: to,
            yMin: points.map(\.value).min() ?? 0,
            yMax: points.map(\.value).max() ?? 1,
            series: [
                ReportChartSeries(
                    points: points, dashed: false, dots: false, secondary: false,
                    terminalDot: true),
            ])
        return ReportSection(
            title: "POIDS",
            blocks: [
                .statChart(ReportStatChart(
                    stats: stats, caption: nil, chart: chart, punctuality: nil, note: nil,
                    centred: true)),
            ])
    }

    // MARK: - 6 — Ressenti déclaré

    private struct Indicator {
        let label: String
        let current: [ReportTimedValue]
        let previous: [Double]
    }

    private func feelingsSection(range: ReportRange) async -> ReportSection? {
        let from = range.fromMs
        let to = range.toMs
        let all = (try? await session.listJournalEntries(offset: 0, limit: Self.entryLimit)) ?? []
        let current = all.filter { $0.atMs >= from && $0.atMs <= to }.sorted { $0.atMs < $1.atMs }
        guard !current.isEmpty else { return nil }
        let span = max(1, to - from)
        let previous = all.filter { $0.atMs >= from - span && $0.atMs < from }

        var indicators: [Indicator] = []
        for gauge in Self.builtInGauges {
            let now = current.compactMap { entry -> ReportTimedValue? in
                guard let v = gauge.read(entry) else { return nil }
                return ReportTimedValue(atMs: entry.atMs, value: Double(v))
            }
            if !now.isEmpty {
                indicators.append(Indicator(
                    label: gauge.label,
                    current: now,
                    previous: previous.compactMap { gauge.read($0) }.map(Double.init)))
            }
        }
        indicators.append(contentsOf: await customIndicators(current: current, previous: previous))

        let moodWeeks = weekly(current.compactMap { entry -> ReportTimedValue? in
            guard let mood = entry.mood else { return nil }
            return ReportTimedValue(atMs: entry.atMs, value: Double(mood))
        })
        let signalled = effects(current)
        // §7.7 forbids an orphan title; a table reduced to its header row is the
        // same defect one level down. Every built-in gauge can be hidden and
        // every entry left without a value, so nothing guarantees a row: the
        // table is printed only when it has some, and when that leaves the
        // framing sentence alone the section itself has nothing to say.
        if indicators.isEmpty, moodWeeks.count < 2, signalled.isEmpty { return nil }

        var blocks: [ReportBlock] = []
        blocks.append(.paragraph(
            text: "\(plural(current.count, "saisie", "saisies")) sur "
                + "\(plural(range.days, "jour", "jours")). Échelles de 0 à 10, remplies par la "
                + "personne elle-même. Le texte libre du journal n'est pas exporté.",
            note: false))
        if !indicators.isEmpty {
            let empty = "—"
            blocks.append(.table(
                columns: [
                    ReportColumn(title: "INDICATEUR", weight: 1.4, strong: true),
                    ReportColumn(title: "MOYENNE", weight: 0.8),
                    ReportColumn(title: "PÉRIODE −1", weight: 0.8, muted: true),
                    ReportColumn(title: "TENDANCE", weight: 1.6),
                ],
                rows: indicators.map { indicator in
                    let mean = average(indicator.current.map(\.value)) ?? 0
                    let before = average(indicator.previous)
                    return [
                        indicator.label,
                        f.score(mean),
                        before.map { f.score($0) } ?? empty,
                        trend(mean: mean, previous: before, series: indicator.current),
                    ]
                },
                rowPad: ReportGeo.px(10)))
        }

        if moodWeeks.count >= 2 {
            blocks.append(.caption("HUMEUR MOYENNE PAR SEMAINE"))
            blocks.append(.wideChart(
                chart: ReportChartSpec(
                    width: ReportGeo.contentW,
                    height: ReportGeo.px(64),
                    gutter: 0,
                    inset: ReportGeo.px(17),
                    plotTop: ReportGeo.px(14.9),
                    baseline: ReportGeo.px(57.6),
                    gridlines: [ReportGeo.px(14.9)],
                    fromMs: from,
                    toMs: to,
                    yMin: 0,
                    yMax: Self.scaleMax,
                    series: [
                        ReportChartSeries(
                            points: moodWeeks, dashed: false, dots: false, secondary: false,
                            terminalDot: true),
                    ]),
                legend: [],
                legendTail: nil))
        }

        if !signalled.isEmpty {
            blocks.append(.caption("EFFETS LES PLUS SIGNALÉS"))
            blocks.append(.chips(signalled))
        }
        return ReportSection(title: "RESSENTI DÉCLARÉ", blocks: blocks)
    }

    private func customIndicators(
        current: [JournalEntry],
        previous: [JournalEntry]
    ) async -> [Indicator] {
        let definitions = ((try? await session.listMetricDefinitions(domain: "journal")) ?? [])
            .filter { !$0.builtin && $0.enabled }
        guard !definitions.isEmpty else { return [] }

        func read(_ entries: [JournalEntry]) async -> [Int64: [ReportTimedValue]] {
            var out: [Int64: [ReportTimedValue]] = [:]
            for entry in entries {
                let values = (try? await session.listMetricValues(
                    entryDomain: "journal", entryId: entry.id)) ?? []
                for value in values {
                    out[value.metricId, default: []]
                        .append(ReportTimedValue(atMs: entry.atMs, value: Double(value.value)))
                }
            }
            return out
        }

        let now = await read(current)
        let before = await read(previous)
        return definitions.compactMap { definition in
            guard let series = now[definition.id]?.sorted(by: { $0.atMs < $1.atMs }),
                  !series.isEmpty
            else { return nil }
            return Indicator(
                label: definition.label,
                current: series,
                previous: (before[definition.id] ?? []).map(\.value))
        }
    }

    /// The trend is written out, never an arrow: « En hausse » is readable by
    /// someone skimming a printed page, a glyph is not. « régulière » is only
    /// claimed when the weekly means actually move the same way three weeks in a
    /// row.
    private func trend(mean: Double, previous: Double?, series: [ReportTimedValue]) -> String {
        guard let previous else { return "Première période mesurée" }
        let delta = mean - previous
        let weeks = weekly(series)
        let run = monotoneRun(weeks.map(\.value))
        if run >= Self.steadyWeeks && abs(delta) >= Self.trendClear,
           weeks.count - run >= 0, weeks.count - run < weeks.count {
            let since = f.capitalise(f.monthLong(weeks[weeks.count - run].atMs))
            return delta > 0
                ? "En hausse régulière depuis \(since)"
                : "En baisse régulière depuis \(since)"
        }
        if abs(delta) < Self.trendFlat { return "Stable" }
        if abs(delta) < Self.trendClear { return delta > 0 ? "Légère hausse" : "Légère baisse" }
        return delta > 0 ? "En hausse" : "En baisse"
    }

    /// Length of the monotone tail of `values`, counted in samples.
    private func monotoneRun(_ values: [Double]) -> Int {
        guard values.count >= 2 else { return 0 }
        var run = 1
        let rising = values[values.count - 1] >= values[values.count - 2]
        var index = values.count - 1
        while index >= 1 {
            let step = values[index] - values[index - 1]
            if (rising && step >= 0) || (!rising && step <= 0) { run += 1 } else { break }
            index -= 1
        }
        return run
    }

    /// One point per calendar week. The bucket is placed at the mean instant of
    /// its own samples, so a point can never land outside the exported period.
    private func weekly(_ points: [ReportTimedValue]) -> [ReportTimedValue] {
        var buckets: [Int: [ReportTimedValue]] = [:]
        for point in points {
            buckets[epochDay(point.atMs) / 7, default: []].append(point)
        }
        return buckets.keys.sorted().map { key in
            let group = buckets[key] ?? []
            let at = group.map { Double($0.atMs) }.reduce(0, +) / Double(max(1, group.count))
            return ReportTimedValue(
                atMs: Int64(at), value: average(group.map(\.value)) ?? 0)
        }
    }

    /// `sideEffects` is free text, comma-separated the same way the app splits it
    /// into chips. Counting folds case and accents so « Céphalées » and
    /// « cephalees » are one effect, and the day is the unit — three entries the
    /// same day are one day of fatigue, not three.
    private func effects(_ entries: [JournalEntry]) -> [(label: String, count: String)] {
        var days: [String: Set<Int>] = [:]
        var display: [String: String] = [:]
        for entry in entries {
            let day = epochDay(entry.atMs)
            for chunk in (entry.sideEffects ?? "").components(separatedBy: ",") {
                let label = chunk.trimmingCharacters(in: .whitespacesAndNewlines)
                if label.isEmpty { continue }
                let key = label.folding(
                    options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
                if display[key] == nil { display[key] = label }
                days[key, default: []].insert(day)
            }
        }
        return days.keys
            .sorted { a, b in
                let ca = days[a]?.count ?? 0
                let cb = days[b]?.count ?? 0
                if ca != cb { return ca > cb }
                return (display[a] ?? "") < (display[b] ?? "")
            }
            .prefix(Self.maxEffects)
            .map { (label: display[$0] ?? "", count: "\(days[$0]?.count ?? 0) j") }
    }

    // MARK: - Menstruations

    private func bleedingSection(from: Int64, to: Int64) async -> ReportSection? {
        let entries = ((try? await session.listBleedingEntries(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { $0.atMs >= from && $0.atMs <= to }
            .sorted { $0.atMs < $1.atMs }
        guard !entries.isEmpty else { return nil }

        // Consecutive days are one episode; the doctor reads spans, not rows.
        struct Span {
            var start: Int
            var end: Int
            var period: Bool
            var spotting: Bool
        }
        var spans: [Span] = []
        for entry in entries {
            let day = epochDay(entry.atMs)
            let spotting = entry.isSpotting == true
            if var last = spans.last, day <= last.end + 1 {
                if day > last.end { last.end = day }
                if spotting { last.spotting = true } else { last.period = true }
                spans[spans.count - 1] = last
            } else {
                spans.append(Span(start: day, end: day, period: !spotting, spotting: spotting))
            }
        }

        let rows = spans.reversed().map { span -> [String] in
            let length = span.end - span.start + 1
            let nature: String
            if span.period && span.spotting { nature = "Menstruations et spotting" }
            else if span.spotting { nature = "Spotting" }
            else { nature = "Menstruations" }
            return [
                f.slashed(msOfEpochDay(span.start)),
                f.slashed(msOfEpochDay(span.end)),
                plural(length, "jour", "jours"),
                nature,
            ]
        }
        var blocks: [ReportBlock] = [
            .table(
                columns: [
                    ReportColumn(title: "DÉBUT", weight: 1.0, strong: true),
                    ReportColumn(title: "FIN", weight: 1.0),
                    ReportColumn(title: "DURÉE", weight: 0.9),
                    ReportColumn(title: "NATURE", weight: 1.1, alignRight: true, muted: true),
                ],
                rows: rows,
                rowPad: ReportGeo.px(10)),
        ]
        if spans.count >= 2 {
            var gaps: [Double] = []
            for index in 1..<spans.count {
                gaps.append(Double(spans[index].start - spans[index - 1].start))
            }
            let mean = Int((average(gaps) ?? 0).rounded())
            blocks.append(.paragraph(
                text: "Intervalle moyen observé entre deux débuts : \(mean) jours.", note: true))
        }
        return ReportSection(title: "MENSTRUATIONS", blocks: blocks)
    }

    // MARK: - 7 — Voix

    private func voiceSection(from: Int64, to: Int64) async -> ReportSection? {
        let clips = ((try? await session.listVoiceClips(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { $0.atMs >= from && $0.atMs <= to && $0.pitchHz != nil }
            .sorted { $0.atMs < $1.atMs }
        guard !clips.isEmpty else { return nil }
        let points = clips.map {
            ReportTimedValue(atMs: $0.atMs, value: Double($0.pitchHz ?? 0))
        }
        let first = points[0].value
        let last = points[points.count - 1].value
        let stats = [
            ReportStat(label: "Actuelle", value: "\(Int(last.rounded())) Hz"),
            ReportStat(
                label: f.capitalise(f.monthLong(points[0].atMs)),
                value: "\(Int(first.rounded())) Hz"),
            ReportStat(label: "Variation", value: f.signed(last - first, "Hz", oneDecimal: false)),
            ReportStat(label: "Enregistrements", value: f.integer(clips.count)),
        ]
        let scale = hertzScale(
            low: points.map(\.value).min() ?? 0, high: points.map(\.value).max() ?? 1)
        // The gradations are derived from the data, so their gridlines are placed
        // by the same mapping the curve uses rather than pinned to the
        // prototype's demo scale of 180 / 160 / 140 Hz.
        let top = ReportGeo.px(15.1)
        let baseline = ReportGeo.px(93.1)
        let chart: ReportChartSpec? = points.count < 2 ? nil : ReportChartSpec(
            width: ReportGeo.chartW,
            height: ReportGeo.px(104),
            gutter: Self.voiceGutter,
            inset: ReportGeo.px(6),
            plotTop: top,
            baseline: baseline,
            gridlines: scale.ticks.map { value in
                let fraction = (value - scale.min) / max(0.0001, scale.max - scale.min)
                return baseline - (baseline - top) * CGFloat(fraction)
            },
            yTickLabels: scale.ticks.map { "\(Int($0.rounded())) Hz" },
            fromMs: from,
            toMs: to,
            yMin: scale.min,
            yMax: scale.max,
            series: [ReportChartSeries(points: points, dashed: false, dots: true, secondary: false)],
            bounds: (
                left: f.monthShort(points[0].atMs),
                right: f.monthShort(points[points.count - 1].atMs)))
        return ReportSection(
            title: "VOIX",
            blocks: [
                .statChart(ReportStatChart(
                    stats: stats,
                    caption: chart == nil ? nil : "HAUTEUR FONDAMENTALE MESURÉE",
                    chart: chart,
                    punctuality: nil,
                    note: "Hauteur fondamentale mesurée sur l'appareil, sensible aux conditions "
                        + "d'enregistrement — à lire comme une tendance, pas comme une mesure de "
                        + "phoniatrie.")),
            ])
    }

    private struct Scale {
        let min: Double
        let max: Double
        let ticks: [Double]
    }

    /// Round gradations in the unit the reader thinks in: 20 Hz at a time.
    private func hertzScale(low: Double, high: Double) -> Scale {
        var step = Self.hertzStep
        let bottom = (low / step).rounded(.down) * step
        var top = (high / step).rounded(.up) * step
        if top <= bottom { top = bottom + step }
        while (top - bottom) / step > Double(Self.maxTicks) { step *= 2 }
        var ticks: [Double] = []
        var value = top
        while value >= bottom - 0.001 {
            ticks.append(value)
            value -= step
        }
        return Scale(min: bottom - step * 0.35, max: top, ticks: ticks)
    }

    // MARK: - 8 — Questions à aborder

    private func nextAppointment() async -> Appointment? {
        let now = Time.nowMs()
        return ((try? await session.listAppointments(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { $0.atMs > now }
            .min { $0.atMs < $1.atMs }
    }

    private func questionsSection() async -> ReportSection? {
        guard let next = await nextAppointment() else { return nil }
        let items = appointmentTodoItems(next.todo).map(\.label)
        guard !items.isEmpty else { return nil }
        return ReportSection(title: "QUESTIONS À ABORDER", blocks: [.checklist(items)])
    }

    // MARK: - Photos d'évolution

    private func photosSection(from: Int64, to: Int64) async -> ReportSection? {
        let records = ((try? await session.listPhotoRecords(offset: 0, limit: Self.entryLimit)) ?? [])
            .filter { $0.atMs >= from && $0.atMs <= to }
            .sorted { $0.atMs < $1.atMs }
            .suffix(Self.maxPhotos)
        guard !records.isEmpty else { return nil }
        // Decrypted in memory and handed straight to the renderer: no plaintext
        // image is ever written next to the PDF.
        var tiles: [ReportPhotoTile] = []
        for record in records {
            guard let bytes = try? await session.decryptBlobFile(
                URL(fileURLWithPath: record.filePath))
            else { continue }
            tiles.append(ReportPhotoTile(date: f.slashed(record.atMs), bytes: bytes))
        }
        guard !tiles.isEmpty else { return nil }
        return ReportSection(
            title: "PHOTOS D'ÉVOLUTION",
            blocks: [
                .photos(tiles),
                .paragraph(
                    text: "Photographies enregistrées dans l'application, réduites pour "
                        + "l'impression. Elles ne sont incluses que parce qu'elles ont été cochées "
                        + "explicitement.",
                    note: true),
            ])
    }

    // MARK: - Plumbing

    private func date(_ atMs: Int64) -> Date { Date(timeIntervalSince1970: Double(atMs) / 1000) }

    /// Midnight of 1 January 1970 **in the report's own calendar** — the origin
    /// the day index below counts from.
    private var dayOrigin: Date { calendar.startOfDay(for: Date(timeIntervalSince1970: 0)) }

    /// Local day index. Monotone and unique per calendar day, which is all the
    /// bucketing and the span welding need — but it also has to survive the trip
    /// back through `msOfEpochDay`, and that is where arithmetic on epoch seconds
    /// broke it: a local midnight expressed in UTC seconds lands on the *previous*
    /// UTC day everywhere east of Greenwich, so every date rebuilt from an index
    /// printed one day early. The calendar the builder already holds knows the
    /// offset and the daylight-saving jumps; the division did not.
    private func epochDay(_ atMs: Int64) -> Int {
        let start = calendar.startOfDay(for: date(atMs))
        return calendar.dateComponents([.day], from: dayOrigin, to: start).day ?? 0
    }

    /// The inverse of ``epochDay(_:)``, aimed at midday: a formatter reading it
    /// back in the same zone then lands on that day whatever the offset, and no
    /// daylight-saving hour can tip it into its neighbour.
    private func msOfEpochDay(_ day: Int) -> Int64 {
        let start = calendar.date(byAdding: .day, value: day, to: dayOrigin) ?? dayOrigin
        let noon = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: start) ?? start
        return Int64(noon.timeIntervalSince1970 * 1000)
    }

    private func average(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        return values.reduce(0, +) / Double(values.count)
    }

    private func plural(_ count: Int, _ one: String, _ many: String) -> String {
        "\(count) " + (count <= 1 ? one : many)
    }

    private struct Gauge {
        let label: String
        let read: (JournalEntry) -> Int?
    }

    private static let measureLimit: Int64 = 2000
    private static let entryLimit: Int64 = 5000
    private static let maxScaleFactor = 1000
    private static let punctualityDots = 60
    private static let twoHoursMin = 120
    private static let maxEffects = 6
    private static let maxPhotos = 8
    private static let scaleMax: Double = 10
    private static let trendFlat: Double = 0.3
    private static let trendClear: Double = 0.8
    private static let steadyWeeks = 3
    private static let hertzStep: Double = 20
    private static let maxTicks = 3

    /// Room for « 180 Hz » to the left of the voice plot.
    private static let voiceGutter: CGFloat = 38.5

    /// The five built-in gauges, in catalogue order (§6.2).
    private static let builtInGauges: [Gauge] = [
        Gauge(label: "Humeur", read: { $0.mood.map(Int.init) }),
        Gauge(label: "Dysphorie", read: { $0.dysphoria.map(Int.init) }),
        Gauge(label: "Euphorie", read: { $0.euphoria.map(Int.init) }),
        Gauge(label: "Libido", read: { $0.libido.map(Int.init) }),
        Gauge(label: "Énergie", read: { $0.energy.map(Int.init) }),
    ]
}
