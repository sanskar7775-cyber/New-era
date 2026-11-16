package com.example.googleapps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoogleAppsManagerTest {

    @Test
    fun `fromId resolves supported Google apps accurately`() {
        assertEquals(GoogleAppIntegration.GMAIL, GoogleAppIntegration.fromId("gmail"))
        assertEquals(GoogleAppIntegration.CALENDAR, GoogleAppIntegration.fromId("Google Calendar"))
        assertEquals(GoogleAppIntegration.DRIVE, GoogleAppIntegration.fromId("drive"))
        assertEquals(GoogleAppIntegration.TASKS, GoogleAppIntegration.fromId("tasks"))
    }

    @Test
    fun `revoking app permission immediately blocks access check`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GoogleAppsManager(context)

        manager.setAppPermission(GoogleAppIntegration.GMAIL, false)
        assertFalse(manager.isAppPermitted(GoogleAppIntegration.GMAIL))

        val result = manager.requireAuthorizedTokenFor(GoogleAppIntegration.GMAIL)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Permission denied by user") == true)

        manager.setAppPermission(GoogleAppIntegration.GMAIL, true)
        assertTrue(manager.isAppPermitted(GoogleAppIntegration.GMAIL))
    }
}
