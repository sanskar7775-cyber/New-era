package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.EncryptedRecord
import com.example.data.model.PersonalWebsite
import com.example.data.model.UserProfile
import com.example.data.model.VaultBundle
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class NewEraRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth by lazy { Firebase.auth }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> {
        return db.collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowErrorIfCurrent(error, OperationType.GET, "users/$userId", userId)
                throw error
            }
    }

    fun observeEncryptedRecords(userId: String): Flow<List<EncryptedRecord>> {
        return db.collection("users").document(userId).collection("encrypted_records")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(EncryptedRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowErrorIfCurrent(error, OperationType.LIST, "users/$userId/encrypted_records", userId)
                throw error
            }
    }

    fun observeWebsitePage(userId: String): Flow<PersonalWebsite?> {
        return db.collection("users").document(userId).collection("website_pages").document(MAIN_SITE_DOC_ID)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(PersonalWebsite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowErrorIfCurrent(error, OperationType.GET, "users/$userId/website_pages/$MAIN_SITE_DOC_ID", userId)
                throw error
            }
    }

    fun observeVaultBundle(userId: String): Flow<VaultBundle?> {
        return db.collection("users").document(userId).collection("vault_bundles").document(PRIMARY_BUNDLE_DOC_ID)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(VaultBundle::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                logFlowErrorIfCurrent(error, OperationType.GET, "users/$userId/vault_bundles/$PRIMARY_BUNDLE_DOC_ID", userId)
                throw error
            }
    }

    suspend fun ensureAutomatedSetup(
        keyFingerprint: String,
        defaultWebsiteHtml: String
    ) {
        val uid = requireUserId()
        val userDoc = db.collection("users").document(uid)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("encrypted_records").document(docId)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("encrypted_records").document(recordId)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("encrypted_records").document(recordId)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
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
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
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
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            db.collection("user_directory").document(uid)
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
            db.collection("user_activities").document(actId)
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
        return try {
            val usersSnap = db.collection("user_directory").get().await()
            val users = usersSnap.documents.mapNotNull { doc ->
                val uid = doc.getString("userId") ?: doc.id
                val uname = doc.getString("username") ?: return@mapNotNull null
                val method = doc.getString("authMethod") ?: "google"
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
            val actSnap = db.collection("user_activities").get().await()
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
        if (toLog != null && auth.currentUser?.uid == expectedUid) {
            handleFirestoreError(toLog, operationType, path)
        }
    }

    companion object {
        const val MAIN_SITE_DOC_ID = "main_site"
        const val PRIMARY_BUNDLE_DOC_ID = "primary_bundle"
    }
}
