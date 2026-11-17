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
    fun `owner 6 digit code verifies accurately and rejects invalid inputs`() {
        assertTrue(OwnerAccountPolicy.verifyOwnerCode("777777"))
        assertTrue(OwnerAccountPolicy.verifyOwnerCode(" 777777 "))
        assertFalse(OwnerAccountPolicy.verifyOwnerCode("123456"))
        assertFalse(OwnerAccountPolicy.verifyOwnerCode("77777"))
        assertFalse(OwnerAccountPolicy.verifyOwnerCode("abcdef"))
        assertFalse(OwnerAccountPolicy.verifyOwnerCode(""))
        assertFalse(OwnerAccountPolicy.verifyOwnerCode(null))
    }

    @Test
    fun `direct login supports optional 6 digit owner code and code rotation without Google`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = UserSessionManager(context)
        manager.signOutSession()

        // 1. Blank code signs in directly without owner privileges
        val directRes = manager.signInDirect("")
        assertTrue(directRes is DirectAuthResult.Success)
        val directSession = (directRes as DirectAuthResult.Success).session
        assertTrue(directSession.isSignedIn)
        assertFalse(directSession.isOwner)
        assertEquals("direct", directSession.authMethod)

        // 2. Wrong 6-digit code fails cleanly without crashing
        val wrongRes = manager.signInDirect("111111")
        assertTrue(wrongRes is DirectAuthResult.Error)

        // 3. Correct 6-digit Owner Code (777777) unlocks Owner privileges
        val ownerRes = manager.signInDirect("777777")
        assertTrue(ownerRes is DirectAuthResult.Success)
        val ownerSession = (ownerRes as DirectAuthResult.Success).session
        assertTrue(ownerSession.isSignedIn)
        assertTrue(ownerSession.isOwner)
        assertEquals("direct_owner", ownerSession.authMethod)

        // 4. Owner can rotate their 6-digit Owner Code
        val rotateRes = manager.updateOwnerCode("888999")
        assertTrue(rotateRes.isSuccess)
        assertTrue(manager.signInDirect("888999") is DirectAuthResult.Success)
        assertTrue(manager.signInDirect("777777") is DirectAuthResult.Error)

        // 5. Activity directory tracks direct sessions cleanly
        val directory = manager.getDirectory()
        assertTrue(directory.totalUsers >= 2)
        assertTrue(directory.activities.any { it.actionType == "OWNER_DIRECT_LOGIN" })
    }
}
