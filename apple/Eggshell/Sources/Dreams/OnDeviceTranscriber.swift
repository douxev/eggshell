import Foundation
import Speech

/// Speech-to-text for dream voice notes, **on the device and nowhere else**.
///
/// `SFSpeechRecognizer` defaults to Apple's servers. For this app that is not a
/// trade-off but a contradiction: the vault is encrypted, the reminders are
/// deliberately generic, and a dream is the most revealing thing any of it
/// holds. Sending one away to be typed out would undo all of it in a tap.
///
/// So `requiresOnDeviceRecognition` is forced on, and when the device cannot
/// honour it the answer is [Result.unavailable] — never a network fallback,
/// which is the one behaviour this type exists to prevent. That has real costs
/// the UI states rather than hides: the language model has to be installed, and
/// on-device recognition is generally less accurate than the server model.
///
/// Mirrors Android's `OnDeviceTranscriber`, down to the three reasons, so the
/// two platforms refuse for the same articulable causes.
@MainActor
final class OnDeviceTranscriber {

    enum Reason {
        /// The user has not granted speech-recognition permission.
        case notAuthorized
        /// No recogniser for this locale at all.
        case noRecognizer
        /// The recogniser exists but has no on-device model installed.
        case languageNotDownloaded
    }

    enum Result {
        case text(String)
        case unavailable(Reason)
        case noSpeech
        case failed(String)
    }

    /// Cheap enough to call while composing a row. Returns nil when usable.
    ///
    /// Deliberately does *not* prompt: a permission dialog that appears while
    /// someone is scrolling a list of their dreams is startling, and the answer
    /// is only needed once they tap Transcribe.
    func availability(locale: Locale = .current) -> Reason? {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized: break
        case .notDetermined: break  // asked for at the point of use
        default: return .notAuthorized
        }
        guard let recognizer = SFSpeechRecognizer(locale: locale) else { return .noRecognizer }
        guard recognizer.isAvailable else { return .noRecognizer }
        guard recognizer.supportsOnDeviceRecognition else { return .languageNotDownloaded }
        return nil
    }

    /// Transcribe a **plaintext** audio file the caller is responsible for
    /// deleting afterwards.
    func transcribe(url: URL, locale: Locale = .current) async -> Result {
        if case .notDetermined = SFSpeechRecognizer.authorizationStatus() {
            let granted = await withCheckedContinuation { cont in
                SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0) }
            }
            guard granted == .authorized else { return .unavailable(.notAuthorized) }
        }
        if let reason = availability(locale: locale) { return .unavailable(reason) }
        guard let recognizer = SFSpeechRecognizer(locale: locale) else {
            return .unavailable(.noRecognizer)
        }

        let request = SFSpeechURLRecognitionRequest(url: url)
        // The line that matters. Without it the audio goes to Apple.
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false

        return await withCheckedContinuation { cont in
            // Resumed exactly once: the callback fires repeatedly, and a
            // continuation resumed twice is a crash rather than a warning.
            var finished = false
            recognizer.recognitionTask(with: request) { result, error in
                guard !finished else { return }
                if let error {
                    finished = true
                    cont.resume(returning: .failed(error.localizedDescription))
                    return
                }
                guard let result, result.isFinal else { return }
                finished = true
                let text = result.bestTranscription.formattedString
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                cont.resume(returning: text.isEmpty ? .noSpeech : .text(text))
            }
        }
    }

    static func message(for reason: Reason) -> String {
        switch reason {
        case .notAuthorized:
            return "La transcription a besoin de l’autorisation de reconnaissance vocale. L’audio reste enregistré et chiffré."
        case .noRecognizer:
            return "Pas de moteur de transcription pour cette langue. On n’envoie rien à un serveur, donc l’audio reste sans texte."
        case .languageNotDownloaded:
            return "Le modèle de langue n’est pas installé sur cet appareil. Ajoute-le dans Réglages → Général → Clavier → Dictée."
        }
    }
}
