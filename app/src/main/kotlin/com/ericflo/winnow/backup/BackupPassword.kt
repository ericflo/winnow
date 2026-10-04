package com.ericflo.winnow.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The backup password (Settings → Backup), kept so automatic backups can be protected without
 * asking: never the password itself, but the key made from it, sealed by a key in Android's
 * Keystore that can't leave this phone. Another phone needs the password to open a backup.
 */
class BackupPassword(context: Context) {
    private val prefs = context.getSharedPreferences("backup_password", Context.MODE_PRIVATE)
    private val _isSet = MutableStateFlow(prefs.contains(KEY_SEALED))
    /** Whether new backups are protected. */
    val isSet: StateFlow<Boolean> = _isSet.asStateFlow()

    /** Protects backups from now on with [password] (slow: about a second, off the main thread). */
    suspend fun set(password: CharArray) = withContext(Dispatchers.Default) {
        val params = BackupCrypto.newParams()
        val key = BackupCrypto.deriveKey(password, params)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.doFinal(key.secret.encoded)
        prefs.edit()
            .putString(KEY_SALT, encode(params.salt))
            .putInt(KEY_ITERATIONS, params.iterations)
            .putString(KEY_IV, encode(cipher.iv))
            .putString(KEY_SEALED, encode(sealed))
            .commit()
        _isSet.value = true
    }

    /** New backups aren't protected; ones already made still need the password they were made with. */
    fun clear() {
        prefs.edit().clear().commit()
        _isSet.value = false
    }

    /** The key backups are protected with, or null if there's no password (or the Keystore lost its key). */
    fun key(): BackupCrypto.Key? = runCatching {
        val salt = prefs.getString(KEY_SALT, null)?.let(::decode) ?: return null
        val iv = prefs.getString(KEY_IV, null)?.let(::decode) ?: return null
        val sealed = prefs.getString(KEY_SEALED, null)?.let(::decode) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv)) }
        BackupCrypto.Key(SecretKeySpec(cipher.doFinal(sealed), "AES"), BackupCrypto.KeyParams(salt, prefs.getInt(KEY_ITERATIONS, BackupCrypto.ITERATIONS)))
    }.getOrNull()

    /** The key for a file made with [params], if it's this phone's password's: no need to ask for it. */
    fun keyFor(params: BackupCrypto.KeyParams): BackupCrypto.Key? =
        key()?.takeIf { it.params.salt.contentEquals(params.salt) && it.params.iterations == params.iterations }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "winnow_backup_password"
        const val KEY_SALT = "salt"
        const val KEY_ITERATIONS = "iterations"
        const val KEY_IV = "iv"
        const val KEY_SEALED = "sealed"
    }
}
