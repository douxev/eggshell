import Foundation

// Mirrors android/.../security/PinRateLimiter.kt.
// 3 free attempts, then exponential backoff; wipe the vault after 12 failures.
struct PinRateLimiter {
    private let d: UserDefaults
    init(suite: String = "com.douxev.eggshell.pin") {
        d = UserDefaults(suiteName: suite) ?? .standard
    }

    private static let freeAttempts = 3
    private static let wipeThreshold = 12
    // Backoff after the free attempts are exhausted (seconds).
    private static let backoff: [TimeInterval] = [5, 30, 120, 600, 3600]

    private enum K { static let failures = "failures"; static let lockUntil = "lock_until_ms" }

    private var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Remaining lockout in ms, or 0 if the user may try now.
    var remainingLockMs: Int64 {
        let until = Int64(d.double(forKey: K.lockUntil))
        return max(0, until - nowMs)
    }

    var failures: Int { d.integer(forKey: K.failures) }

    /// Record a failed attempt. Returns `.wipe` once the threshold is crossed.
    enum Outcome { case allowed, locked(ms: Int64), wipe }

    @discardableResult
    mutating func recordFailure() -> Outcome {
        let n = failures + 1
        d.set(n, forKey: K.failures)
        if n >= Self.wipeThreshold { return .wipe }
        if n > Self.freeAttempts {
            let idx = min(n - Self.freeAttempts - 1, Self.backoff.count - 1)
            let lockMs = Int64(Self.backoff[idx] * 1000)
            d.set(Double(nowMs + lockMs), forKey: K.lockUntil)
            return .locked(ms: lockMs)
        }
        return .allowed
    }

    mutating func recordSuccess() {
        d.removeObject(forKey: K.failures)
        d.removeObject(forKey: K.lockUntil)
    }

    func reset() {
        d.removeObject(forKey: K.failures)
        d.removeObject(forKey: K.lockUntil)
    }
}
