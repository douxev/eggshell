import Foundation
import LocalAuthentication
import Security

// Thin wrapper over the iOS Keychain for storing the symmetric *wrapping key*
// that protects the vault master key. Mirrors the role of Android's Keystore:
// the wrapping key never leaves the Keychain in plaintext and (in biometric
// mode) is gated behind Face ID / Touch ID via a SecAccessControl.
enum Keychain {
    enum KeychainError: Error {
        case unexpectedStatus(OSStatus)
        case userCancelled
        case notFound
    }

    /// Add or replace a raw secret under (service, account). `accessControl`,
    /// when supplied, binds retrieval to user presence / biometrics.
    static func set(
        _ data: Data,
        service: String,
        account: String,
        accessControl: SecAccessControl? = nil
    ) throws {
        var add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
        ]
        if let accessControl {
            add[kSecAttrAccessControl as String] = accessControl
        } else {
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        }

        // Idempotent: delete any prior item first.
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ] as CFDictionary)

        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }

    /// Read a secret. Pass an `LAContext` (already evaluated, or fresh) to
    /// satisfy a biometric SecAccessControl without a second prompt.
    static func get(
        service: String,
        account: String,
        context: LAContext? = nil,
        prompt: String? = nil
    ) throws -> Data {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        if let context {
            query[kSecUseAuthenticationContext as String] = context
        }
        if let prompt {
            query[kSecUseOperationPrompt as String] = prompt
        }

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { throw KeychainError.notFound }
            return data
        case errSecItemNotFound:
            throw KeychainError.notFound
        case errSecUserCanceled, errSecAuthFailed:
            throw KeychainError.userCancelled
        default:
            throw KeychainError.unexpectedStatus(status)
        }
    }

    static func delete(service: String, account: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ] as CFDictionary)
    }

    static func exists(service: String, account: String) -> Bool {
        let status = SecItemCopyMatching([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] as CFDictionary, nil)
        return status == errSecSuccess
    }

    /// Build a SecAccessControl. `biometric == true` requires a currently
    /// enrolled biometric (invalidated if the biometric set changes — matching
    /// Android's `setUserAuthenticationParameters(AUTH_BIOMETRIC_STRONG)`).
    static func accessControl(biometric: Bool) throws -> SecAccessControl {
        let flags: SecAccessControlCreateFlags = biometric ? [.biometryCurrentSet] : []
        var error: Unmanaged<CFError>?
        guard let ac = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            flags,
            &error
        ) else {
            throw error!.takeRetainedValue() as Error
        }
        return ac
    }
}
