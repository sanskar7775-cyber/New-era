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
    fun `owner username and password verify accurately`() {
        assertTrue(OwnerAccountPolicy.verifyOwnerCredentials("legendary", "sanskarisgod"))
        assertTrue(OwnerAccountPolicy.verifyOwnerCredentials("  Legendary ", "sanskarisgod"))
        assertFalse(OwnerAccountPolicy.verifyOwnerCredentials("legendary", "wrongpass"))
        assertFalse(OwnerAccountPolicy.verifyOwnerCredentials("otheruser", "sanskarisgod"))
        assertFalse(OwnerAccountPolicy.verifyOwnerCredentials("", ""))
        assertFalse(OwnerAccountPolicy.verifyOwnerCredentials(null, null))
    }

    @Test
    fun `authorized owner emails still match owner cloud privileges`() {
        assertTrue(OwnerAccountPolicy.isAuthorizedOwnerEmail("sanskar7775@gmail.com"))
        assertTrue(OwnerAccountPolicy.isAuthorizedOwnerEmail("sanskarghogare076@gmail.com"))
        assertFalse(OwnerAccountPolicy.isAuthorizedOwnerEmail("alice@gmail.com"))
    }

    @Test
    fun `username and password supports open user registration, owner monitor directory, and activity tracking`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = UserSessionManager(context)
        manager.signOutSession()

        // 1. Register & sign in standard user "alice"
        val aliceRes = manager.signInOrRegisterWithCredentials("alice", "pass1234")
        assertTrue(aliceRes is AuthAttemptResult.Success)
        val aliceSession = (aliceRes as AuthAttemptResult.Success).session
        assertEquals("alice", aliceSession.username)
        assertFalse(aliceSession.isOwner)

        // 2. Wrong password on subsequent login is rejected
        val badRes = manager.signInOrRegisterWithCredentials("alice", "wrongpass")
        assertTrue(badRes is AuthAttemptResult.Error)

        // 3. Record user activity for "alice"
        manager.recordUserActivity(
            uid = aliceSession.uid,
            username = aliceSession.username,
            actionType = "IMAGE_STUDIO",
            summary = "Generated portrait",
            authMethod = "password",
            isOwner = false
        )

        // 4. Owner signs in with "legendary" / "sanskarisgod"
        val ownerRes = manager.signInOrRegisterWithCredentials("legendary", "sanskarisgod")
        assertTrue(ownerRes is AuthAttemptResult.Success)
        val ownerSession = (ownerRes as AuthAttemptResult.Success).session
        assertTrue(ownerSession.isOwner)
        assertEquals("legendary", ownerSession.username)

        // 5. Verify Owner Directory Snapshot exposes user count, usernames, and activities
        val directory = manager.getDirectory()
        assertTrue(directory.totalUsers >= 2)
        val usernames = directory.users.map { it.username }
        assertTrue("alice" in usernames)
        assertTrue("legendary" in usernames)
        assertTrue(directory.activities.any { it.username == "alice" && it.actionType == "IMAGE_STUDIO" })
    }
}
