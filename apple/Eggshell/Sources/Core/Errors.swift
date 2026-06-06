import Foundation
import TransitionCore

/// User-facing French message for any thrown error (core or platform).
func describe(_ error: Error) -> String {
    if let e = error as? TransitionError {
        switch e {
        case .WrongKey:       return "Clé ou phrase secrète incorrecte."
        case .Crypto:         return "Erreur de chiffrement."
        case .Database:       return "Erreur de base de données."
        case .Migration:      return "Échec de migration des données."
        case .VaultBusy:      return "Coffre occupé, réessayez."
        case .Unimplemented:  return "Fonction indisponible."
        }
    }
    switch error {
    case Biometric.BiometricError.cancelled: return "Authentification annulée."
    case Biometric.BiometricError.unavailable: return "Biométrie indisponible."
    case Keychain.KeychainError.userCancelled: return "Authentification annulée."
    case Keychain.KeychainError.notFound: return "Élément du trousseau introuvable."
    case Keychain.KeychainError.unexpectedStatus(let status):
        // -34018 = errSecMissingEntitlement (typically an unsigned simulator build).
        let hint = status == -34018 ? " — build non signé ? Teste via TestFlight." : ""
        return "Trousseau indisponible (code \(status))\(hint)"
    case VaultError.missingPassphrase: return "Phrase secrète requise."
    case VaultError.paranoidRequiresRekey:
        return "Le mode paranoïaque n'est pas disponible pour un import ou un changement de mode."
    default: return (error as NSError).localizedDescription
    }
}

/// True when an error is specifically a wrong key / wrong passphrase.
func isWrongKey(_ error: Error) -> Bool {
    if let e = error as? TransitionError, case .WrongKey = e { return true }
    return false
}
