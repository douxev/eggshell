//! Links between what the user takes, how they sleep and how they feel.
//!
//! This exists because three modules were already recording the pieces and
//! nothing was putting them together: doses on a day, the dream of the night
//! that followed, the mood of the morning after that. The correlation screen
//! drew them as parallel lanes and left the reader to eyeball it, which is
//! exactly the job a computer should be doing.
//!
//! # What this deliberately is not
//!
//! It is **not** a claim about cause.  Everything here is co-occurrence in one
//! person's own record, over weeks. A finding says "these two moved together",
//! never "this one moved the other" — the wording of every string built from it
//! has to hold that line, because the subject matter is someone's medication
//! and the temptation to read a cause is enormous.
//!
//! It is **not** a significance test. A p-value computed over an unregistered
//! trawl of every metric against every other would be meaningless, and printing
//! one would lend false authority. Instead every finding carries the effect in
//! the metric's own units and the number of observations behind it, and the
//! thresholds below refuse to emit anything too thin to be worth reading.
//!
//! # The lag, which is the whole point
//!
//! Naively matching everything on the same calendar day gets the physiology
//! backwards. The chain runs:
//!
//! ```text
//!   doses taken on day N  →  the night of day N  →  the mood of day N+1
//! ```
//!
//! A dream belongs to the night that *started* on day N (see
//! `dreams::night_ms`), so it compares against day N's doses at lag 0 and
//! against day N+1's mood at lag 1. Comparing a night's sleep to the mood of
//! the day it began — before the sleep happened — is the mistake this module
//! exists to avoid making silently.

use std::collections::HashMap;

use crate::db::Database;
use crate::TransitionError;

/// Which direction of a metric is the good one.
///
/// Needed because "dysphoria went up with missed doses" and "mood went up with
/// missed doses" are opposite news, and a UI that only had the sign would
/// colour them the same.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Valence {
    HigherIsBetter,
    HigherIsWorse,
    /// Custom sliders, and built-ins where the app has no business deciding —
    /// libido and dream vividness are not achievements.
    Neutral,
}

pub fn valence_of(metric_key: &str) -> Valence {
    match metric_key {
        "mood" | "euphoria" | "energy" | "sleep_quality" | "emotional_tone" => {
            Valence::HigherIsBetter
        }
        "dysphoria" => Valence::HigherIsWorse,
        _ => Valence::Neutral,
    }
}

/// How much of a metric's own scale the difference covers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Strength {
    Slight,
    Clear,
    Strong,
}

/// One readable link.
#[derive(Clone, Debug, PartialEq)]
pub struct Insight {
    /// The metric that moved, e.g. `sleep_quality`.
    pub metric_key: String,
    /// What it moved with. A condition key (`missed_dose`, `lucid_dream`) or
    /// another metric key.
    pub against_key: String,
    /// Difference in the metric's own units: the mean when the condition held
    /// minus the mean when it did not. Signed.
    pub delta: f64,
    /// Observations on each side of the comparison. Both are stated because
    /// "3 nights vs 40" and "20 vs 23" are very different claims.
    pub sample_with: i64,
    pub sample_without: i64,
    /// Days between the condition and the measurement. See the module note.
    pub lag_days: i64,
    pub strength: Strength,
    /// True when the movement is in the direction the user would want, given
    /// [`valence_of`]. Meaningless for [`Valence::Neutral`] metrics, which the
    /// UI should render without a good/bad colour.
    pub favourable: bool,
    pub valence: Valence,
}

/// A day's worth of everything, keyed by local date in epoch-ms at midnight.
///
/// Built by the caller because only the native side knows the device's zone —
/// the same reason `dreams.night_ms` is passed in rather than derived.
#[derive(Clone, Debug, Default)]
pub struct DayRecord {
    /// Metric key → value, from the journal and its custom sliders.
    pub mood_metrics: HashMap<String, f64>,
    /// Metric key → value, from the dream of the night that began this day.
    pub dream_metrics: HashMap<String, f64>,
    pub had_dream: bool,
    pub lucid_dream: bool,
    pub doses_on_time: i64,
    pub doses_late: i64,
    pub doses_missed: i64,
}

impl DayRecord {
    fn missed_dose(&self) -> bool {
        self.doses_missed > 0
    }
    fn late_dose(&self) -> bool {
        self.doses_late > 0
    }
    fn clean_day(&self) -> bool {
        self.doses_on_time > 0 && self.doses_late == 0 && self.doses_missed == 0
    }
}

