package com.example.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OwnerAccountPolicyTest {

    @Test
    fun `authorized owner google account grants owner power to sanskar7775`() {
        assertTrue(OwnerAccountPolicy.isAuthorizedOwnerEmail("sanskar7775@gmail.com"))
        assertTrue(OwnerAccountPolicy.isAuthorizedOwnerEmail("  SANSKAR7775@GMAIL.COM "))
        assertTrue(OwnerAccountPolicy.isAuthorizedOwnerEmail("sanskarghogare076@gmail.com"))
        assertFalse(OwnerAccountPolicy.isAuthorizedOwnerEmail("alice@gmail.com"))
        assertFalse(OwnerAccountPolicy.isAuthorizedOwnerEmail(""))
        assertFalse(OwnerAccountPolicy.isAuthorizedOwnerEmail(null))
    }

    @Test
    fun `google user sessions track owner status, user directory, and activity feed`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = UserSessionManager(context)
        manager.signOutSession()

        // 1. Standard Google user signs in
        val aliceSession = manager.activateGoogleUserSession(
            uid = "uid_alice_1",
            email = "alice@gmail.com",
            displayName = "Alice"
        )
        assertTrue(aliceSession.isSignedIn)
        assertEquals("google", aliceSession.authMethod)
        assertEquals("Alice", aliceSession.username)
        assertFalse(aliceSession.isOwner)

        // 2. Record user activity for Alice
        manager.recordUserActivity(
            uid = aliceSession.uid,
            username = aliceSession.username,
            actionType = "IMAGE_STUDIO",
            summary = "Generated portrait",
            authMethod = "google",
            isOwner = false
        )

        // 3. Owner Google account (sanskar7775@gmail.com) signs in with Owner powers
        val ownerSession = manager.activateGoogleUserSession(
            uid = "uid_owner_sanskar",
            email = "sanskar7775@gmail.com",
            displayName = "Sanskar"
        )
        assertTrue(ownerSession.isSignedIn)
        assertEquals("google", ownerSession.authMethod)
        assertTrue(ownerSession.isOwner)

        // 4. Verify Owner Directory Snapshot tracks users, emails, and activity events
        val directory = manager.getDirectory()
        assertTrue(directory.totalUsers >= 2)
        val ownerRecord = directory.users.firstOrNull { it.uid == "uid_owner_sanskar" }
        assertTrue(ownerRecord?.isOwner == true)
        assertEquals("sanskar7775@gmail.com", ownerRecord?.email)
        assertTrue(directory.activities.any { it.username == "Alice" && it.actionType == "IMAGE_STUDIO" })
    }
}
