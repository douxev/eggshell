//! Cryptographic primitives for Transition.
//!
//! Two layers:
//! - **Key derivation** (`KdfParams`): Argon2id turns a user passphrase into a
//!   32-byte master key. Salt and cost parameters are stored alongside the
//!   ciphertext (they are not secret).
//! - **Authenticated encryption** (`encrypt` / `decrypt`): AES-256-GCM with a
//!   fresh 96-bit random nonce per call. Used for file blobs (photos, audio,
//!   logs) and for wrapping the SQLCipher key under the user's master key.
//!
//! Master keys are held in [`Zeroizing`] buffers so they are wiped from memory
//! when dropped — important for the "paranoid mode" described in the plan, where
//! the key must not linger after the app pauses.

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use hkdf::Hkdf;
use rand::RngCore;
use rand::rngs::OsRng;
use sha2::Sha256;
use zeroize::{Zeroize, Zeroizing};

use crate::TransitionError;

/// Length of the symmetric key used by both AES-256-GCM and SQLCipher.
pub const KEY_LEN: usize = 32;
/// AES-GCM nonce length (96 bits, NIST-recommended).
pub const NONCE_LEN: usize = 12;
/// Argon2id salt length.
pub const SALT_LEN: usize = 16;

/// A 32-byte symmetric key. The inner buffer is zeroized on drop.
///
/// `MasterKey` deliberately does not implement `Clone` or `Copy` — every clone
/// would be a new heap allocation we'd need to wipe. If a caller needs two
/// handles, they should use a reference or wrap the key in `Arc`.
pub struct MasterKey(Zeroizing<[u8; KEY_LEN]>);

impl MasterKey {
    /// Generate a fresh random key from the OS CSPRNG.
    pub fn generate() -> Self {
        let mut bytes = [0u8; KEY_LEN];
        OsRng.fill_bytes(&mut bytes);
        Self(Zeroizing::new(bytes))
    }

    /// Wrap an existing 32-byte buffer. Used after deriving a key via `KdfParams`
    /// or after unwrapping a key from the Keystore.
    pub fn from_bytes(bytes: [u8; KEY_LEN]) -> Self {
        Self(Zeroizing::new(bytes))
    }

    /// Borrow the raw key material. Restrict the lifetime of this borrow so the
    /// key is wiped as soon as the caller is done.
    pub fn expose(&self) -> &[u8; KEY_LEN] {
        &self.0
    }

    /// Derive a domain-separated sub-key via HKDF-SHA256. Use this whenever a
    /// distinct purpose touches the same root key — for example the DB key vs.
    /// the file-blob key — so an oracle leak on one side cannot be replayed
    /// against the other.
    pub fn derive_subkey(&self, info: &[u8]) -> Self {
        let hk = Hkdf::<Sha256>::new(None, self.expose());
        let mut out = [0u8; KEY_LEN];
        hk.expand(info, &mut out).expect("HKDF expand 32 bytes never fails");
        let key = MasterKey::from_bytes(out);
        let mut out = out;
        out.zeroize();
        key
    }
}

/// Argon2id parameters + the salt. The salt is not secret and is persisted
/// next to the wrapped key so we can re-derive on unlock.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KdfParams {
    pub salt: [u8; SALT_LEN],
    pub m_cost_kib: u32,
    pub t_cost: u32,
    pub p_cost: u32,
}

impl KdfParams {
    /// OWASP 2024 recommended parameters for Argon2id: 64 MiB memory,
    /// 3 iterations, 4 lanes. Suitable for a phone — completes in well under
    /// a second on a modern Pixel, while remaining painful for offline brute
    /// force.
    pub fn recommended() -> Self {
        let mut salt = [0u8; SALT_LEN];
        OsRng.fill_bytes(&mut salt);
        Self {
            salt,
            m_cost_kib: 64 * 1024,
            t_cost: 3,
            p_cost: 4,
        }
    }

    /// Recreate parameters that were persisted earlier (e.g. read from the DB).
    /// The caller is responsible for providing the same salt that produced
    /// the original key.
    pub fn from_persisted(salt: [u8; SALT_LEN], m_cost_kib: u32, t_cost: u32, p_cost: u32) -> Self {
        Self { salt, m_cost_kib, t_cost, p_cost }
    }

