package com.example.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.userSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "new_era_user_sessions"
)

data class AuthSession(
    val isSignedIn: Boolean = false,
    val uid: String = "",
    val username: String = "",
    val email: String = "",
    val isOwner: Boolean = false,
    val authMethod: String = "none" // "direct", "direct_owner", "google", "none"
)

data class RegisteredUserRecord(
    val uid: String,
    val username: String,
    val authMethod: String = "direct",
    val email: String = "",
    val isOwner: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastActiveMillis: Long = System.currentTimeMillis(),
    val activityCount: Int = 0
)

data class UserActivityEvent(
    val id: String,
    val uid: String,
    val username: String,
    val actionType: String,
    val summary: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class UserDirectorySnapshot(
    val users: List<RegisteredUserRecord> = emptyList(),
    val activities: List<UserActivityEvent> = emptyList()
) {
    val totalUsers: Int
        get() = users.size
}

sealed interface DirectAuthResult {
    data class Success(val session: AuthSession) : DirectAuthResult
    data class Error(val message: String) : DirectAuthResult
}

class UserSessionManager(private val context: Context) {

    private val appContext = context.applicationContext

    val sessionFlow: Flow<AuthSession> = appContext.userSessionDataStore.data.map { prefs ->
        val raw = prefs[KEY_ACTIVE_SESSION]
        if (raw.isNullOrBlank()) AuthSession()
        else runCatching { decodeSession(raw) }.getOrDefault(AuthSession())
    }

    val directoryFlow: Flow<UserDirectorySnapshot> = appContext.userSessionDataStore.data.map { prefs ->
        val raw = prefs[KEY_DIRECTORY_JSON]
        if (raw.isNullOrBlank()) UserDirectorySnapshot()
        else runCatching { decodeDirectory(raw) }.getOrDefault(UserDirectorySnapshot())
    }

    suspend fun getSession(): AuthSession = sessionFlow.first()

    suspend fun getDirectory(): UserDirectorySnapshot = directoryFlow.first()

    suspend fun signInDirect(optionalOwnerCode: String = ""): DirectAuthResult {
        val trimmedCode = optionalOwnerCode.trim()
        if (trimmedCode.isEmpty()) {
            val standardSession = AuthSession(
                isSignedIn = true,
                uid = OwnerAccountPolicy.STANDARD_DIRECT_UID,
                username = "New Era User",
                email = "",
                isOwner = false,
                authMethod = "direct"
            )
            saveActiveSession(standardSession)
            upsertUserAndLogActivity(
                uid = standardSession.uid,
                username = standardSession.username,
                authMethod = "direct",
                isOwner = false,
                actionType = "DIRECT_LOGIN",
                summary = "Entered New Era directly without Google account"
            )
            return DirectAuthResult.Success(standardSession)
        }

        if (!OwnerAccountPolicy.isValidSixDigitCode(trimmedCode)) {
            return DirectAuthResult.Error(
                "Owner code must be 6 digits, or leave it empty for standard access."
            )
        }

        val customHash = getCustomOwnerCodeHash()
        if (!OwnerAccountPolicy.verifyOwnerCode(trimmedCode, customHash)) {
            return DirectAuthResult.Error(
                "Invalid 6-digit Owner Code. Leave blank for standard access or enter the correct 6-digit code."
            )
        }

        val ownerSession = AuthSession(
            isSignedIn = true,
            uid = OwnerAccountPolicy.OWNER_UID,
            username = OwnerAccountPolicy.OWNER_DISPLAY_NAME,
            email = "sanskar7775@gmail.com",
            isOwner = true,
            authMethod = "direct_owner"
        )
        saveActiveSession(ownerSession)
        upsertUserAndLogActivity(
            uid = ownerSession.uid,
            username = ownerSession.username,
            authMethod = "direct_owner",
            email = ownerSession.email,
            isOwner = true,
            actionType = "OWNER_DIRECT_LOGIN",
            summary = "Owner unlocked direct session using 6-digit code"
        )
        return DirectAuthResult.Success(ownerSession)
    }

    suspend fun updateOwnerCode(newSixDigitCode: String): Result<Unit> {
        val clean = newSixDigitCode.trim()
        if (!OwnerAccountPolicy.isValidSixDigitCode(clean)) {
            return Result.failure(IllegalArgumentException("Owner code must be exactly 6 digits."))
        }
        val hash = OwnerAccountPolicy.sha256Hex(clean)
        appContext.userSessionDataStore.edit { prefs ->
            prefs[KEY_OWNER_CODE_SHA256] = hash
        }
        return Result.success(Unit)
    }

    suspend fun activateGoogleUserSession(
        uid: String,
        email: String?,
        displayName: String?
    ): AuthSession {
        val cleanEmail = email.orEmpty().trim()
        val derivedUsername = displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: cleanEmail.substringBefore("@").ifBlank { "user_${uid.take(6)}" }
        val isGoogleOwner = OwnerAccountPolicy.isAuthorizedOwnerEmail(cleanEmail)
        val session = AuthSession(
            isSignedIn = true,
            uid = uid,
            username = derivedUsername,
            email = cleanEmail,
            isOwner = isGoogleOwner,
            authMethod = "google"
        )
        saveActiveSession(session)
        upsertUserAndLogActivity(
            uid = uid,
            username = session.username,
            authMethod = "google",
            email = cleanEmail,
            isOwner = isGoogleOwner,
            actionType = "GOOGLE_SIGN_IN",
            summary = "Signed in with Google ($cleanEmail)"
        )
        return session
    }

    suspend fun recordUserActivity(
        uid: String,
        username: String,
        actionType: String,
        summary: String,
        authMethod: String = "direct",
        isOwner: Boolean = false
    ) {
        if (uid.isBlank() || username.isBlank()) return
        upsertUserAndLogActivity(
            uid = uid,
            username = username,
            authMethod = authMethod,
            isOwner = isOwner,
            actionType = actionType,
            summary = summary
        )
    }

    suspend fun mergeCloudDirectory(
        cloudUsers: List<RegisteredUserRecord>,
        cloudActivities: List<UserActivityEvent>
    ) {
        val current = getDirectory()
        val usersByKey = LinkedHashMap<String, RegisteredUserRecord>()
        current.users.forEach { usersByKey[it.uid] = it }
        cloudUsers.forEach { cloud ->
            val existing = usersByKey[cloud.uid]
            if (existing == null || cloud.lastActiveMillis > existing.lastActiveMillis) {
                usersByKey[cloud.uid] = cloud
            }
        }
        val actMap = LinkedHashMap<String, UserActivityEvent>()
        (cloudActivities + current.activities)
            .sortedByDescending { it.timestampMillis }
            .forEach { ev -> actMap.putIfAbsent(ev.id, ev) }

        saveDirectory(
            UserDirectorySnapshot(
                users = usersByKey.values.sortedByDescending { it.lastActiveMillis },
                activities = actMap.values.take(300)
            )
        )
    }

    suspend fun signOutSession() {
        appContext.userSessionDataStore.edit { prefs ->
            prefs.remove(KEY_ACTIVE_SESSION)
        }
    }

    private suspend fun getCustomOwnerCodeHash(): String? {
        return appContext.userSessionDataStore.data.first()[KEY_OWNER_CODE_SHA256]
    }

    private suspend fun saveActiveSession(session: AuthSession) {
        appContext.userSessionDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_SESSION] = encodeSession(session)
        }
    }

    private suspend fun upsertUserAndLogActivity(
        uid: String,
        username: String,
        authMethod: String,
        email: String = "",
        isOwner: Boolean,
        actionType: String,
        summary: String
    ) {
        val now = System.currentTimeMillis()
        appContext.userSessionDataStore.edit { prefs ->
            val current = prefs[KEY_DIRECTORY_JSON]?.let { raw ->
                runCatching { decodeDirectory(raw) }.getOrNull()
            } ?: UserDirectorySnapshot()

            val existing = current.users.firstOrNull { it.uid == uid }
            val updatedUser = if (existing != null) {
                existing.copy(
                    username = username,
                    authMethod = authMethod,
                    email = email.ifBlank { existing.email },
                    isOwner = existing.isOwner || isOwner,
                    lastActiveMillis = now,
                    activityCount = existing.activityCount + 1
                )
            } else {
                RegisteredUserRecord(
                    uid = uid,
                    username = username,
                    authMethod = authMethod,
                    email = email,
                    isOwner = isOwner,
                    createdAtMillis = now,
                    lastActiveMillis = now,
                    activityCount = 1
                )
            }

            val updatedUsers = (listOf(updatedUser) + current.users.filterNot { it.uid == uid })
                .sortedByDescending { it.lastActiveMillis }

            val newEvent = UserActivityEvent(
                id = "act_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                uid = uid,
                username = username,
                actionType = actionType,
                summary = summary.take(200),
                timestampMillis = now
            )

            val updatedActivities = (listOf(newEvent) + current.activities).take(300)
            prefs[KEY_DIRECTORY_JSON] = encodeDirectory(
                UserDirectorySnapshot(users = updatedUsers, activities = updatedActivities)
            )
        }
    }

    private suspend fun saveDirectory(snapshot: UserDirectorySnapshot) {
        appContext.userSessionDataStore.edit { prefs ->
            prefs[KEY_DIRECTORY_JSON] = encodeDirectory(snapshot)
        }
    }

    private fun encodeSession(s: AuthSession): String {
        return JSONObject().apply {
            put("isSignedIn", s.isSignedIn)
            put("uid", s.uid)
            put("username", s.username)
            put("email", s.email)
            put("isOwner", s.isOwner)
            put("authMethod", s.authMethod)
        }.toString()
    }

    private fun decodeSession(raw: String): AuthSession {
        val o = JSONObject(raw)
        val method = o.optString("authMethod", "none")
        if (method !in setOf("direct", "direct_owner", "google")) return AuthSession()
        return AuthSession(
            isSignedIn = o.optBoolean("isSignedIn", false),
            uid = o.optString("uid", ""),
            username = o.optString("username", ""),
            email = o.optString("email", ""),
            isOwner = o.optBoolean("isOwner", false),
            authMethod = method
        )
    }

    private fun encodeDirectory(snap: UserDirectorySnapshot): String {
        val usersArr = JSONArray().apply {
            snap.users.forEach { u ->
                put(JSONObject().apply {
                    put("uid", u.uid)
                    put("username", u.username)
                    put("authMethod", u.authMethod)
                    put("email", u.email)
                    put("isOwner", u.isOwner)
                    put("createdAtMillis", u.createdAtMillis)
                    put("lastActiveMillis", u.lastActiveMillis)
                    put("activityCount", u.activityCount)
                })
            }
        }
        val actArr = JSONArray().apply {
            snap.activities.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("uid", a.uid)
                    put("username", a.username)
                    put("actionType", a.actionType)
                    put("summary", a.summary)
                    put("timestampMillis", a.timestampMillis)
                })
            }
        }
        return JSONObject().apply {
            put("users", usersArr)
            put("activities", actArr)
        }.toString()
    }

    private fun decodeDirectory(raw: String): UserDirectorySnapshot {
        val root = JSONObject(raw)
        val usersArr = root.optJSONArray("users") ?: JSONArray()
        val users = (0 until usersArr.length()).mapNotNull { i ->
            val o = usersArr.optJSONObject(i) ?: return@mapNotNull null
            RegisteredUserRecord(
                uid = o.optString("uid"),
                username = o.optString("username"),
                authMethod = o.optString("authMethod", "direct"),
                email = o.optString("email", ""),
                isOwner = o.optBoolean("isOwner", false),
                createdAtMillis = o.optLong("createdAtMillis", 0L),
                lastActiveMillis = o.optLong("lastActiveMillis", 0L),
                activityCount = o.optInt("activityCount", 0)
            )
        }
        val actArr = root.optJSONArray("activities") ?: JSONArray()
        val activities = (0 until actArr.length()).mapNotNull { i ->
            val o = actArr.optJSONObject(i) ?: return@mapNotNull null
            UserActivityEvent(
                id = o.optString("id"),
                uid = o.optString("uid"),
                username = o.optString("username"),
                actionType = o.optString("actionType"),
                summary = o.optString("summary"),
                timestampMillis = o.optLong("timestampMillis", 0L)
            )
        }
        return UserDirectorySnapshot(users = users, activities = activities)
    }

    companion object {
        private val KEY_ACTIVE_SESSION = stringPreferencesKey("active_auth_session_v1")
        private val KEY_DIRECTORY_JSON = stringPreferencesKey("user_directory_activity_v1")
        private val KEY_OWNER_CODE_SHA256 = stringPreferencesKey("owner_six_digit_code_sha256_v1")
    }
}
