import Foundation

/// The shipped version, read from the bundle rather than typed in twice.
///
/// Two places print it and both must agree: the footer of Réglages and the
/// footer of every page of the doctor's report. A literal in either would
/// eventually disagree with the build.
enum AppVersion {
    static let name: String =
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"

    static let build: String =
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
}
