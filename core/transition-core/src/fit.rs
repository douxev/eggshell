//! Garmin `.fit` workout files.
//!
//! The third file format a watch export can come in, next to GPX and TCX — and
//! the one Garmin, Coros, Suunto, Wahoo and most bike computers actually record
//! in. Unlike the other two it is binary, so it is decoded here, in the core,
//! once for both apps, rather than twice in Kotlin and Swift.
//!
//! Decoding is done by the `fitparser` crate (MIT, stadelmanma/fitparse-rs),
//! which carries the published FIT profile. What this module owns is the step
//! from "a stream of FIT messages" to "the sessions a person did": the same
//! small shape the GPX/TCX parsers produce on each platform, and nothing more.
//!
//! **What is deliberately dropped.** A `.fit` activity holds every GPS fix,
//! every heartbeat, the device's serial number and the user's body profile.
//! None of it leaves this function: a vault that exists to keep people from
//! being tracked has no business importing where they ran. The route is not
//! even read; distance comes from the watch's own total.

use fitparser::profile::MesgNum;
use fitparser::{FitDataRecord, Value};

/// One workout read out of a `.fit` file.
#[derive(Debug, Clone, PartialEq)]
pub struct FitWorkout {
    pub started_ms: i64,
    /// Timer time — what the watch counted as exercising, pauses excluded —
    /// falling back to elapsed time when the timer is absent.
    pub duration_s: i64,
    /// Metres, as the watch totalled them. None when it recorded no distance.
    pub distance_m: Option<f64>,
    /// The FIT sport name ("running", "cycling", "training"…), used to find
    /// the user's own activity type. Never shown or stored as is.
    pub sport: Option<String>,
    /// The name of the watch's sport profile ("Trail", "Vélo route"), when it
    /// has one. Used as the session note, never invented.
    pub title: Option<String>,
    /// Beats per minute. None when absent or implausible (outside 20–250).
    pub avg_hr: Option<i64>,
    pub max_hr: Option<i64>,
}

/// Heart rates kept from a FIT file. The vault accepts 20–300
/// (`0017_sport.sql`); the ceiling here is lower because 255 is FIT's "no
/// value" for a one-byte field, and a decoder that passes it through would
/// file every strap dropout as a peak of 255 bpm. No heart beats that fast.
const HR_MIN: i64 = 20;
const HR_MAX: i64 = 250;

/// Every workout a `.fit` file describes — usually one, several for a
/// multisport activity — or an empty list when it holds none.
///
/// Never an error: a file that is not a FIT activity (a course, a settings
/// file, a truncated download, a photo picked by mistake) simply holds no
/// workouts, and the import preview says so. That is the same contract the
/// GPX/TCX parsers keep on both platforms.
pub fn parse_fit_workouts(bytes: &[u8]) -> Vec<FitWorkout> {
    let records = match fitparser::from_bytes(bytes) {
        Ok(records) => records,
        // Some exporters write a wrong or missing CRC. The data is still the
        // data; refusing the whole file over a checksum would lose a workout
        // the person can see in their watch app.
        Err(_) => match fitparser::de::from_bytes_with_options(
            bytes,
            &[
                fitparser::de::DecodeOption::SkipHeaderCrcValidation,
                fitparser::de::DecodeOption::SkipDataCrcValidation,
            ]
            .into_iter()
            .collect(),
        ) {
            Ok(records) => records,
            Err(_) => return Vec::new(),
        },
    };
    workouts_from(&records)
}

fn workouts_from(records: &[FitDataRecord]) -> Vec<FitWorkout> {
    let sessions: Vec<FitWorkout> = records
        .iter()
        .filter(|r| r.kind() == MesgNum::Session)
        .filter_map(|session| from_session(session, records))
        .collect();
    if !sessions.is_empty() {
        return sessions;
    }
    // No session message: some devices and converters only write the raw
    // records. Rebuild one workout from their first and last timestamps.
    from_records(records).into_iter().collect()
}