    /// Derive a 32-byte master key from a passphrase.
    ///
    /// The passphrase is consumed by reference; callers should keep it in a
    /// `Zeroizing<String>` or similar and drop it immediately after.
    pub fn derive(&self, passphrase: &[u8]) -> Result<MasterKey, TransitionError> {
        let params = Params::new(self.m_cost_kib, self.t_cost, self.p_cost, Some(KEY_LEN))
            .map_err(|e| TransitionError::Crypto(format!("argon2 params: {e}")))?;
        let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
        let mut out = [0u8; KEY_LEN];
        argon
            .hash_password_into(passphrase, &self.salt, &mut out)
            .map_err(|e| TransitionError::Crypto(format!("argon2 derive: {e}")))?;
        let key = MasterKey::from_bytes(out);
        // `out` was copied into MasterKey's Zeroizing buffer; wipe our stack copy too.
        let mut out = out;
        out.zeroize();
        Ok(key)
    }
}

/// A self-contained authenticated ciphertext. The nonce is stored alongside
/// the ciphertext because it's required for decryption and is single-use, not
/// secret.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EncryptedBlob {
    pub nonce: [u8; NONCE_LEN],
    /// Ciphertext with the 16-byte GCM authentication tag appended.
    pub ciphertext: Vec<u8>,
}

impl EncryptedBlob {
    /// Encode as `nonce || ciphertext` for storage on disk.
    pub fn to_vec(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(NONCE_LEN + self.ciphertext.len());
        out.extend_from_slice(&self.nonce);
        out.extend_from_slice(&self.ciphertext);
        out
    }

    /// Parse the `nonce || ciphertext` layout produced by [`to_vec`].
    pub fn from_slice(bytes: &[u8]) -> Result<Self, TransitionError> {
        if bytes.len() < NONCE_LEN {
            return Err(TransitionError::Crypto(
                "encrypted blob shorter than nonce length".into(),
            ));
        }
        let mut nonce = [0u8; NONCE_LEN];
        nonce.copy_from_slice(&bytes[..NONCE_LEN]);
        Ok(Self { nonce, ciphertext: bytes[NONCE_LEN..].to_vec() })
    }
}

/// AEAD encrypt `plaintext` under `key` with a fresh random nonce.
pub fn encrypt(key: &MasterKey, plaintext: &[u8]) -> Result<EncryptedBlob, TransitionError> {
    encrypt_with_aad(key, plaintext, b"")
}

/// AEAD decrypt and authenticate. Returns the plaintext in a zeroizing buffer.
pub fn decrypt(key: &MasterKey, blob: &EncryptedBlob) -> Result<Zeroizing<Vec<u8>>, TransitionError> {
    decrypt_with_aad(key, blob, b"")
}

/// AEAD encrypt with associated data. The AAD is authenticated but not
/// encrypted — pass any header/version/parameter bytes you need to bind
/// cryptographically to the ciphertext so they cannot be tampered with
/// in transit (downgrade, parameter substitution).
pub fn encrypt_with_aad(
    key: &MasterKey,
    plaintext: &[u8],
    aad: &[u8],
) -> Result<EncryptedBlob, TransitionError> {
    let cipher = Aes256Gcm::new(key.expose().into());
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload { msg: plaintext, aad },
        )
        .map_err(|e| TransitionError::Crypto(format!("aes-gcm encrypt: {e}")))?;
    Ok(EncryptedBlob { nonce, ciphertext })
}

