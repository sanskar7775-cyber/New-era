package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.PersonalWebsite
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class NewEraRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureAutomatedSetup_authenticatedOwner_createsProfileAndOwnerTaggedWebsite() = runBlocking {
        val uid = signInTestUser("sanskar_${UUID.randomUUID()}@test.com")
        val repository = NewEraRepository(firestore)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureAutomatedSetup(
                keyFingerprint = "FP12345678",
                defaultWebsiteHtml = "<html><body>Owner: Sanskar</body></html>"
            )
        }

        val profile = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeUserProfile(uid).first { it != null }
        }
        assertNotNull(profile)
        assertEquals("Sanskar", profile?.ownerName)
        assertEquals("New Era", profile?.aiName)

        val website = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeWebsitePage(uid).first { it != null }
        }
        assertNotNull(website)
        assertEquals("Owner: Sanskar", website?.ownerTag)
    }

    @Test
    fun saveAndObserveEncryptedRecord_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser("sanskar_rec_${UUID.randomUUID()}@test.com")
        val repository = NewEraRepository(firestore)

        val recordId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveEncryptedRecord(
                recordType = "note",
                ciphertext = "ZW5jcnlwdGVkX3BheWxvYWRfZGF0YQ==",
                iv = "MTIzNDU2Nzg5MDEy",
                pinned = true
            )
        }
        assertTrue(recordId.isNotBlank())

        val records = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeEncryptedRecords(uid).first { list -> list.any { it.id == recordId } }
        }
        assertTrue(records.any { it.id == recordId && it.pinned })

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveWebsitePage(
                PersonalWebsite(
                    userId = uid,
                    ownerTag = "Owner: Sanskar",
                    siteTitle = "New Era — Sanskar",
                    headline = "Updated Headline",
                    bio = "Bio text",
                    accentStyle = "obsidian_gold",
                    showTelemetry = true,
                    showAiPortal = true,
                    publishedHtml = "<html>Owner: Sanskar</html>"
                )
            )
        }
    }

    @Test
    fun observeEncryptedRecords_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val aliceRepo = NewEraRepository(firestore)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.saveEncryptedRecord(
                recordType = "chat",
                ciphertext = "c2VjcmV0X2NoYXQ=",
                iv = "MTIzNDU2Nzg5MDEy",
                pinned = false
            )
        }

        signInTestUser("bob_${UUID.randomUUID()}@test.com")
        val bobRepo = NewEraRepository(firestore)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeEncryptedRecords(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED when Bob reads Alice's encrypted records")
        } catch (e: Exception) {
            val firestoreException = checkNotNull(e.unwrapFirestoreException()) {
                "Expected FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
        }
    }

    @Test
    fun observeEncryptedRecords_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = NewEraRepository(firestore)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeEncryptedRecords("some_user_123").first()
            }
            fail("Expected PERMISSION_DENIED when unauthenticated")
        } catch (e: Exception) {
            val firestoreException = checkNotNull(e.unwrapFirestoreException()) {
                "Expected FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
