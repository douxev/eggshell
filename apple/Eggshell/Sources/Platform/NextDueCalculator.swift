import Foundation
import TransitionCore

// Pure schedule math, mirroring android/.../reminders/NextDueCalculator.kt.
// Three kinds: "interval" (every N minutes), "daily" (HH:MM each day),
// "days_interval" (every N days at HH:MM, preserving phase).
enum NextDueCalculator {
    private static var cal: Calendar { var c = Calendar.current; return c }

    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    static func ms(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    /// First occurrence strictly after `from` for a freshly created schedule.
    static func firstDue(_ s: NewDoseSchedule, from: Date = Date()) -> Int64 {
        switch s.kind {
        case "interval":
            let mins = Int(s.intervalMinutes ?? 0)
            return ms(from.addingTimeInterval(Double(mins) * 60))
        case "daily":
            return ms(nextDaily(hour: Int(s.dailyHour ?? 0), minute: Int(s.dailyMinute ?? 0), after: from))
        case "days_interval":
            return ms(nextDaily(hour: Int(s.dailyHour ?? 0), minute: Int(s.dailyMinute ?? 0), after: from))
        default:
            return ms(from)
        }
    }

    /// Next due after marking the current occurrence done / after it fired.
    static func advance(_ s: DoseSchedule, now: Date = Date()) -> Int64 {
        switch s.kind {
        case "interval":
            let mins = Int(s.intervalMinutes ?? 0)
            return ms(now.addingTimeInterval(Double(mins) * 60))
        case "daily":
            return ms(nextDaily(hour: Int(s.dailyHour ?? 0), minute: Int(s.dailyMinute ?? 0), after: now))
        case "days_interval":
            let days = max(1, Int(s.intervalDays ?? 1))
            var next = date(s.nextDueAtMs)
            // Advance whole periods from the scheduled due to keep phase.
            while next <= now {
                next = cal.date(byAdding: .day, value: days, to: next) ?? next.addingTimeInterval(Double(days) * 86400)
            }
            return ms(next)
        default:
            return ms(now)
        }
    }

    private static func nextDaily(hour: Int, minute: Int, after: Date) -> Date {
        let c = cal
        var comps = c.dateComponents([.year, .month, .day], from: after)
        comps.hour = hour; comps.minute = minute; comps.second = 0
        let today = c.date(from: comps) ?? after
        return today > after ? today : (c.date(byAdding: .day, value: 1, to: today) ?? after.addingTimeInterval(86400))
    }

    /// Human label, e.g. "Toutes les 12 h", "Tous les jours à 8:00", "Tous les 3 j à 8:00".
    static func describe(_ s: DoseSchedule) -> String {
        switch s.kind {
        case "interval":
            let m = Int(s.intervalMinutes ?? 0)
            return m % 60 == 0 ? "Toutes les \(m / 60) h" : "Toutes les \(m) min"
        case "daily":
            return String(format: "Tous les jours à %d:%02d", Int(s.dailyHour ?? 0), Int(s.dailyMinute ?? 0))
        case "days_interval":
            return String(format: "Tous les %d j à %d:%02d", Int(s.intervalDays ?? 1), Int(s.dailyHour ?? 0), Int(s.dailyMinute ?? 0))
        default:
            return "Planning"
        }
    }
}