/// AEAD decrypt with associated data. The same AAD passed to
/// [`encrypt_with_aad`] MUST be supplied here — any difference (including
/// the empty/non-empty distinction) fails authentication.
pub fn decrypt_with_aad(
    key: &MasterKey,
    blob: &EncryptedBlob,
    aad: &[u8],
) -> Result<Zeroizing<Vec<u8>>, TransitionError> {
    let cipher = Aes256Gcm::new(key.expose().into());
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&blob.nonce),
            Payload { msg: blob.ciphertext.as_ref(), aad },
        )
        .map_err(|_| TransitionError::Crypto("aes-gcm decrypt failed (wrong key or tampered ciphertext)".into()))?;
    Ok(Zeroizing::new(plaintext))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let key = MasterKey::generate();
        let plaintext = b"transition payload";
        let blob = encrypt(&key, plaintext).expect("encrypt");
        let recovered = decrypt(&key, &blob).expect("decrypt");
        assert_eq!(&recovered[..], plaintext);
    }

    #[test]
    fn different_keys_fail_to_decrypt() {
        let k1 = MasterKey::generate();
        let k2 = MasterKey::generate();
        let blob = encrypt(&k1, b"secret").unwrap();
        assert!(decrypt(&k2, &blob).is_err());
    }

    #[test]
    fn tampered_ciphertext_fails() {
        let key = MasterKey::generate();
        let mut blob = encrypt(&key, b"payload").unwrap();
        blob.ciphertext[0] ^= 0xff;
        assert!(decrypt(&key, &blob).is_err());
    }

    #[test]
    fn blob_serialization_roundtrip() {
        let key = MasterKey::generate();
        let blob = encrypt(&key, b"hello").unwrap();
        let bytes = blob.to_vec();
        let parsed = EncryptedBlob::from_slice(&bytes).unwrap();
        assert_eq!(blob, parsed);
        assert_eq!(&decrypt(&key, &parsed).unwrap()[..], b"hello");
    }

    #[test]
    fn kdf_is_deterministic_for_same_salt() {
        // Lower the m_cost for tests so they stay fast (1 MiB instead of 64 MiB).
        let params = KdfParams::from_persisted([7u8; SALT_LEN], 1024, 1, 1);
        let k1 = params.derive(b"correct horse").unwrap();
        let k2 = params.derive(b"correct horse").unwrap();
        assert_eq!(k1.expose(), k2.expose());
    }

    #[test]
    fn kdf_differs_for_different_passphrases() {
        let params = KdfParams::from_persisted([7u8; SALT_LEN], 1024, 1, 1);
        let k1 = params.derive(b"correct horse").unwrap();
        let k2 = params.derive(b"battery staple").unwrap();
        assert_ne!(k1.expose(), k2.expose());
    }

    #[test]
    fn kdf_differs_for_different_salts() {
        let p1 = KdfParams::from_persisted([1u8; SALT_LEN], 1024, 1, 1);
        let p2 = KdfParams::from_persisted([2u8; SALT_LEN], 1024, 1, 1);
        let k1 = p1.derive(b"pw").unwrap();
        let k2 = p2.derive(b"pw").unwrap();
        assert_ne!(k1.expose(), k2.expose());
    }

    #[test]
    fn from_slice_rejects_too_short_input() {
        let too_short = vec![0u8; NONCE_LEN - 1];
        assert!(EncryptedBlob::from_slice(&too_short).is_err());
    }

    #[test]
    fn aad_must_match_to_decrypt() {
        let key = MasterKey::generate();
        let blob = encrypt_with_aad(&key, b"payload", b"header-v2").unwrap();
        assert!(decrypt_with_aad(&key, &blob, b"header-v2").is_ok());
        assert!(decrypt_with_aad(&key, &blob, b"header-v1").is_err());
        assert!(decrypt_with_aad(&key, &blob, b"").is_err());
    }

    #[test]
    fn empty_aad_is_compatible_with_plain_encrypt() {
        let key = MasterKey::generate();
        let blob = encrypt(&key, b"hi").unwrap();
        assert_eq!(&decrypt_with_aad(&key, &blob, b"").unwrap()[..], b"hi");
    }

    #[test]
    fn derive_subkey_is_deterministic_per_info() {
        let root = MasterKey::generate();
        let k1 = root.derive_subkey(b"transition::files::v1");
        let k2 = root.derive_subkey(b"transition::files::v1");
        let k3 = root.derive_subkey(b"transition::logs::v1");
        assert_eq!(k1.expose(), k2.expose());
        assert_ne!(k1.expose(), k3.expose());
        // The sub-key is not the root.
        assert_ne!(k1.expose(), root.expose());
    }
}