fn from_session(session: &FitDataRecord, records: &[FitDataRecord]) -> Option<FitWorkout> {
    let elapsed = number(session, "total_elapsed_time");
    let timer = number(session, "total_timer_time");
    let end_ms = timestamp(session, "timestamp");
    let started_ms = timestamp(session, "start_time").or_else(|| {
        // A session with no start: work back from its end.
        Some(end_ms? - (elapsed? * 1000.0) as i64)
    })?;
    let duration_s = timer.or(elapsed).map(|s| s.round() as i64)?;
    if duration_s <= 0 {
        return None;
    }

    let mut avg_hr = number(session, "avg_heart_rate").map(|v| v.round() as i64);
    let mut max_hr = number(session, "max_heart_rate").map(|v| v.round() as i64);
    if avg_hr.is_none() || max_hr.is_none() {
        // The summary lacks heart rate but the samples may have it: average
        // the records that fall inside this session.
        let until = end_ms.unwrap_or(started_ms + duration_s * 1000);
        let (avg, max) = heart_rate_of(
            records.iter().filter(|r| {
                r.kind() == MesgNum::Record
                    && timestamp(r, "timestamp").is_some_and(|t| t >= started_ms && t <= until)
            }),
        );
        avg_hr = avg_hr.or(avg);
        max_hr = max_hr.or(max);
    }

    Some(FitWorkout {
        started_ms,
        duration_s,
        distance_m: number(session, "total_distance").filter(|d| *d > 0.0),
        sport: sport_of(session).or_else(|| sport_message(records)),
        title: text(session, "sport_profile_name").or_else(|| sport_name(records)),
        avg_hr: avg_hr.filter(|v| (HR_MIN..=HR_MAX).contains(v)),
        max_hr: max_hr.filter(|v| (HR_MIN..=HR_MAX).contains(v)),
    })
}

fn from_records(records: &[FitDataRecord]) -> Option<FitWorkout> {
    let samples: Vec<&FitDataRecord> = records.iter().filter(|r| r.kind() == MesgNum::Record).collect();
    let first = samples.iter().find_map(|r| timestamp(r, "timestamp"))?;
    let last = samples.iter().rev().find_map(|r| timestamp(r, "timestamp"))?;
    let duration_s = (last - first) / 1000;
    if duration_s <= 0 {
        return None;
    }
    // Distance in a record is the running total, so the last one is the whole.
    let distance_m = samples.iter().rev().find_map(|r| number(r, "distance")).filter(|d| *d > 0.0);
    let (avg_hr, max_hr) = heart_rate_of(samples.iter().copied());
    Some(FitWorkout {
        started_ms: first,
        duration_s,
        distance_m,
        sport: sport_message(records),
        title: sport_name(records),
        avg_hr,
        max_hr,
    })
}

/// Mean and peak of the `heart_rate` samples, ignoring anything a heart does
/// not do — a strap settling reports nonsense for its first few seconds.
fn heart_rate_of<'a>(records: impl Iterator<Item = &'a FitDataRecord>) -> (Option<i64>, Option<i64>) {
    let mut sum = 0i64;
    let mut count = 0i64;
    let mut peak = 0i64;
    for bpm in records.filter_map(|r| number(r, "heart_rate")).map(|v| v.round() as i64) {
        if !(HR_MIN..=HR_MAX).contains(&bpm) {
            continue;
        }
        sum += bpm;
        count += 1;
        peak = peak.max(bpm);
    }
    if count == 0 {
        (None, None)
    } else {
        (Some(sum / count), Some(peak))
    }
}

/// The session's sport, refined by its sub-sport when that says more —
/// "trail" running, "road" cycling — so a catalogue with « Trail » finds it.
fn sport_of(record: &FitDataRecord) -> Option<String> {
    let sport = text(record, "sport")?;
    match text(record, "sub_sport") {
        Some(sub) if sub != "generic" && sub != "all" => Some(format!("{sub} {sport}")),
        _ => Some(sport),
    }
}

/// The file-level `sport` message, for files whose session has none.
fn sport_message(records: &[FitDataRecord]) -> Option<String> {
    records.iter().filter(|r| r.kind() == MesgNum::Sport).find_map(sport_of)
}

fn sport_name(records: &[FitDataRecord]) -> Option<String> {
    records.iter().filter(|r| r.kind() == MesgNum::Sport).find_map(|r| text(r, "name"))
}

