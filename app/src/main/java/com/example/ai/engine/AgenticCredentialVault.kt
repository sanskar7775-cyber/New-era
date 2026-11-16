package com.example.ai.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.crypto.E2eeCryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKey

private val Context.gatewayVaultDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "new_era_agentic_gateway_vault"
)

enum class GatewayChannel(
    val title: String,
    val protocol: String,
    val scopes: List<String>
) {
    FIREBASE_APP_CHECK("Firebase App Check Proxy", "Keyless Attestation", listOf("appcheck.attest", "ai.invoke")),
    UNIFIED_LLM_GATEWAY("Unified Agent Gateway", "Zero-Key Multi-Model Router", listOf("llm.system2", "media.synth")),
    GOOGLE_WORKSPACE_DELEGATE("Workspace OAuth Delegation", "Scoped Per-App Session", listOf("gmail", "calendar", "drive", "docs")),
    WEB_GROUNDING_SANDBOX("Web Search Proxy", "Read-Only Sanitized Fetch", listOf("http.get.safe")),
    HARDWARE_SECRET_ENCLAVE("Encrypted Agent Enclave", "AES-256-GCM Zero-Leak Vault", listOf("vault.seal", "token.rotate"))
}

data class ScopedEphemeralToken(
    val channel: GatewayChannel,
    val maskedToken: String,
    val encryptedPayloadB64: String,
    val encryptedIvB64: String,
    val scopes: List<String>,
    val provisionedAtMillis: Long,
    val expiresAtMillis: Long,
    val active: Boolean = true
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAtMillis
}

class AgenticCredentialVault(
    private val context: Context,
    private val cryptoManager: E2eeCryptoManager
) {
    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()

    val tokensFlow: Flow<List<ScopedEphemeralToken>> = appContext.gatewayVaultDataStore.data.map { prefs ->
        val raw = prefs[KEY_TOKENS_JSON]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    suspend fun ensureJustInTimeProvisioned(userId: String): List<ScopedEphemeralToken> {
        val current = tokensFlow.first()
        if (current.size == GatewayChannel.entries.size && current.none { it.isExpired }) {
            return current
        }
        val key = cryptoManager.getOrInitLocalMasterKey(userId)
        val provisioned = GatewayChannel.entries.map { channel ->
            createEncryptedEphemeralToken(channel, key)
        }
        saveTokens(provisioned)
        return provisioned
    }

    suspend fun rotateChannelToken(userId: String, channel: GatewayChannel): ScopedEphemeralToken {
        val key = cryptoManager.getOrInitLocalMasterKey(userId)
        val fresh = createEncryptedEphemeralToken(channel, key)
        val current = tokensFlow.first().filterNot { it.channel == channel }
        saveTokens(listOf(fresh) + current)
        return fresh
    }

    suspend fun revokeChannelToken(channel: GatewayChannel) {
        val updated = tokensFlow.first().map { token ->
            if (token.channel == channel) token.copy(active = false) else token
        }
        saveTokens(updated)
    }

    private fun createEncryptedEphemeralToken(
        channel: GatewayChannel,
        key: SecretKey
    ): ScopedEphemeralToken {
        val randomBytes = ByteArray(18)
        secureRandom.nextBytes(randomBytes)
        val hex = randomBytes.joinToString("") { "%02x".format(it) }
        val rawEphemeral = "ne_eph_${channel.name.take(4).lowercase()}_$hex"
        val masked = "${rawEphemeral.take(10)}••••${rawEphemeral.takeLast(4).uppercase()}"
        val encrypted = cryptoManager.encrypt(rawEphemeral, key)
        val now = System.currentTimeMillis()
        return ScopedEphemeralToken(
            channel = channel,
            maskedToken = masked,
            encryptedPayloadB64 = encrypted.ciphertextBase64,
            encryptedIvB64 = encrypted.ivBase64,
            scopes = channel.scopes,
            provisionedAtMillis = now,
            expiresAtMillis = now + 60 * 60 * 1000L, // 1 hour ephemeral TTL
            active = true
        )
    }

    private suspend fun saveTokens(tokens: List<ScopedEphemeralToken>) {
        appContext.gatewayVaultDataStore.edit { prefs ->
            prefs[KEY_TOKENS_JSON] = encode(tokens)
        }
    }

    private fun encode(tokens: List<ScopedEphemeralToken>): String {
        val arr = JSONArray()
        tokens.forEach { t ->
            arr.put(JSONObject().apply {
                put("channel", t.channel.name)
                put("maskedToken", t.maskedToken)
                put("cipher", t.encryptedPayloadB64)
                put("iv", t.encryptedIvB64)
                put("scopes", JSONArray(t.scopes))
                put("provisionedAtMillis", t.provisionedAtMillis)
                put("expiresAtMillis", t.expiresAtMillis)
                put("active", t.active)
            })
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<ScopedEphemeralToken> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val channel = runCatching { GatewayChannel.valueOf(o.optString("channel")) }
                .getOrNull() ?: return@mapNotNull null
            val sArr = o.optJSONArray("scopes") ?: JSONArray()
            val scopes = (0 until sArr.length()).mapNotNull { s -> sArr.optString(s).takeIf { it.isNotBlank() } }
            ScopedEphemeralToken(
                channel = channel,
                maskedToken = o.optString("maskedToken"),
                encryptedPayloadB64 = o.optString("cipher"),
                encryptedIvB64 = o.optString("iv"),
                scopes = scopes,
                provisionedAtMillis = o.optLong("provisionedAtMillis", 0L),
                expiresAtMillis = o.optLong("expiresAtMillis", 0L),
                active = o.optBoolean("active", true)
            )
        }
    }

    companion object {
        private val KEY_TOKENS_JSON = stringPreferencesKey("scoped_ephemeral_tokens_v1")
    }
}
