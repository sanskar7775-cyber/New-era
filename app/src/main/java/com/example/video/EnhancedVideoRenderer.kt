package com.example.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.firebase.ai.type.AspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class VideoMotionStyle {
    ZOOM_IN,
    PAN_RIGHT,
    PAN_LEFT,
    ZOOM_OUT;

    companion object {
        fun fromString(raw: String): VideoMotionStyle {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: ZOOM_IN
        }
    }
}

enum class VideoQualityPreset(
    val label: String,
    val shortEdge: Int,
    val longEdge: Int,
    val bitrateBps: Int,
    val fps: Int
) {
    HD_720P(
        label = "720p HD",
        shortEdge = 720,
        longEdge = 1280,
        bitrateBps = 6_000_000,
        fps = 30
    ),
    FULL_HD_1080P(
        label = "1080p Ultra",
        shortEdge = 1080,
        longEdge = 1920,
        bitrateBps = 10_000_000,
        fps = 30
    )
}

enum class VideoColorGrade(val label: String) {
    NATURAL("Natural"),
    VIBRANT("Vibrant"),
    WARM_CINEMA("Warm Cinema"),
    NOIR_BW("Noir B&W")
}

data class VideoScenePlan(
    val sceneTitle: String,
    val visualPrompt: String,
    val narration: String,
    val motionStyle: VideoMotionStyle
)

data class GeneratedVideoResult(
    val file: File,
    val prompt: String,
    val directorSummary: String,
    val scenes: List<VideoScenePlan>,
    val durationSeconds: Int,
    val width: Int,
    val height: Int,
    val qualityLabel: String,
    val engineLabel: String = "New Era OmniVeo (Google Gemini Omni / Veo 3.1)"
)

data class RenderedSceneFrameInput(
    val bitmap: Bitmap,
    val plan: VideoScenePlan
)

class EnhancedVideoRenderer(private val context: Context) {

