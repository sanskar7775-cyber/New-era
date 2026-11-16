package com.example.googleapps

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

private val Context.googleAppsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "new_era_google_apps_prefs"
)

enum class GoogleAppIntegration(
    val id: String,
    val displayName: String,
    val capabilitySummary: String,
    val oauthScopes: List<String>,
    val androidPackageName: String
) {
    GMAIL(
        id = "gmail",
        displayName = "Gmail",
        capabilitySummary = "Read recent messages & send emails",
        oauthScopes = listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.send"
        ),
        androidPackageName = "com.google.android.gm"
    ),
    CALENDAR(
        id = "calendar",
        displayName = "Google Calendar",
        capabilitySummary = "View agenda & schedule events",
        oauthScopes = listOf("https://www.googleapis.com/auth/calendar"),
        androidPackageName = "com.google.android.calendar"
    ),
    DRIVE(
        id = "drive",
        displayName = "Google Drive",
        capabilitySummary = "Search & browse cloud files",
        oauthScopes = listOf(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.file"
        ),
        androidPackageName = "com.google.android.apps.docs"
    ),
    DOCS(
        id = "docs",
        displayName = "Google Docs",
        capabilitySummary = "Create & open documents",
        oauthScopes = listOf("https://www.googleapis.com/auth/documents"),
        androidPackageName = "com.google.android.apps.docs.editors.docs"
    ),
    SHEETS(
        id = "sheets",
        displayName = "Google Sheets",
        capabilitySummary = "Create & organize spreadsheets",
        oauthScopes = listOf("https://www.googleapis.com/auth/spreadsheets"),
        androidPackageName = "com.google.android.apps.docs.editors.sheets"
    ),
    SLIDES(
        id = "slides",
        displayName = "Google Slides",
        capabilitySummary = "Create & access presentations",
        oauthScopes = listOf("https://www.googleapis.com/auth/presentations"),
        androidPackageName = "com.google.android.apps.docs.editors.slides"
    ),
    CONTACTS(
        id = "contacts",
        displayName = "Google Contacts",
        capabilitySummary = "Look up contacts & emails",
        oauthScopes = listOf("https://www.googleapis.com/auth/contacts.readonly"),
        androidPackageName = "com.google.android.contacts"
    ),
    TASKS(
        id = "tasks",
        displayName = "Google Tasks",
        capabilitySummary = "View & add to-do tasks",
        oauthScopes = listOf("https://www.googleapis.com/auth/tasks"),
        androidPackageName = "com.google.android.apps.tasks"
    ),
    YOUTUBE(
        id = "youtube",
        displayName = "YouTube",
        capabilitySummary = "Check subscriptions & channels",
        oauthScopes = listOf("https://www.googleapis.com/auth/youtube.readonly"),
        androidPackageName = "com.google.android.youtube"
    );

    companion object {
        fun fromId(raw: String): GoogleAppIntegration? {
            val norm = raw.trim().lowercase()
            return entries.firstOrNull {
                it.id == norm || it.displayName.lowercase().contains(norm)
            }
        }
    }
}

data class GoogleOAuthSession(
    val accessToken: String = "",
    val grantedScopes: Set<String> = emptySet(),
    val authorizedAtMillis: Long = 0L
) {
    val isAuthorized: Boolean
        get() = accessToken.isNotBlank() &&
            (System.currentTimeMillis() - authorizedAtMillis) < 55 * 60 * 1000L
}

class GoogleAppsManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.googleAppsDataStore

    private val _oauthSession = MutableStateFlow(GoogleOAuthSession())
    val oauthSession: StateFlow<GoogleOAuthSession> = _oauthSession.asStateFlow()

    val permittedAppsFlow: Flow<Set<GoogleAppIntegration>> = dataStore.data.map { prefs ->
        val initialized = prefs[KEY_PERMS_INITIALIZED] ?: false
        if (!initialized) {
            // Default all Google Apps enabled as user-configurable permissions; user can toggle any off/on
            GoogleAppIntegration.entries.toSet()
        } else {
            val savedIds = prefs[KEY_ALLOWED_APP_IDS] ?: emptySet()
            GoogleAppIntegration.entries.filter { it.id in savedIds }.toSet()
        }
    }

    suspend fun getPermittedApps(): Set<GoogleAppIntegration> = permittedAppsFlow.first()

    suspend fun isAppPermitted(app: GoogleAppIntegration): Boolean {
        return app in getPermittedApps()
    }

    suspend fun setAppPermission(app: GoogleAppIntegration, allowed: Boolean) {
        val current = getPermittedApps().toMutableSet()
        if (allowed) current.add(app) else current.remove(app)
        dataStore.edit { prefs ->
            prefs[KEY_PERMS_INITIALIZED] = true
            prefs[KEY_ALLOWED_APP_IDS] = current.map { it.id }.toSet()
        }
    }

    suspend fun setAllPermissions(allowed: Boolean) {
        val next = if (allowed) GoogleAppIntegration.entries.map { it.id }.toSet() else emptySet()
        dataStore.edit { prefs ->
            prefs[KEY_PERMS_INITIALIZED] = true
            prefs[KEY_ALLOWED_APP_IDS] = next
        }
    }

    fun getActiveAccessToken(): String? {
        val session = _oauthSession.value
        return if (session.isAuthorized) session.accessToken else null
    }

    fun clearSession() {
        _oauthSession.value = GoogleOAuthSession()
    }

    suspend fun requestWorkspaceAuthorization(): AuthorizationResult {
        val permitted = getPermittedApps()
        require(permitted.isNotEmpty()) {
            "Enable at least one Google App permission toggle before authorizing."
        }
        val scopes = permitted
            .flatMap { it.oauthScopes }
            .distinct()
            .map { Scope(it) }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .build()

        val result = Identity.getAuthorizationClient(appContext)
            .authorize(request)
            .await()

        if (!result.hasResolution() && !result.accessToken.isNullOrBlank()) {
            _oauthSession.value = GoogleOAuthSession(
                accessToken = result.accessToken.orEmpty(),
                grantedScopes = result.grantedScopes.toSet(),
                authorizedAtMillis = System.currentTimeMillis()
            )
        }
        return result
    }

    fun handleAuthorizationActivityResult(data: Intent?): GoogleOAuthSession {
        val result = Identity.getAuthorizationClient(appContext)
            .getAuthorizationResultFromIntent(data)
        val token = result.accessToken.orEmpty()
        val session = GoogleOAuthSession(
            accessToken = token,
            grantedScopes = result.grantedScopes.toSet(),
            authorizedAtMillis = if (token.isNotBlank()) System.currentTimeMillis() else 0L
        )
        _oauthSession.value = session
        return session
    }

    suspend fun requireAuthorizedTokenFor(app: GoogleAppIntegration): Result<String> {
        if (!isAppPermitted(app)) {
            return Result.failure(
                IllegalStateException(
                    "Permission denied by user: ${app.displayName} access is turned OFF in Google Apps Permissions."
                )
            )
        }
        val currentToken = getActiveAccessToken()
        if (!currentToken.isNullOrBlank()) {
            return Result.success(currentToken)
        }
        return try {
            val result = requestWorkspaceAuthorization()
            val token = result.accessToken
            if (!result.hasResolution() && !token.isNullOrBlank()) {
                Result.success(token)
            } else {
                Result.failure(
                    IllegalStateException(
                        "Google OAuth consent required for ${app.displayName}. Open Apps -> Google Apps Permissions and tap Authorize."
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Unable to authorize ${app.displayName}: ${e.localizedMessage ?: "Sign in with Google and authorize Workspace permissions."}"
                )
            )
        }
    }

    companion object {
        private val KEY_PERMS_INITIALIZED = booleanPreferencesKey("google_apps_perms_initialized")
        private val KEY_ALLOWED_APP_IDS = stringSetPreferencesKey("google_apps_allowed_ids")
    }
}