fn field<'a>(record: &'a FitDataRecord, name: &str) -> Option<&'a Value> {
    record.fields().iter().find(|f| f.name() == name).map(|f| f.value())
}

fn number(record: &FitDataRecord, name: &str) -> Option<f64> {
    match field(record, name)? {
        Value::Byte(v) | Value::Enum(v) | Value::UInt8(v) | Value::UInt8z(v) => Some(f64::from(*v)),
        Value::SInt8(v) => Some(f64::from(*v)),
        Value::SInt16(v) => Some(f64::from(*v)),
        Value::UInt16(v) | Value::UInt16z(v) => Some(f64::from(*v)),
        Value::SInt32(v) => Some(f64::from(*v)),
        Value::UInt32(v) | Value::UInt32z(v) => Some(f64::from(*v)),
        Value::SInt64(v) => Some(*v as f64),
        Value::UInt64(v) | Value::UInt64z(v) => Some(*v as f64),
        Value::Float32(v) => Some(f64::from(*v)),
        Value::Float64(v) => Some(*v),
        _ => None,
    }
    .filter(|v| v.is_finite())
}

fn timestamp(record: &FitDataRecord, name: &str) -> Option<i64> {
    match field(record, name)? {
        Value::Timestamp(at) => Some(at.timestamp_millis()),
        _ => None,
    }
}

fn text(record: &FitDataRecord, name: &str) -> Option<String> {
    match field(record, name)? {
        Value::String(s) => {
            let s = s.trim().trim_end_matches('\0').trim();
            (!s.is_empty()).then(|| s.to_string())
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Two sample activities from the fitparser test suite (MIT): a short run
    // with no heart rate, and a Fenix 5 ride that has a sport profile name.
    const RUN: &[u8] = include_bytes!("../tests/fixtures/fit/Activity.fit");
    const RIDE: &[u8] = include_bytes!("../tests/fixtures/fit/garmin-fenix-5-bike.fit");

    #[test]
    fn a_run_is_one_workout_with_its_distance_and_timer_time() {
        let w = parse_fit_workouts(RUN);
        assert_eq!(w.len(), 1);
        let run = &w[0];
        assert_eq!(run.sport.as_deref(), Some("running"));
        assert_eq!(run.duration_s, 14); // 13.749 s of timer time, rounded
        assert_eq!(run.distance_m, Some(5.73));
        assert_eq!(run.avg_hr, None);
    }

    #[test]
    fn a_ride_keeps_heart_rate_and_the_profile_name() {
        let ride = &parse_fit_workouts(RIDE)[0];
        assert_eq!(ride.sport.as_deref(), Some("cycling"));
        assert_eq!(ride.title.as_deref(), Some("Bike"));
        assert_eq!(ride.avg_hr, Some(101));
        assert_eq!(ride.max_hr, Some(114));
        assert_eq!(ride.duration_s, 60);
        // 2017-06-12T18:09:22+02:00
        assert_eq!(ride.started_ms, 1_497_283_762_000);
    }

    #[test]
    fn something_that_is_not_a_fit_file_holds_no_workouts() {
        assert!(parse_fit_workouts(b"<gpx></gpx>").is_empty());
        assert!(parse_fit_workouts(&[]).is_empty());
        // A truncated download must not panic; whatever it yields is fine.
        let _ = parse_fit_workouts(&RUN[..RUN.len() / 2]);
    }

    #[test]
    fn heart_rate_outside_what_a_heart_does_is_ignored() {
        let mut r = FitDataRecord::new(MesgNum::Record);
        r.push(fitparser::FitDataField::new("heart_rate".into(), 3, None, Value::UInt8(255), "bpm".into()));
        let mut low = FitDataRecord::new(MesgNum::Record);
        low.push(fitparser::FitDataField::new("heart_rate".into(), 3, None, Value::UInt8(9), "bpm".into()));
        let mut ok = FitDataRecord::new(MesgNum::Record);
        ok.push(fitparser::FitDataField::new("heart_rate".into(), 3, None, Value::UInt8(120), "bpm".into()));
        assert_eq!(heart_rate_of([&r, &low, &ok].into_iter()), (Some(120), Some(120)));
    }
}
