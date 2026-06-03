// Hand-written companion to the generated uniffi bindings.
//
// `apple/build-ios.sh` drops the generated `transition.swift` next to this file
// (it is gitignored and regenerated every build). This file exists so the
// SwiftPM target always has at least one committed source — and as a home for
// small Swift-side conveniences on top of the raw FFI.

/// Version of the bindings contract this app was built against. Bump when the
/// UDL surface changes in a way the Swift app must adapt to.
public enum TransitionCoreInfo {
    public static let bindingsContract = "0.0.6"
}
