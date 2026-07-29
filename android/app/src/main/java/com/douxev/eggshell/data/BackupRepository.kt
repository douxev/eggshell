package com.douxev.eggshell.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.importEncrypted

@Singleton
class BackupRepository @Inject constructor(
    private val vault: VaultRepository,
    private val settingsBackup: SettingsBackup,
    @ApplicationContext private val context: Context,
) {
    private val settingsDir: File get() = File(context.filesDir, "settings")

    /**
     * The settings snapshot rides along as a third blob kind inside the bundle.
     *
     * It is written where the Rust exporter already looks, rather than pushed
     * across the FFI, so neither the export signature nor iOS has to change —
     * a platform with no settings/ directory simply contributes nothing and
     * keeps working. Sealed with the vault key like every other blob, so the
     * file is never readable at rest even in the window before the export
     * finishes.
     */
    private fun writeSettingsSnapshot() {
        runCatching {
            settingsDir.mkdirs()
            val sealed = vault.requireSession().encryptBlob(settingsBackup.snapshot())
            File(settingsDir, "app-settings.bin").writeBytes(sealed)
        }
    }

    private fun clearSettingsSnapshot() {
        runCatching { settingsDir.listFiles()?.forEach { it.delete() } }
    }
    /**
     * Export the open vault as an encrypted blob, write it to the dedicated
     * `backup_share` sub-cache, and return the File. Caller hands it to a
     * SAF "save to…" picker or ACTION_SEND.
     */
    suspend fun exportToCache(passphrase: String): File = withContext(Dispatchers.IO) {
        writeSettingsSnapshot()
        val bytes = try {
            vault.requireSession().exportEncrypted(passphrase)
        } finally {
            // Never leave it lying around: outside an export it has no reason
            // to exist, and the orphan reaper does not know this directory.
            clearSettingsSnapshot()
        }
        val dir = File(context.cacheDir, "backup_share").apply { mkdirs() }
        // Sweep anything from a previous share first. The bundle is encrypted,
        // but it is a complete copy of the vault and it was never purged — one
        // "share a backup" tap left it in the cache indefinitely, reachable by
        // anything that can read the app's cache directory.
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        val out = File(dir, "eggshell-${System.currentTimeMillis()}.transition.enc")
        out.writeBytes(bytes)
        out.deleteOnExit()
        out
    }

    /**
     * Import a previously-exported bundle.
     *
     * The Rust import returns the original 32-byte master key that was used
     * to encrypt the SQLCipher DB on the source device. Without that key the
     * restored DB cannot be opened — historically this was the bug behind the
     * "Failed: database: file is not in database" error users saw: after a
     * restore, the locally-Keystore-wrapped key still pointed at the
     * pre-import key, which SQLCipher rightly refused.
     *
     * The fix is to immediately re-wrap the imported master key under the
     * local Keystore (and reset the security mode to KEYSTORE_ONLY so the
     * next unlock auto-opens). We do this in a tmp-then-rename atomic
     * write so a crash mid-import doesn't leave the user with a corrupted
     * vault.db and no recovery path.
     */
    suspend fun importFromUri(uri: Uri, passphrase: String) = withContext(Dispatchers.IO) {
        val bundle = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("could not read $uri")
        val targetPath = File(context.filesDir, "vault.db").absolutePath
        // NB: we do NOT lock the session up-front. `importEncrypted` decrypts
        // and validates the passphrase BEFORE writing the DB, so a wrong
        // passphrase throws here with the current session still intact — the
        // app stays usable instead of being stranded with no session. On
        // success, `restoreFromImportedKey` drops the (now-stale) session. The
        // brief overlap where the old SQLCipher handle is still open while Rust
        // atomically renames the new vault.db over it is safe: the old handle
        // keeps the unlinked inode, the next unlock opens the new one.
        val imported = importEncrypted(bundle, passphrase, targetPath)
        try {
            // Apply the settings before the key is re-wrapped and the process
            // restarts. The snapshot is sealed with the imported master key, so
            // this is the one moment we hold what is needed to open it.
            runCatching {
                val sealed = File(settingsDir, "app-settings.bin")
                if (sealed.isFile) {
                    val key = uniffi.transition.VaultKey.fromRaw(imported.masterKey)
                    val plain = uniffi.transition.Vault(targetPath, key).decryptBlob(sealed.readBytes())
                    settingsBackup.restore(plain)
                }
            }
            clearSettingsSnapshot()
            vault.restoreFromImportedKey(imported.masterKey)
        } finally {
            // Best-effort wipe of the master key copy that crossed the FFI.
            // Kotlin garbage-collection will eventually reclaim it, but
            // until then it sits in heap — fill it with zeros so a heap
            // dump can't recover it.
            imported.masterKey.fill(0)
        }
    }
}
