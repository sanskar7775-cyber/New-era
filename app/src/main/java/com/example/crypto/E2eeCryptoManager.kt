package com.example.crypto

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val Context.e2eeDataStore: DataStore<Preferences> by preferencesDataStore(name = "new_era_e2ee_vault")

data class EncryptedPayload(
    val ciphertextBase64: String,
    val ivBase64: String
)

data class WrappedKeyBundle(
    val saltBase64: String,
    val wrappedKeyBase64: String,
    val ivBase64: String,
    val fingerprint: String
)

class E2eeCryptoManager(private val appContext: Context) {

    private val masterKeyPref = stringPreferencesKey("e2ee_master_key_b64")
    private val secureRandom = SecureRandom()

    suspend fun getOrInitLocalMasterKey(userId: String): SecretKey {
        val keyPref = stringPreferencesKey("e2ee_master_key_b64_$userId")
        val prefs = appContext.e2eeDataStore.data.first()
        val existingB64 = prefs[keyPref] ?: prefs[masterKeyPref]
        if (!existingB64.isNullOrBlank()) {
            val bytes = Base64.decode(existingB64, Base64.NO_WRAP)
            if (bytes.size == 32) {
                return SecretKeySpec(bytes, "AES")
            }
        }
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, secureRandom)
        val newKey = keyGen.generateKey()
        val encoded = Base64.encodeToString(newKey.encoded, Base64.NO_WRAP)
        appContext.e2eeDataStore.edit { mutablePrefs ->
            mutablePrefs[keyPref] = encoded
        }
        return newKey
    }

    suspend fun saveMasterKey(userId: String, key: SecretKey) {
        val keyPref = stringPreferencesKey("e2ee_master_key_b64_$userId")
        val encoded = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        appContext.e2eeDataStore.edit { mutablePrefs ->
            mutablePrefs[keyPref] = encoded
        }
    }

    suspend fun hasLocalMasterKey(userId: String): Boolean {
        val keyPref = stringPreferencesKey("e2ee_master_key_b64_$userId")
        val prefs = appContext.e2eeDataStore.data.first()
        return !prefs[keyPref].isNullOrBlank()
    }

    fun getKeyFingerprint(key: SecretKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return digest.take(8).joinToString("") { "%02X".format(it) }
    }

    fun exportRecoveryCode(key: SecretKey): String {
        return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }

    fun importRecoveryCode(recoveryCode: String): SecretKey {
        val decoded = Base64.decode(recoveryCode.trim(), Base64.NO_WRAP)
        require(decoded.size == 32) { "Invalid 256-bit E2EE recovery key." }
        return SecretKeySpec(decoded, "AES")
    }

    fun encrypt(plaintext: String, key: SecretKey): EncryptedPayload {
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String, key: SecretKey): String {
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    fun wrapMasterKeyWithPassphrase(masterKey: SecretKey, passphrase: String): WrappedKeyBundle {
        require(passphrase.length >= 4) { "Sync passphrase must be at least 4 characters." }
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val wrappingKey = deriveKeyFromPassphrase(passphrase, salt)
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
        val wrappedBytes = cipher.doFinal(masterKey.encoded)
        return WrappedKeyBundle(
            saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            wrappedKeyBase64 = Base64.encodeToString(wrappedBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            fingerprint = getKeyFingerprint(masterKey)
        )
    }

    fun unwrapMasterKeyWithPassphrase(
        saltBase64: String,
        wrappedKeyBase64: String,
        ivBase64: String,
        passphrase: String
    ): SecretKey {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val wrappedBytes = Base64.decode(wrappedKeyBase64, Base64.NO_WRAP)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val wrappingKey = deriveKeyFromPassphrase(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
        val rawMasterKey = cipher.doFinal(wrappedBytes)
        return SecretKeySpec(rawMasterKey, "AES")
    }

    private fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray): SecretKey {
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: Exception) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 100_000, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
