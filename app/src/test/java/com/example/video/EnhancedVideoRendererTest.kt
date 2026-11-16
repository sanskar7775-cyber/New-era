package com.example.video

import com.google.firebase.ai.type.AspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnhancedVideoRendererTest {

    @Test
    fun `computeDimensions produces 16-byte aligned dimensions for 720p and 1080p`() {
        val (w1080, h1080) = EnhancedVideoRenderer.computeDimensions(
            AspectRatio.LANDSCAPE_16x9,
            VideoQualityPreset.FULL_HD_1080P
        )
        assertEquals(1920, w1080)
        assertEquals(1088, h1080) // 1080 rounded to 16-aligned 1088
        assertEquals(0, w1080 % 16)
        assertEquals(0, h1080 % 16)

        val (wPort, hPort) = EnhancedVideoRenderer.computeDimensions(
            AspectRatio.PORTRAIT_9x16,
            VideoQualityPreset.HD_720P
        )
        assertEquals(720, wPort)
        assertEquals(1280, hPort)
        assertEquals(0, wPort % 16)
        assertEquals(0, hPort % 16)

        val (wSq, hSq) = EnhancedVideoRenderer.computeDimensions(
            AspectRatio.SQUARE_1x1,
            VideoQualityPreset.FULL_HD_1080P
        )
        assertEquals(1088, wSq)
        assertEquals(1088, hSq)
    }

    @Test
    fun `easeInOutCubic satisfies smooth easing invariants`() {
        assertEquals(0f, EnhancedVideoRenderer.easeInOutCubic(0f), 0.001f)
        assertEquals(0.5f, EnhancedVideoRenderer.easeInOutCubic(0.5f), 0.001f)
        assertEquals(1f, EnhancedVideoRenderer.easeInOutCubic(1f), 0.001f)
        assertTrue(EnhancedVideoRenderer.easeInOutCubic(0.25f) < 0.25f)
        assertTrue(EnhancedVideoRenderer.easeInOutCubic(0.75f) > 0.75f)
    }

    @Test
    fun `computeKenBurnsSrcRect always stays inside source bounds across motions`() {
        for (motion in VideoMotionStyle.entries) {
            for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val rect = EnhancedVideoRenderer.computeKenBurnsSrcRect(
                    srcW = 1920,
                    srcH = 1080,
                    dstW = 1280,
                    dstH = 720,
                    progress = p,
                    motion = motion
                )
                assertTrue(rect.left >= 0)
                assertTrue(rect.top >= 0)
                assertTrue(rect.right <= 1920)
                assertTrue(rect.bottom <= 1080)
                assertTrue(rect.width() > 0)
                assertTrue(rect.height() > 0)
            }
        }
    }
}
