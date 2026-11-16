package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PersonalWebsite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GuestModeRepositoryTest {

    @Test
    fun `guest mode initializes profile and website locally without Firestore`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NewEraRepository(context, isGuestMode = true)

        repo.ensureAutomatedSetup(keyFingerprint = "ABC12345", defaultWebsiteHtml = "<html>Guest</html>")

        val profile = repo.observeUserProfile(NewEraRepository.GUEST_USER_ID).first()
        val site = repo.observeWebsitePage(NewEraRepository.GUEST_USER_ID).first()

        assertNotNull(profile)
        assertEquals("Sanskar (Guest)", profile?.ownerName)
        assertEquals("ABC12345", profile?.keyFingerprint)
        assertNotNull(site)
        assertEquals("Owner: Sanskar", site?.ownerTag)
        assertEquals("<html>Guest</html>", site?.publishedHtml)
    }

    @Test
    fun `guest mode supports saving pinning deleting records and updating website`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NewEraRepository(context, isGuestMode = true)

        repo.ensureAutomatedSetup("FP01", "<html/>")
        val id = repo.saveEncryptedRecord("note", "cipher==", "iv==", pinned = false)

        var records = repo.observeEncryptedRecords(NewEraRepository.GUEST_USER_ID).first()
        assertEquals(1, records.size)
        assertFalse(records.first().pinned)

        repo.togglePinRecord(id, true)
        records = repo.observeEncryptedRecords(NewEraRepository.GUEST_USER_ID).first()
        assertTrue(records.first().pinned)

        repo.saveWebsitePage(
            PersonalWebsite(
                userId = NewEraRepository.GUEST_USER_ID,
                siteTitle = "Guest Portal",
                headline = "Testing New Era"
            )
        )
        val site = repo.observeWebsitePage(NewEraRepository.GUEST_USER_ID).first()
        assertEquals("Guest Portal", site?.siteTitle)
        assertEquals("Owner: Sanskar", site?.ownerTag)

        repo.deleteEncryptedRecord(id)
        records = repo.observeEncryptedRecords(NewEraRepository.GUEST_USER_ID).first()
        assertTrue(records.isEmpty())
    }
}
