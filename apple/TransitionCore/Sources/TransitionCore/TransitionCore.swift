// Hand-written companion to the generated uniffi bindings.
//
// `apple/build-ios.sh` drops the generated `transition.swift` next to this file
// (it is gitignored and regenerated every build). This file exists so the
// SwiftPM target always has at least one committed source — and as a home for
// small Swift-side conveniences on top of the raw FFI.

/// Version of the bindings contract this app was built against. Bump when the
/// UDL surface changes in a way the Swift app must adapt to.
///
/// 0.0.8: schema v14 — `DoseSchedule`/`NewDoseSchedule` gain `label` (custom
/// reminder text); new Vault methods `logDoses`, `updateDose`, `getDose`,
/// `updateSchedule`, `addBleedingEntries`.
///
/// 0.0.9: schema v17 — the sport tables (`SportActivity`, `SportSession`,
/// `StepDay` and their Vault methods) and the passphrase-change trio of free
/// functions `rekeyVault`, `finishPendingRekey`, `discardPendingRekey` with
/// `RekeyReport`.
///
/// 0.0.10: the free function `parseFitWorkouts(data:)` and its `FitWorkout`
/// record — Garmin `.fit` decoding for the sport import. No schema change.
public enum TransitionCoreInfo {
    public static let bindingsContract = "0.0.10"
}
