import Foundation

// `Appointment.todo` is one string, one task per line — there is no table for
// this and the refonte does not add one. A ticked line is prefixed `- [x] `,
// an open one `- [ ] `; a line written before this release carries no marker
// at all and reads as open, so nothing anybody typed is ever lost.
//
// Kept byte-compatible with the Android `appointmentTodoItems` /
// `renderAppointmentTodo` pair: the same vault is read by both platforms.

/// One line of `Appointment.todo`, with its tick.
struct AppointmentTodo: Hashable, Identifiable {
    let label: String
    let done: Bool

    /// Stable enough for a `ForEach` over a list the user edits one tick at a
    /// time; the index is folded in by the caller when labels can repeat.
    var id: String { (done ? "x " : "o ") + label }
}

/// Parses the stored string. Unknown shapes are kept verbatim as open tasks —
/// a line we fail to recognise is still something the user wrote.
func appointmentTodoItems(_ raw: String?) -> [AppointmentTodo] {
    guard let raw, !raw.isEmpty else { return [] }
    return raw
        .split(separator: "\n", omittingEmptySubsequences: false)
        .compactMap { parseAppointmentTodoLine(String($0)) }
}

/// Renders back, always with an explicit marker so the tick survives a reload.
func renderAppointmentTodo(_ items: [AppointmentTodo]) -> String? {
    guard !items.isEmpty else { return nil }
    return items
        .map { ($0.done ? "- [x] " : "- [ ] ") + $0.label }
        .joined(separator: "\n")
}

private func parseAppointmentTodoLine(_ line: String) -> AppointmentTodo? {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    if trimmed.isEmpty { return nil }

    var body = Substring(trimmed)
    var done = false

    if let bullet = body.first, bullet == "-" || bullet == "*" {
        var afterBullet = body.dropFirst()
        while afterBullet.first == " " { afterBullet = afterBullet.dropFirst() }
        if afterBullet.first == "[" {
            let inner = afterBullet.dropFirst()
            if let close = inner.firstIndex(of: "]") {
                let mark = inner[inner.startIndex..<close].trimmingCharacters(in: .whitespaces)
                let ticked = mark.caseInsensitiveCompare("x") == .orderedSame
                if mark.isEmpty || ticked {
                    done = ticked
                    body = inner[inner.index(after: close)...]
                }
            }
        }
    }

    let label = body.trimmingCharacters(in: .whitespaces)
    return label.isEmpty ? nil : AppointmentTodo(label: label, done: done)
}