    suspend fun renderCinemaMp4(
        scenes: List<RenderedSceneFrameInput>,
        aspectRatio: AspectRatio,
        qualityPreset: VideoQualityPreset,
        showCaptions: Boolean = true,
        secondsPerScene: Int = 3,
        forcedMotionStyle: VideoMotionStyle? = null,
        colorGrade: VideoColorGrade = VideoColorGrade.NATURAL,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.Default) {
        require(scenes.isNotEmpty()) { "At least one rendered scene is required to build video." }

        val (width, height) = computeDimensions(aspectRatio, qualityPreset)
        val fps = qualityPreset.fps
        val clippedSeconds = secondsPerScene.coerceIn(2, 5)
        val framesPerScene = fps * clippedSeconds
        val crossfadeFrames = (fps * 0.45f).roundToInt().coerceAtLeast(1)
        val totalFrames = scenes.size * framesPerScene

        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        val outFile = File(mediaDir, "new_era_video_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, qualityPreset.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var inputSurface: android.view.Surface? = null
        var muxerStarted = false
        var trackIndex = -1

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = buildColorFilter(colorGrade)
            }
            val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (width.coerceAtMost(height) * 0.036f).coerceIn(22f, 44f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setShadowLayer(6f, 0f, 2f, Color.argb(190, 0, 0, 0))
            }
            val dstRect = Rect(0, 0, width, height)
            val vignetteShader = LinearGradient(
                0f,
                height * 0.62f,
                0f,
                height.toFloat(),
                Color.TRANSPARENT,
                Color.argb(185, 8, 12, 20),
                Shader.TileMode.CLAMP
            )

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    codec.signalEndOfInputStream()
                }
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) {
                                throw IllegalStateException("MediaMuxer format changed twice")
                            }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val encodedData = codec.getOutputBuffer(outputIndex)
                                ?: throw IllegalStateException("Encoder output buffer was null")
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
            }

            for (globalFrame in 0 until totalFrames) {
                val sceneIdx = (globalFrame / framesPerScene).coerceAtMost(scenes.lastIndex)
                val frameInScene = globalFrame % framesPerScene
                val rawProgress = frameInScene.toFloat() / (framesPerScene - 1).coerceAtLeast(1)
                val eased = easeInOutCubic(rawProgress)
                val currentScene = scenes[sceneIdx]

                val canvas: Canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)

                    val activeMotion = forcedMotionStyle ?: currentScene.plan.motionStyle
                    val srcRect = computeKenBurnsSrcRect(
                        srcW = currentScene.bitmap.width,
                        srcH = currentScene.bitmap.height,
                        dstW = width,
                        dstH = height,
                        progress = eased,
                        motion = activeMotion
                    )
                    imagePaint.alpha = 255
                    canvas.drawBitmap(currentScene.bitmap, srcRect, dstRect, imagePaint)

                    // Smooth cross-dissolve into next scene at end of current scene
                    if (sceneIdx < scenes.lastIndex && frameInScene >= framesPerScene - crossfadeFrames) {
                        val blendT = (frameInScene - (framesPerScene - crossfadeFrames)).toFloat() / crossfadeFrames
                        val nextScene = scenes[sceneIdx + 1]
                        val nextRect = computeKenBurnsSrcRect(
                            srcW = nextScene.bitmap.width,
                            srcH = nextScene.bitmap.height,
                            dstW = width,
                            dstH = height,
                            progress = 0f,
                            motion = forcedMotionStyle ?: nextScene.plan.motionStyle
                        )
                        imagePaint.alpha = (blendT.coerceIn(0f, 1f) * 255f).roundToInt()
                        canvas.drawBitmap(nextScene.bitmap, nextRect, dstRect, imagePaint)
                        imagePaint.alpha = 255
                    }

                    // Bottom cinematic gradient & optional narration caption
                    if (showCaptions && currentScene.plan.narration.isNotBlank()) {
                        overlayPaint.shader = vignetteShader
                        canvas.drawRect(RectF(0f, height * 0.62f, width.toFloat(), height.toFloat()), overlayPaint)
                        overlayPaint.shader = null

                        val horizontalPad = (width * 0.08f).roundToInt()
                        val textWidth = (width - horizontalPad * 2).coerceAtLeast(100)
                        val layout = StaticLayout.Builder.obtain(
                            currentScene.plan.narration,
                            0,
                            currentScene.plan.narration.length,
                            textPaint,
                            textWidth
                        )
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setLineSpacing(4f, 1.05f)
                            .setMaxLines(2)
                            .build()

                        canvas.save()
                        val yTop = height - layout.height - (height * 0.055f)
                        canvas.translate(horizontalPad.toFloat(), yTop)
                        layout.draw(canvas)
                        canvas.restore()
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                drainEncoder(endOfStream = false)
                onProgress((globalFrame + 1).toFloat() / totalFrames)
            }

            drainEncoder(endOfStream = true)
            outFile
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { inputSurface?.release() }
        }
    }

    companion object {
        fun buildColorFilter(grade: VideoColorGrade): ColorMatrixColorFilter? {
            return when (grade) {
                VideoColorGrade.NATURAL -> null
                VideoColorGrade.VIBRANT -> {
                    val m = ColorMatrix().apply { setSaturation(1.28f) }
                    ColorMatrixColorFilter(m)
                }
                VideoColorGrade.WARM_CINEMA -> {
                    val warm = ColorMatrix(
                        floatArrayOf(
                            1.10f, 0f, 0f, 0f, 6f,
                            0f, 1.02f, 0f, 0f, 2f,
                            0f, 0f, 0.90f, 0f, -6f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    ColorMatrixColorFilter(warm)
                }
                VideoColorGrade.NOIR_BW -> {
                    val m = ColorMatrix().apply { setSaturation(0f) }
                    val contrast = ColorMatrix(
                        floatArrayOf(
                            1.18f, 0f, 0f, 0f, -14f,
                            0f, 1.18f, 0f, 0f, -14f,
                            0f, 0f, 1.18f, 0f, -14f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    m.postConcat(contrast)
                    ColorMatrixColorFilter(m)
                }
            }
        }

        fun computeDimensions(
            aspectRatio: AspectRatio,
            preset: VideoQualityPreset
        ): Pair<Int, Int> {
            val raw = when (aspectRatio) {
                AspectRatio.PORTRAIT_9x16,
                AspectRatio.PORTRAIT_2x3,
                AspectRatio.PORTRAIT_3x4,
                AspectRatio.PORTRAIT_4x5 -> preset.shortEdge to preset.longEdge

                AspectRatio.SQUARE_1x1 -> preset.shortEdge to preset.shortEdge

                else -> preset.longEdge to preset.shortEdge
            }
            // Round to nearest multiple of 16 for universal MediaCodec H.264 alignment
            val alignedW = ((raw.first + 8) / 16) * 16
            val alignedH = ((raw.second + 8) / 16) * 16
            return alignedW to alignedH
        }

        fun easeInOutCubic(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return if (x < 0.5f) {
                4f * x * x * x
            } else {
                val f = -2f * x + 2f
                1f - (f * f * f) / 2f
            }
        }

        fun computeKenBurnsSrcRect(
            srcW: Int,
            srcH: Int,
            dstW: Int,
            dstH: Int,
            progress: Float,
            motion: VideoMotionStyle
        ): Rect {
            val p = progress.coerceIn(0f, 1f)
            val safeSrcW = srcW.coerceAtLeast(16)
            val safeSrcH = srcH.coerceAtLeast(16)
            val targetAspect = dstW.toFloat() / dstH.coerceAtLeast(1)
            val srcAspect = safeSrcW.toFloat() / safeSrcH

            val baseW: Float
            val baseH: Float
            if (srcAspect > targetAspect) {
                baseH = safeSrcH.toFloat()
                baseW = baseH * targetAspect
            } else {
                baseW = safeSrcW.toFloat()
                baseH = baseW / targetAspect
            }

            val scale = when (motion) {
                VideoMotionStyle.ZOOM_IN -> 1.0f - (0.14f * p)
                VideoMotionStyle.ZOOM_OUT -> 0.86f + (0.14f * p)
                VideoMotionStyle.PAN_RIGHT,
                VideoMotionStyle.PAN_LEFT -> 0.88f
            }

            val cropW = (baseW * scale).coerceAtMost(safeSrcW.toFloat())
            val cropH = (baseH * scale).coerceAtMost(safeSrcH.toFloat())

            val maxShiftX = ((safeSrcW - cropW) * 0.5f).coerceAtLeast(0f)
            val centerX = when (motion) {
                VideoMotionStyle.PAN_RIGHT -> (safeSrcW * 0.5f - maxShiftX * 0.8f) + (maxShiftX * 1.6f * p)
                VideoMotionStyle.PAN_LEFT -> (safeSrcW * 0.5f + maxShiftX * 0.8f) - (maxShiftX * 1.6f * p)
                else -> safeSrcW * 0.5f
            }
            val centerY = safeSrcH * 0.5f

            val left = (centerX - cropW * 0.5f).coerceIn(0f, safeSrcW - cropW)
            val top = (centerY - cropH * 0.5f).coerceIn(0f, safeSrcH - cropH)
            return Rect(
                left.roundToInt(),
                top.roundToInt(),
                (left + cropW).roundToInt().coerceAtMost(safeSrcW),
                (top + cropH).roundToInt().coerceAtMost(safeSrcH)
            )
        }
    }
}