/// Below this on either side, a difference is an anecdote.
///
/// Five is not a statistical threshold, it is an editorial one: with three
/// nights on one side the app would confidently report a coincidence, and a
/// user reading it about their own medication deserves better than that.
const MIN_GROUP: usize = 5;

/// Differences smaller than this fraction of the metric's range are noise the
/// user cannot act on. Metrics here are 0..10, so this is half a point.
const MIN_DELTA: f64 = 0.5;

const DAY_MS: i64 = 86_400_000;

/// Compute every link worth showing, strongest first.
///
/// `days` must be keyed by local midnight. Missing days are simply absent —
/// gaps are normal and are not interpolated, because inventing a mood for a day
/// nobody recorded one would be inventing the finding too.
pub fn analyse(days: &HashMap<i64, DayRecord>) -> Vec<Insight> {
    let mut out = Vec::new();

    // Dose adherence on day N against the night that began on day N.
    for &metric in DREAM_METRICS {
        out.extend(contrast(days, metric, "missed_dose", 0, DayRecord::missed_dose, |d| {
            d.dream_metrics.get(metric).copied()
        }));
        out.extend(contrast(days, metric, "late_dose", 0, DayRecord::late_dose, |d| {
            d.dream_metrics.get(metric).copied()
        }));
    }

    // The night of day N against the mood of day N+1 — the sleep happened in
    // between, so the mood of day N cannot be downstream of it.
    for &metric in MOOD_METRICS {
        out.extend(contrast(days, metric, "lucid_dream", 1, |d| d.lucid_dream, |d| {
            d.mood_metrics.get(metric).copied()
        }));
        out.extend(contrast(days, metric, "any_dream", 1, |d| d.had_dream, |d| {
            d.mood_metrics.get(metric).copied()
        }));
        // And dose adherence against the same day's mood, at lag 0: a dose
        // missed this morning is felt today, not tomorrow.
        out.extend(contrast(days, metric, "missed_dose", 0, DayRecord::missed_dose, |d| {
            d.mood_metrics.get(metric).copied()
        }));
        out.extend(contrast(days, metric, "clean_day", 0, DayRecord::clean_day, |d| {
            d.mood_metrics.get(metric).copied()
        }));
    }

    // Strongest first, and drop the ones too small to be worth a line.
    out.retain(|i| i.delta.abs() >= MIN_DELTA);
    out.sort_by(|a, b| {
        b.delta
            .abs()
            .partial_cmp(&a.delta.abs())
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    out
}

/// Mean of `value` on days where `condition` held, minus where it did not,
/// with the measurement taken `lag_days` after the condition.
fn contrast<C, V>(
    days: &HashMap<i64, DayRecord>,
    metric_key: &str,
    against_key: &str,
    lag_days: i64,
    condition: C,
    value: V,
) -> Option<Insight>
where
    C: Fn(&DayRecord) -> bool,
    V: Fn(&DayRecord) -> Option<f64>,
{
    let mut with: Vec<f64> = Vec::new();
    let mut without: Vec<f64> = Vec::new();

    for (day, record) in days {
        // The measurement lives on a different day than the condition, and that
        // day has to actually exist — a lagged lookup into a gap must drop the
        // pair rather than silently compare against the condition's own day.
        let measured_on = day + lag_days * DAY_MS;
        let Some(target) = days.get(&measured_on) else { continue };
        let Some(v) = value(target) else { continue };
        if condition(record) {
            with.push(v)
        } else {
            without.push(v)
        }
    }

    if with.len() < MIN_GROUP || without.len() < MIN_GROUP {
        return None;
    }

    let delta = mean(&with) - mean(&without);
    let valence = valence_of(metric_key);
    Some(Insight {
        metric_key: metric_key.to_string(),
        against_key: against_key.to_string(),
        delta,
        sample_with: with.len() as i64,
        sample_without: without.len() as i64,
        lag_days,
        strength: strength_of(delta),
        favourable: match valence {
            Valence::HigherIsBetter => delta > 0.0,
            Valence::HigherIsWorse => delta < 0.0,
            Valence::Neutral => false,
        },
        valence,
    })
}

fn mean(xs: &[f64]) -> f64 {
    if xs.is_empty() {
        0.0
    } else {
        xs.iter().sum::<f64>() / xs.len() as f64
    }
}

/// Banded on the 0..10 scale the sliders use. Deliberately coarse: the
/// difference between 1.9 and 2.1 points is not something this data resolves,
/// and three bands is what a reader can act on.
fn strength_of(delta: f64) -> Strength {
    match delta.abs() {
        d if d >= 2.0 => Strength::Strong,
        d if d >= 1.0 => Strength::Clear,
        _ => Strength::Slight,
    }
}

/// The built-in dream sliders, seeded by migration 0016.
const DREAM_METRICS: &[&str] = &["sleep_quality", "recall", "vividness", "emotional_tone"];
/// The built-in journal gauges, seeded by migration 0010.
const MOOD_METRICS: &[&str] = &["mood", "dysphoria", "euphoria", "energy"];

/// Load a day table from the vault, ready for [`analyse`].
///
/// `day_of` maps an instant to that instant's local midnight; the caller
/// supplies it because only it knows the timezone.
pub fn day_records(
    db: &Database,
    from_ms: i64,
    to_ms: i64,
    day_of: impl Fn(i64) -> i64,
) -> Result<HashMap<i64, DayRecord>, TransitionError> {
    let mut days: HashMap<i64, DayRecord> = HashMap::new();

    for e in crate::journal::list_between(db, from_ms, to_ms)? {
        let rec = days.entry(day_of(e.at_ms)).or_default();
        if let Some(v) = e.mood {
            rec.mood_metrics.insert("mood".into(), v as f64);
        }
        if let Some(v) = e.dysphoria {
            rec.mood_metrics.insert("dysphoria".into(), v as f64);
        }
        if let Some(v) = e.euphoria {
            rec.mood_metrics.insert("euphoria".into(), v as f64);
        }
        if let Some(v) = e.energy {
            rec.mood_metrics.insert("energy".into(), v as f64);
        }
        if let Some(v) = e.libido {
            rec.mood_metrics.insert("libido".into(), v as f64);
        }
    }

    // metric_id -> metric_key, built once. Archived definitions are included:
    // a slider the user retired still has historical values, and dropping them
    // would quietly shrink the sample behind every past finding.
    let dream_keys: HashMap<i64, String> =
        crate::metrics::list_definitions(db, "dreams".to_string(), true)?
            .into_iter()
            .map(|d| (d.id, d.metric_key))
            .collect();

    for d in crate::dreams::list_between(db, from_ms, to_ms)? {
        // night_ms is already local midnight of the night — no day_of here, or
        // a night stored at midnight would be re-floored and stay put by luck
        // rather than by design.
        let rec = days.entry(d.night_ms).or_default();
        rec.had_dream = true;
        rec.lucid_dream = rec.lucid_dream || d.lucid;
        for v in crate::metrics::list_values(db, "dreams".to_string(), d.id)? {
            if let Some(key) = dream_keys.get(&v.metric_id) {
                rec.dream_metrics.insert(key.clone(), v.value as f64);
            }
        }
    }

    for ev in crate::medication::list_dose_events_between(db, from_ms, to_ms)? {
        let rec = days.entry(day_of(ev.taken_at_ms)).or_default();
        match ev.status.as_str() {
            "taken" => match ev.scheduled_at_ms {
                Some(planned) if (ev.taken_at_ms - planned) / 60_000 > 15 => rec.doses_late += 1,
                _ => rec.doses_on_time += 1,
            },
            // A declared skip is a missed dose: the user said they did not take
            // it, which is the thing being correlated against.
            _ => rec.doses_missed += 1,
        }
    }

    Ok(days)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(n: i64) -> i64 {
        n * DAY_MS
    }

    fn with_dream_quality(q: f64) -> DayRecord {
        let mut r = DayRecord::default();
        r.had_dream = true;
        r.dream_metrics.insert("sleep_quality".into(), q);
        r
    }

    #[test]
    fn a_contrast_needs_enough_on_both_sides() {
        let mut days = HashMap::new();
        // Four missed-dose days against plenty of clean ones: below MIN_GROUP,
        // so nothing is reported however large the difference looks.
        for i in 0..4 {
            let mut r = with_dream_quality(2.0);
            r.doses_missed = 1;
            days.insert(day(i), r);
        }
        for i in 4..20 {
            let mut r = with_dream_quality(9.0);
            r.doses_on_time = 1;
            days.insert(day(i), r);
        }
        assert!(
            analyse(&days).is_empty(),
            "four observations is an anecdote, not a finding",
        );
    }

    #[test]
    fn a_clear_difference_is_reported_with_its_direction() {
        let mut days = HashMap::new();
        for i in 0..8 {
            let mut r = with_dream_quality(3.0);
            r.doses_missed = 1;
            days.insert(day(i), r);
        }
        for i in 8..20 {
            let mut r = with_dream_quality(8.0);
            r.doses_on_time = 1;
            days.insert(day(i), r);
        }
        let found = analyse(&days);
        let sleep = found
            .iter()
            .find(|i| i.metric_key == "sleep_quality" && i.against_key == "missed_dose")
            .expect("the difference is five points and both groups are large enough");
        assert!(sleep.delta < 0.0, "sleep was worse on missed-dose nights");
        assert_eq!(sleep.strength, Strength::Strong);
        assert!(!sleep.favourable, "lower sleep quality is not good news");
        assert_eq!(sleep.sample_with, 8);
        assert_eq!(sleep.sample_without, 12);
    }

    #[test]
    fn valence_flips_what_counts_as_good_news() {
        // Dysphoria RISING with missed doses is unfavourable, even though the
        // delta is positive — the sign alone would colour it green.
        let mut days = HashMap::new();
        for i in 0..8 {
            let mut r = DayRecord::default();
            r.doses_missed = 1;
            r.mood_metrics.insert("dysphoria".into(), 8.0);
            days.insert(day(i), r);
        }
        for i in 8..20 {
            let mut r = DayRecord::default();
            r.doses_on_time = 1;
            r.mood_metrics.insert("dysphoria".into(), 3.0);
            days.insert(day(i), r);
        }
        let dys = analyse(&days)
            .into_iter()
            .find(|i| i.metric_key == "dysphoria" && i.against_key == "missed_dose")
            .expect("large, well-sampled difference");
        assert!(dys.delta > 0.0);
        assert!(!dys.favourable);
        assert_eq!(dys.valence, Valence::HigherIsWorse);
    }

    #[test]
    fn the_lag_reads_the_day_after_and_drops_pairs_with_no_such_day() {
        // Lucid on even days; mood recorded only on the day AFTER each lucid
        // night. If the lag were ignored the two would never line up.
        let mut days = HashMap::new();
        for i in 0..30 {
            let mut r = DayRecord::default();
            r.had_dream = true;
            r.lucid_dream = i % 2 == 0;
            r.mood_metrics
                .insert("mood".into(), if i % 2 == 1 { 9.0 } else { 4.0 });
            days.insert(day(i), r);
        }
        let mood = analyse(&days)
            .into_iter()
            .find(|i| i.metric_key == "mood" && i.against_key == "lucid_dream")
            .expect("lucid nights are followed by the high-mood days");
        assert_eq!(mood.lag_days, 1);
        assert!(mood.delta > 0.0, "the day after a lucid night scored higher");
    }

    #[test]
    fn noise_sized_differences_are_dropped() {
        let mut days = HashMap::new();
        for i in 0..10 {
            let mut r = with_dream_quality(5.0);
            r.doses_missed = 1;
            days.insert(day(i), r);
        }
        for i in 10..20 {
            let mut r = with_dream_quality(5.2);
            r.doses_on_time = 1;
            days.insert(day(i), r);
        }
        assert!(
            analyse(&days).iter().all(|i| i.metric_key != "sleep_quality"),
            "a fifth of a point is not something a user can act on",
        );
    }

    #[test]
    fn findings_come_back_strongest_first() {
        let mut days = HashMap::new();
        for i in 0..10 {
            let mut r = with_dream_quality(1.0);
            r.doses_missed = 1;
            r.mood_metrics.insert("mood".into(), 4.0);
            days.insert(day(i), r);
        }
        for i in 10..24 {
            let mut r = with_dream_quality(9.0);
            r.doses_on_time = 1;
            r.mood_metrics.insert("mood".into(), 5.0);
            days.insert(day(i), r);
        }
        let found = analyse(&days);
        assert!(found.len() >= 2);
        for pair in found.windows(2) {
            assert!(
                pair[0].delta.abs() >= pair[1].delta.abs(),
                "the biggest difference has to be the first thing read",
            );
        }
    }
}
