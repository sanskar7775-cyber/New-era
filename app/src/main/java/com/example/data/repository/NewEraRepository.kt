package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import com.example.data.model.EncryptedRecord
import com.example.data.model.PersonalWebsite
import com.example.data.model.UserProfile
import com.example.data.model.VaultBundle
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

private val Context.localWorkspaceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "new_era_local_workspace"
)

class NewEraRepository(
    private val db: FirebaseFirestore?,
    private val localContext: Context? = null,
    private val localUserId: String = "direct_user_local"
) {
    constructor(db: FirebaseFirestore) : this(db = db, localContext = null)

    constructor(context: Context, localUserId: String = "direct_user_local") : this(
        db = resolveFirestoreIfAvailable(context),
        localContext = context.applicationContext,
        localUserId = localUserId
    )

    private val auth by lazy { Firebase.auth }
    private val isLocalMode: Boolean
        get() = db == null

    private fun requireDb(): FirebaseFirestore {
        return db ?: throw IllegalStateException("Cloud Firestore unavailable in direct local mode.")
    }

    private fun requireUserId(): String {
        if (isLocalMode) return localUserId
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            return ctx.localWorkspaceDataStore.data.map { prefs ->
                prefs[KEY_PROFILE_JSON]?.let { decodeProfile(it, userId) }
            }
        }
        return requireDb().collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catchIfCloud { error ->
                logFlowErrorIfCurrent(error, OperationType.GET, "users/$userId", userId)
                throw error
            }
    }

    fun observeEncryptedRecords(userId: String): Flow<List<EncryptedRecord>> {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            return ctx.localWorkspaceDataStore.data.map { prefs ->
                prefs[KEY_RECORDS_JSON]?.let { decodeRecords(it, userId) } ?: emptyList()
            }
        }
        return requireDb().collection("users").document(userId).collection("encrypted_records")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(EncryptedRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catchIfCloud { error ->
                logFlowErrorIfCurrent(error, OperationType.LIST, "users/$userId/encrypted_records", userId)
                throw error
            }
    }

    fun observeWebsitePage(userId: String): Flow<PersonalWebsite?> {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            return ctx.localWorkspaceDataStore.data.map { prefs ->
                prefs[KEY_WEBSITE_JSON]?.let { decodeWebsite(it, userId) }
            }
        }
        return requireDb().collection("users").document(userId).collection("website_pages").document(MAIN_SITE_DOC_ID)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(PersonalWebsite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catchIfCloud { error ->
                logFlowErrorIfCurrent(error, OperationType.GET, "users/$userId/website_pages/$MAIN_SITE_DOC_ID", userId)
                throw error
            }
    }

    fun observeVaultBundle(userId: String): Flow<VaultBundle?> {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            return ctx.localWorkspaceDataStore.data.map { prefs ->
                prefs[KEY_VAULT_BUNDLE_JSON]?.let { decodeVaultBundle(it, userId) }
            }
        }
        return requireDb().collection("users").document(userId).collection("vault_bundles").document(PRIMARY_BUNDLE_DOC_ID)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(VaultBundle::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catchIfCloud { error ->
                logFlowErrorIfCurrent(error, OperationType.GET, "users/$userId/vault_bundles/$PRIMARY_BUNDLE_DOC_ID", userId)
                throw error
            }
    }

    suspend fun ensureAutomatedSetup(
        keyFingerprint: String,
        defaultWebsiteHtml: String
    ) {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            ctx.localWorkspaceDataStore.edit { prefs ->
                if (prefs[KEY_PROFILE_JSON].isNullOrBlank()) {
                    val profile = UserProfile(
                        userId = localUserId,
                        ownerName = "Sanskar",
                        aiName = "New Era",
                        websiteTitle = "New Era — Sanskar",
                        websiteTagline = "Owner: Sanskar • Encrypted AI Hub",
                        keyFingerprint = keyFingerprint,
                        voiceReplyEnabled = true,
                        autoSetupCompleted = true,
                        createdAt = Timestamp.now(),
                        updatedAt = Timestamp.now()
                    )
                    prefs[KEY_PROFILE_JSON] = encodeProfile(profile)
                }
                if (prefs[KEY_WEBSITE_JSON].isNullOrBlank()) {
                    val site = PersonalWebsite(
                        userId = localUserId,
                        ownerTag = "Owner: Sanskar",
                        siteTitle = "New Era — Sanskar",
                        headline = "Welcome to Sanskar's Personal AI & Command Hub",
                        bio = "Powered by New Era AI with voice interaction, multimodal studio, and AES-256 encrypted vault.",
                        accentStyle = "cyber_cyan",
                        showTelemetry = true,
                        showAiPortal = true,
                        publishedHtml = defaultWebsiteHtml,
                        createdAt = Timestamp.now(),
                        updatedAt = Timestamp.now()
                    )
                    prefs[KEY_WEBSITE_JSON] = encodeWebsite(site)
                }
            }
            return
        }

        val uid = requireUserId()
        val userDoc = requireDb().collection("users").document(uid)
        val siteDoc = userDoc.collection("website_pages").document(MAIN_SITE_DOC_ID)

        try {
            val existingProfile = userDoc.get().await()
            if (!existingProfile.exists()) {
                val profilePayload = mapOf(
                    "userId" to uid,
                    "ownerName" to "Sanskar",
                    "aiName" to "New Era",
                    "websiteTitle" to "New Era — Sanskar",
                    "websiteTagline" to "Owner: Sanskar • Encrypted AI Hub",
                    "keyFingerprint" to keyFingerprint,
                    "voiceReplyEnabled" to true,
                    "autoSetupCompleted" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userDoc.set(profilePayload).await()
            } else if (keyFingerprint.isNotBlank() && existingProfile.getString("keyFingerprint").isNullOrBlank()) {
                userDoc.update(
                    mapOf(
                        "keyFingerprint" to keyFingerprint,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val existingSite = siteDoc.get().await()
            if (!existingSite.exists()) {
                val sitePayload = mapOf(
                    "userId" to uid,
                    "ownerTag" to "Owner: Sanskar",
                    "siteTitle" to "New Era — Sanskar",
                    "headline" to "Welcome to Sanskar's Personal AI & Command Hub",
                    "bio" to "Powered by New Era AI with live internet access, phone & app control, Alexa-style voice interaction, and end-to-end encrypted cross-device sync.",
                    "accentStyle" to "cyber_cyan",
                    "showTelemetry" to true,
                    "showAiPortal" to true,
                    "publishedHtml" to defaultWebsiteHtml,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                siteDoc.set(sitePayload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userDoc.path)
            throw e
        }
    }

    suspend fun updateVoiceReplyEnabled(enabled: Boolean) {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            ctx.localWorkspaceDataStore.edit { prefs ->
                val current = prefs[KEY_PROFILE_JSON]?.let { decodeProfile(it, localUserId) }
                    ?: UserProfile(userId = localUserId)
                prefs[KEY_PROFILE_JSON] = encodeProfile(
                    current.copy(voiceReplyEnabled = enabled, updatedAt = Timestamp.now())
                )
            }
            return
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid)
        try {
            docRef.update(
                mapOf(
                    "voiceReplyEnabled" to enabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun updateKeyFingerprint(fingerprint: String) {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            ctx.localWorkspaceDataStore.edit { prefs ->
                val current = prefs[KEY_PROFILE_JSON]?.let { decodeProfile(it, localUserId) }
                    ?: UserProfile(userId = localUserId)
                prefs[KEY_PROFILE_JSON] = encodeProfile(
                    current.copy(keyFingerprint = fingerprint, updatedAt = Timestamp.now())
                )
            }
            return
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid)
        try {
            docRef.update(
                mapOf(
                    "keyFingerprint" to fingerprint,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun saveEncryptedRecord(
        recordType: String,
        ciphertext: String,
        iv: String,
        pinned: Boolean = false
    ): String {
        val docId = "rec_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            val now = Timestamp.now()
            val newRec = EncryptedRecord(
                id = docId,
                userId = localUserId,
                recordType = recordType,
                ciphertext = ciphertext,
                iv = iv,
                keyVersion = 1L,
                pinned = pinned,
                createdAt = now,
                updatedAt = now
            )
            ctx.localWorkspaceDataStore.edit { prefs ->
                val existing = prefs[KEY_RECORDS_JSON]?.let { decodeRecords(it, localUserId) } ?: emptyList()
                prefs[KEY_RECORDS_JSON] = encodeRecords(listOf(newRec) + existing)
            }
            return docId
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid).collection("encrypted_records").document(docId)
        val payload = mapOf(
            "userId" to uid,
            "recordType" to recordType,
            "ciphertext" to ciphertext,
            "iv" to iv,
            "keyVersion" to 1L,
            "pinned" to pinned,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            return docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    suspend fun togglePinRecord(recordId: String, pinned: Boolean) {
        require(recordId.isNotBlank()) { "Record ID must not be blank" }
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            ctx.localWorkspaceDataStore.edit { prefs ->
                val existing = prefs[KEY_RECORDS_JSON]?.let { decodeRecords(it, localUserId) } ?: emptyList()
                val updated = existing.map {
                    if (it.id == recordId) it.copy(pinned = pinned, updatedAt = Timestamp.now()) else it
                }
                prefs[KEY_RECORDS_JSON] = encodeRecords(updated)
            }
            return
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid).collection("encrypted_records").document(recordId)
        try {
            docRef.update(
                mapOf(
                    "pinned" to pinned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun deleteEncryptedRecord(recordId: String) {
        require(recordId.isNotBlank()) { "Record ID must not be blank" }
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            ctx.localWorkspaceDataStore.edit { prefs ->
                val existing = prefs[KEY_RECORDS_JSON]?.let { decodeRecords(it, localUserId) } ?: emptyList()
                prefs[KEY_RECORDS_JSON] = encodeRecords(existing.filterNot { it.id == recordId })
            }
            return
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid).collection("encrypted_records").document(recordId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    suspend fun saveWebsitePage(website: PersonalWebsite) {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            ctx.localWorkspaceDataStore.edit { prefs ->
                prefs[KEY_WEBSITE_JSON] = encodeWebsite(
                    website.copy(
                        userId = localUserId,
                        ownerTag = website.ownerTag.ifBlank { "Owner: Sanskar" },
                        updatedAt = Timestamp.now()
                    )
                )
            }
            return
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid)
            .collection("website_pages").document(MAIN_SITE_DOC_ID)
        try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val createPayload = mapOf(
                    "userId" to uid,
                    "ownerTag" to website.ownerTag.ifBlank { "Owner: Sanskar" },
                    "siteTitle" to website.siteTitle,
                    "headline" to website.headline,
                    "bio" to website.bio,
                    "accentStyle" to website.accentStyle,
                    "showTelemetry" to website.showTelemetry,
                    "showAiPortal" to website.showAiPortal,
                    "publishedHtml" to website.publishedHtml,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "ownerTag" to website.ownerTag.ifBlank { "Owner: Sanskar" },
                    "siteTitle" to website.siteTitle,
                    "headline" to website.headline,
                    "bio" to website.bio,
                    "accentStyle" to website.accentStyle,
                    "showTelemetry" to website.showTelemetry,
                    "showAiPortal" to website.showAiPortal,
                    "publishedHtml" to website.publishedHtml,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun saveVaultBundle(
        saltBase64: String,
        wrappedKeyBase64: String,
        ivBase64: String,
        keyFingerprint: String
    ) {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            ctx.localWorkspaceDataStore.edit { prefs ->
                prefs[KEY_VAULT_BUNDLE_JSON] = encodeVaultBundle(
                    VaultBundle(
                        userId = localUserId,
                        saltBase64 = saltBase64,
                        wrappedKeyBase64 = wrappedKeyBase64,
                        ivBase64 = ivBase64,
                        keyFingerprint = keyFingerprint,
                        createdAt = Timestamp.now(),
                        updatedAt = Timestamp.now()
                    )
                )
            }
            return
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid)
            .collection("vault_bundles").document(PRIMARY_BUNDLE_DOC_ID)
        try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "saltBase64" to saltBase64,
                        "wrappedKeyBase64" to wrappedKeyBase64,
                        "ivBase64" to ivBase64,
                        "keyFingerprint" to keyFingerprint,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "saltBase64" to saltBase64,
                        "wrappedKeyBase64" to wrappedKeyBase64,
                        "ivBase64" to ivBase64,
                        "keyFingerprint" to keyFingerprint,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun getVaultBundleOnce(): VaultBundle? {
        val ctx = localContext
        if (isLocalMode && ctx != null) {
            val raw = ctx.localWorkspaceDataStore.data.first()[KEY_VAULT_BUNDLE_JSON] ?: return null
            return decodeVaultBundle(raw, localUserId)
        }

        val uid = requireUserId()
        val docRef = requireDb().collection("users").document(uid)
            .collection("vault_bundles").document(PRIMARY_BUNDLE_DOC_ID)
        return try {
            docRef.get().await().toObject(VaultBundle::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            throw e
        }
    }

    suspend fun syncCloudUserActivity(
        username: String,
        authMethod: String,
        actionType: String,
        summary: String,
        activityCount: Int
    ) {
        val firestore = db ?: return
        val uid = runCatching { auth.currentUser?.uid }.getOrNull() ?: return
        runCatching {
            firestore.collection("user_directory").document(uid)
                .set(
                    mapOf(
                        "userId" to uid,
                        "username" to username.take(64),
                        "authMethod" to authMethod,
                        "activityCount" to activityCount.coerceAtLeast(0),
                        "lastActiveAt" to FieldValue.serverTimestamp()
                    )
                ).await()

            val actId = "act_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            firestore.collection("user_activities").document(actId)
                .set(
                    mapOf(
                        "userId" to uid,
                        "username" to username.take(64),
                        "actionType" to actionType,
                        "summary" to summary.take(256),
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                ).await()
        }
    }

    suspend fun fetchCloudDirectoryForOwner(): Pair<List<com.example.auth.RegisteredUserRecord>, List<com.example.auth.UserActivityEvent>> {
        val firestore = db ?: return emptyList<com.example.auth.RegisteredUserRecord>() to emptyList()
        return try {
            val usersSnap = firestore.collection("user_directory").get().await()
            val users = usersSnap.documents.mapNotNull { doc ->
                val uid = doc.getString("userId") ?: doc.id
                val uname = doc.getString("username") ?: return@mapNotNull null
                val method = doc.getString("authMethod") ?: "direct"
                val count = (doc.getLong("activityCount") ?: 0L).toInt()
                val lastActive = doc.getTimestamp("lastActiveAt")?.toDate()?.time ?: 0L
                com.example.auth.RegisteredUserRecord(
                    uid = uid,
                    username = uname,
                    authMethod = method,
                    isOwner = false,
                    lastActiveMillis = lastActive,
                    activityCount = count
                )
            }
            val actSnap = firestore.collection("user_activities").get().await()
            val activities = actSnap.documents.mapNotNull { doc ->
                val uid = doc.getString("userId") ?: return@mapNotNull null
                val uname = doc.getString("username") ?: return@mapNotNull null
                val type = doc.getString("actionType") ?: "ACTIVITY"
                val sum = doc.getString("summary") ?: ""
                val ts = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                com.example.auth.UserActivityEvent(
                    id = doc.id,
                    uid = uid,
                    username = uname,
                    actionType = type,
                    summary = sum,
                    timestampMillis = ts
                )
            }.sortedByDescending { it.timestampMillis }
            users to activities
        } catch (_: Exception) {
            emptyList<com.example.auth.RegisteredUserRecord>() to emptyList()
        }
    }

    private fun <T> Flow<T>.catchIfCloud(handler: suspend kotlinx.coroutines.flow.FlowCollector<T>.(Throwable) -> Unit): Flow<T> {
        return this.catch(handler)
    }

    private fun logFlowErrorIfCurrent(
        error: Throwable,
        operationType: OperationType,
        path: String,
        expectedUid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val toLog = firestoreError ?: (error as? Exception)
        if (toLog != null && runCatching { auth.currentUser?.uid }.getOrNull() == expectedUid) {
            handleFirestoreError(toLog, operationType, path)
        }
    }

    private fun encodeProfile(p: UserProfile): String = JSONObject().apply {
        put("ownerName", p.ownerName)
        put("aiName", p.aiName)
        put("websiteTitle", p.websiteTitle)
        put("websiteTagline", p.websiteTagline)
        put("keyFingerprint", p.keyFingerprint)
        put("voiceReplyEnabled", p.voiceReplyEnabled)
        put("autoSetupCompleted", p.autoSetupCompleted)
    }.toString()

    private fun decodeProfile(raw: String, uid: String): UserProfile? = runCatching {
        val o = JSONObject(raw)
        UserProfile(
            userId = uid,
            ownerName = o.optString("ownerName", "Sanskar"),
            aiName = o.optString("aiName", "New Era"),
            websiteTitle = o.optString("websiteTitle", "New Era — Sanskar"),
            websiteTagline = o.optString("websiteTagline", ""),
            keyFingerprint = o.optString("keyFingerprint", ""),
            voiceReplyEnabled = o.optBoolean("voiceReplyEnabled", true),
            autoSetupCompleted = o.optBoolean("autoSetupCompleted", true)
        )
    }.getOrNull()

    private fun encodeRecords(list: List<EncryptedRecord>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("recordType", r.recordType)
                put("ciphertext", r.ciphertext)
                put("iv", r.iv)
                put("keyVersion", r.keyVersion)
                put("pinned", r.pinned)
                put("updatedMillis", r.updatedAt?.toDate()?.time ?: System.currentTimeMillis())
            })
        }
        return arr.toString()
    }

    private fun decodeRecords(raw: String, uid: String): List<EncryptedRecord> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ts = Timestamp(Date(o.optLong("updatedMillis", System.currentTimeMillis())))
            EncryptedRecord(
                id = o.optString("id"),
                userId = uid,
                recordType = o.optString("recordType", "note"),
                ciphertext = o.optString("ciphertext"),
                iv = o.optString("iv"),
                keyVersion = o.optLong("keyVersion", 1L),
                pinned = o.optBoolean("pinned", false),
                createdAt = ts,
                updatedAt = ts
            )
        }
    }.getOrDefault(emptyList())

    private fun encodeWebsite(w: PersonalWebsite): String = JSONObject().apply {
        put("ownerTag", w.ownerTag)
        put("siteTitle", w.siteTitle)
        put("headline", w.headline)
        put("bio", w.bio)
        put("accentStyle", w.accentStyle)
        put("showTelemetry", w.showTelemetry)
        put("showAiPortal", w.showAiPortal)
        put("publishedHtml", w.publishedHtml)
    }.toString()

    private fun decodeWebsite(raw: String, uid: String): PersonalWebsite? = runCatching {
        val o = JSONObject(raw)
        PersonalWebsite(
            userId = uid,
            ownerTag = o.optString("ownerTag", "Owner: Sanskar"),
            siteTitle = o.optString("siteTitle", "New Era — Sanskar"),
            headline = o.optString("headline", ""),
            bio = o.optString("bio", ""),
            accentStyle = o.optString("accentStyle", "cyber_cyan"),
            showTelemetry = o.optBoolean("showTelemetry", true),
            showAiPortal = o.optBoolean("showAiPortal", true),
            publishedHtml = o.optString("publishedHtml", "")
        )
    }.getOrNull()

    private fun encodeVaultBundle(v: VaultBundle): String = JSONObject().apply {
        put("saltBase64", v.saltBase64)
        put("wrappedKeyBase64", v.wrappedKeyBase64)
        put("ivBase64", v.ivBase64)
        put("keyFingerprint", v.keyFingerprint)
    }.toString()

    private fun decodeVaultBundle(raw: String, uid: String): VaultBundle? = runCatching {
        val o = JSONObject(raw)
        VaultBundle(
            userId = uid,
            saltBase64 = o.optString("saltBase64"),
            wrappedKeyBase64 = o.optString("wrappedKeyBase64"),
            ivBase64 = o.optString("ivBase64"),
            keyFingerprint = o.optString("keyFingerprint")
        )
    }.getOrNull()

    companion object {
        const val MAIN_SITE_DOC_ID = "main_site"
        const val PRIMARY_BUNDLE_DOC_ID = "primary_bundle"
        private val KEY_PROFILE_JSON = stringPreferencesKey("local_profile_v1")
        private val KEY_RECORDS_JSON = stringPreferencesKey("local_records_v1")
        private val KEY_WEBSITE_JSON = stringPreferencesKey("local_website_v1")
        private val KEY_VAULT_BUNDLE_JSON = stringPreferencesKey("local_vault_bundle_v1")

        private fun resolveFirestoreIfAvailable(context: Context): FirebaseFirestore? {
            return runCatching {
                if (FirebaseApp.getApps(context).isEmpty()) return null
                if (Firebase.auth.currentUser == null) return null
                FirebaseFirestore.getInstance(
                    context.applicationContext.getString(R.string.firestore_database_id)
                )
            }.getOrNull()
        }
    }
}
