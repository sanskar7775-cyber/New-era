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
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.userSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "new_era_user_sessions"
)

data class AuthSession(
    val isSignedIn: Boolean = false,
    val uid: String = "",
    val username: String = "",
    val email: String = "",
    val isOwner: Boolean = false,
    val authMethod: String = "none", // "owner_password", "password", "google", "guest"
    val isGuestMode: Boolean = false
)

data class RegisteredUserRecord(
    val uid: String,
    val username: String,
    val passwordSaltHex: String = "",
    val passwordHashHex: String = "",
    val authMethod: String = "password",
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

sealed interface AuthAttemptResult {
    data class Success(val session: AuthSession, val isNewAccount: Boolean) : AuthAttemptResult
    data class Error(val message: String) : AuthAttemptResult
}

class UserSessionManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()

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

    suspend fun signInOrRegisterWithCredentials(
        rawUsername: String,
        rawPassword: String
    ): AuthAttemptResult {
        val username = rawUsername.trim()
        val password = rawPassword
        if (username.length < 3) {
            return AuthAttemptResult.Error("Username must be at least 3 characters.")
        }
        if (password.length < 4) {
            return AuthAttemptResult.Error("Password must be at least 4 characters.")
        }

        // Check official Owner credentials ("legendary" / "sanskarisgod")
        if (OwnerAccountPolicy.isReservedOwnerUsername(username)) {
            if (!OwnerAccountPolicy.verifyOwnerCredentials(username, password)) {
                return AuthAttemptResult.Error("Incorrect password for owner account.")
            }
            val ownerSession = AuthSession(
                isSignedIn = true,
                uid = OwnerAccountPolicy.OWNER_UID,
                username = OwnerAccountPolicy.OWNER_USERNAME,
                isOwner = true,
                authMethod = "owner_password",
                isGuestMode = false
            )
            saveActiveSession(ownerSession)
            upsertUserAndLogActivity(
                uid = ownerSession.uid,
                username = ownerSession.username,
                authMethod = "owner_password",
                isOwner = true,
                actionType = "OWNER_LOGIN",
                summary = "Owner signed in via username & password"
            )
            return AuthAttemptResult.Success(ownerSession, isNewAccount = false)
        }

        // Standard user sign-in or registration
        val normUsername = username.lowercase(Locale.US)
        val snapshot = getDirectory()
        val existing = snapshot.users.firstOrNull {
            it.username.lowercase(Locale.US) == normUsername && it.authMethod == "password"
        }

        return if (existing != null) {
            val computed = hashPassword(password, existing.passwordSaltHex)
            if (!constantTimeEquals(computed, existing.passwordHashHex)) {
                AuthAttemptResult.Error("Incorrect password for user '$username'.")
            } else {
                val session = AuthSession(
                    isSignedIn = true,
                    uid = existing.uid,
                    username = existing.username,
                    isOwner = false,
                    authMethod = "password",
                    isGuestMode = false
                )
                saveActiveSession(session)
                upsertUserAndLogActivity(
                    uid = session.uid,
                    username = session.username,
                    authMethod = "password",
                    isOwner = false,
                    actionType = "USER_LOGIN",
                    summary = "Signed in with username & password"
                )
                AuthAttemptResult.Success(session, isNewAccount = false)
            }
        } else {
            val uid = "usr_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val saltBytes = ByteArray(16)
            secureRandom.nextBytes(saltBytes)
            val saltHex = saltBytes.joinToString("") { "%02x".format(it) }
            val passHash = hashPassword(password, saltHex)
            val now = System.currentTimeMillis()
            val newUser = RegisteredUserRecord(
                uid = uid,
                username = username,
                passwordSaltHex = saltHex,
                passwordHashHex = passHash,
                authMethod = "password",
                isOwner = false,
                createdAtMillis = now,
                lastActiveMillis = now,
                activityCount = 0
            )
            saveDirectory(
                snapshot.copy(users = listOf(newUser) + snapshot.users)
            )
            val session = AuthSession(
                isSignedIn = true,
                uid = uid,
                username = username,
                isOwner = false,
                authMethod = "password",
                isGuestMode = false
            )
            saveActiveSession(session)
            upsertUserAndLogActivity(
                uid = uid,
                username = username,
                authMethod = "password",
                isOwner = false,
                actionType = "ACCOUNT_CREATED",
                summary = "Created new user account '$username'"
            )
            AuthAttemptResult.Success(session, isNewAccount = true)
        }
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
            username = if (isGoogleOwner) "${OwnerAccountPolicy.OWNER_USERNAME} ($derivedUsername)" else derivedUsername,
            email = cleanEmail,
            isOwner = isGoogleOwner,
            authMethod = "google",
            isGuestMode = false
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

    suspend fun activateGuestSession(): AuthSession {
        val session = AuthSession(
            isSignedIn = true,
            uid = "guest_local_test",
            username = "guest_preview",
            isOwner = false,
            authMethod = "guest",
            isGuestMode = true
        )
        saveActiveSession(session)
        upsertUserAndLogActivity(
            uid = session.uid,
            username = session.username,
            authMethod = "guest",
            isOwner = false,
            actionType = "GUEST_PREVIEW",
            summary = "Opened Local Offline Preview"
        )
        return session
    }

    suspend fun recordUserActivity(
        uid: String,
        username: String,
        actionType: String,
        summary: String,
        authMethod: String = "session",
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
                usersByKey[cloud.uid] = cloud.copy(
                    passwordSaltHex = existing?.passwordSaltHex.orEmpty(),
                    passwordHashHex = existing?.passwordHashHex.orEmpty()
                )
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

    private fun hashPassword(password: String, saltHex: String): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(password.toCharArray(), salt, 30_000, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return derived.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun encodeSession(s: AuthSession): String {
        return JSONObject().apply {
            put("isSignedIn", s.isSignedIn)
            put("uid", s.uid)
            put("username", s.username)
            put("email", s.email)
            put("isOwner", s.isOwner)
            put("authMethod", s.authMethod)
            put("isGuestMode", s.isGuestMode)
        }.toString()
    }

    private fun decodeSession(raw: String): AuthSession {
        val o = JSONObject(raw)
        return AuthSession(
            isSignedIn = o.optBoolean("isSignedIn", false),
            uid = o.optString("uid", ""),
            username = o.optString("username", ""),
            email = o.optString("email", ""),
            isOwner = o.optBoolean("isOwner", false),
            authMethod = o.optString("authMethod", "none"),
            isGuestMode = o.optBoolean("isGuestMode", false)
        )
    }

    private fun encodeDirectory(snap: UserDirectorySnapshot): String {
        val usersArr = JSONArray().apply {
            snap.users.forEach { u ->
                put(JSONObject().apply {
                    put("uid", u.uid)
                    put("username", u.username)
                    put("salt", u.passwordSaltHex)
                    put("hash", u.passwordHashHex)
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
                passwordSaltHex = o.optString("salt"),
                passwordHashHex = o.optString("hash"),
                authMethod = o.optString("authMethod", "password"),
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
    }
}
