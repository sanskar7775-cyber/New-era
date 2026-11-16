package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.video.EnhancedVideoRenderer
import com.example.video.GeneratedVideoResult
import com.example.video.RenderedSceneFrameInput
import com.example.video.VideoColorGrade
import com.example.video.VideoMotionStyle
import com.example.video.VideoQualityPreset
import com.example.video.VideoScenePlan
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class ImageQualityModel(
    val modelId: String,
    val label: String,
    val description: String
) {
    STUDIO_PRO(
        modelId = "gemini-3-pro-image",
        label = "Studio Pro",
        description = "Flagship 4K fidelity & crisp typography"
    ),
    NANO_FAST(
        modelId = "gemini-nano-banana-2.1",
        label = "Nano 2.1 Fast",
        description = "High-speed photoreal & style synthesis"
    )
}

enum class ImageEngineProfile(
    val aliasName: String,
    val originalName: String,
    val modelTier: ImageQualityModel,
    val specialty: String,
    val styleDirective: String
) {
    GOOGLE_NANO_BANANA_2_1(
        aliasName = "New Era SwiftGen",
        originalName = "Google Nano Banana 2.1",
        modelTier = ImageQualityModel.NANO_FAST,
        specialty = "Fast native photorealism & natural lighting",
        styleDirective = "sharp authentic photographic realism, balanced natural daylight, rich micro-texture, zero artifacts"
    ),
    NANO_BANANA_2_2_1(
        aliasName = "New Era PrimeGen",
        originalName = "Nano Banana 2 / 2.1",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Studio-pro clarity, faithful edits & skin detail",
        styleDirective = "flagship studio-pro photography fidelity, crisp textures, true-to-life depth and tonal range"
    ),
    CHATGPT_GPT_IMAGE_2_5(
        aliasName = "New Era OmniCanvas",
        originalName = "ChatGPT GPT-Image-2.5",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Instruction-faithful edits & razor-sharp text",
        styleDirective = "exact instruction adherence, clean commercial typography legibility, balanced studio framing, accurate object placement"
    ),
    MIDJOURNEY(
        aliasName = "New Era DreamForge",
        originalName = "Midjourney",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Dramatic cinema lighting & rich artistic depth",
        styleDirective = "dramatic chiaroscuro lighting, rich filmic color palette, intricate atmospheric storytelling, editorial medium-format depth"
    ),
    ADOBE_FIREFLY_PHOTOSHOP(
        aliasName = "New Era Prism Studio",
        originalName = "Adobe Firefly / Photoshop",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Generative fill, clean retouching & compositing",
        styleDirective = "commercial-clean Photoshop retouching precision, seamless lighting match, realistic shadows, studio color grading"
    ),
    RECRAFT(
        aliasName = "New Era VectorCraft",
        originalName = "Recraft",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Crisp vector art, icons & brand illustrations",
        styleDirective = "crisp scalable graphic design aesthetics, clean geometry, harmonious flat and 3D brand illustration forms"
    ),
    CANVA_AI(
        aliasName = "New Era LayoutSpark",
        originalName = "Canva AI",
        modelTier = ImageQualityModel.NANO_FAST,
        specialty = "Poster compositions, banners & visual layouts",
        styleDirective = "modern clean social media poster & visual layout composition, vibrant balanced visual hierarchy"
    ),
    LEONARDO_AI(
        aliasName = "New Era ConceptForge",
        originalName = "Leonardo.Ai",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Game concept art, armor, worlds & characters",
        styleDirective = "highly detailed fantasy & sci-fi concept art, PBR material texture, epic volumetric environment rim lighting"
    ),
    DEEPAI(
        aliasName = "New Era InstantRender",
        originalName = "DeepAI",
        modelTier = ImageQualityModel.NANO_FAST,
        specialty = "Rapid imaginative ideation & punchy renders",
        styleDirective = "punchy high-contrast imaginative digital illustration, bold vivid color expression"
    );

    val displayName: String
        get() = "$aliasName ($originalName)"
}

enum class ImageEditAction(
    val label: String,
    val instructionPrefix: String
) {
    CUSTOM("Custom Edit", ""),
    RETOUCH_RELIGHT(
        "Retouch & Relight",
        "Professionally retouch, sharpen micro-details, and apply soft studio lighting while preserving subject identity:"
    ),
    SWAP_BACKGROUND(
        "Background Swap",
        "Keep the foreground subject untouched and seamlessly replace the background with realistic shadow matching:"
    ),
    CINEMATIC_GRADE(
        "Cinema Color Grade",
        "Apply rich anamorphic movie color grading, balanced contrast, and atmospheric depth:"
    ),
    RESTYLE_3D_ART(
        "3D / Stylized Art",
        "Transform into high-end stylized 3D illustration keeping composition and subject features consistent:"
    ),
    REMOVE_CLEANUP(
        "Object Cleanup",
        "Cleanly remove distracting elements or perform requested inpaint edit seamlessly:"
    )
}

enum class VideoEngineProfile(
    val aliasName: String,
    val originalName: String,
    val modelTier: ImageQualityModel,
    val specialty: String,
    val directionHint: String
) {
    KLING_4_0(
        aliasName = "New Era MotionApex",
        originalName = "Kling 4.0",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Dynamic action choreography & crisp subject motion",
        directionHint = "high-precision kinetic choreography, fluid realistic human/object action, dramatic tracking shots"
    ),
    KIMI_PRO_MAX(
        aliasName = "New Era UltraDirector",
        originalName = "Kimi Pro Max",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Deep multi-shot story continuity & atmospheric pacing",
        directionHint = "cohesive multi-shot narrative arc, unbroken character and wardrobe continuity, dramatic lens transitions"
    ),
    WAM_3_0(
        aliasName = "New Era PhysicsEngine",
        originalName = "WAM 3.0",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Grounded real-world physics, water, fabric & light",
        directionHint = "physically grounded environment dynamics, natural wind, water, reflections, and realistic gravity motion"
    ),
    DREAMINA_SEEDANCE_2_5(
        aliasName = "New Era ChoreoCinema",
        originalName = "Dreamina Seedance 2.5",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Expressive rhythm, anime/cinema framing & flair",
        directionHint = "expressive rhythmic camera movement, stylized kinetic framing, vibrant atmospheric depth"
    ),
    GOOGLE_GEMINI_OMNI_VEO_3_1(
        aliasName = "New Era OmniVeo",
        originalName = "Google Gemini Omni / Veo 3.1",
        modelTier = ImageQualityModel.STUDIO_PRO,
        specialty = "Flagship photoreal cinema depth & crisp 1080p frames",
        directionHint = "35mm anamorphic cinema photorealism, rich volumetric light, razor-sharp 8K texture coherence"
    );

    val displayName: String
        get() = "$aliasName ($originalName)"
}

enum class VideoStylePreset(
    val label: String,
    val directionHint: String
) {
    CINEMATIC(
        label = "Cinematic",
        directionHint = "35mm anamorphic cinema lens, dramatic volumetric lighting, rich filmic contrast, depth of field"
    ),
    HYPERREAL(
        label = "Hyper-Real",
        directionHint = "ultra-sharp 8K natural photorealism, physically accurate reflections, realistic motion framing"
    ),
    FUTURISTIC(
        label = "Futuristic",
        directionHint = "sleek architectural sci-fi aesthetic, atmospheric light rays, crisp metallic & glass textures"
    ),
    ANIMATED_3D(
        label = "3D Film",
        directionHint = "feature-film stylized 3D animation, global illumination, expressive storytelling composition"
    )
}

data class GeneratedImageOutput(
    val bitmap: Bitmap,
    val rawPrompt: String,
    val enhancedPrompt: String,
    val modelLabel: String,
    val resolutionLabel: String
)

class AiCreativeMediaService(
    private val videoRenderer: EnhancedVideoRenderer
) {
    private val directorTextModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    private val storyboardModel: GenerativeModel by lazy {
        val sceneSchema = Schema.obj(
            properties = mapOf(
                "sceneTitle" to Schema.string("Short title for this scene"),
                "visualPrompt" to Schema.string("Detailed consistent visual generation prompt for this exact frame"),
                "narration" to Schema.string("Short crisp subtitle line under 12 words"),
                "motionStyle" to Schema.enumeration(
                    values = listOf("ZOOM_IN", "PAN_RIGHT", "PAN_LEFT", "ZOOM_OUT"),
                    description = "Camera motion direction"
                )
            )
        )
        val rootSchema = Schema.obj(
            properties = mapOf(
                "directorSummary" to Schema.string("One-sentence creative direction overview"),
                "scenes" to Schema.array(
                    items = sceneSchema,
                    description = "Exactly 4 cohesive sequential video scenes with consistent style and subject continuity"
                )
            )
        )
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = rootSchema
            }
        )
    }

    suspend fun enhanceVisualPrompt(
        rawPrompt: String,
        styleHint: String = ""
    ): String {
        val cleaned = rawPrompt.trim()
        if (cleaned.isBlank()) return cleaned
        return try {
            withTimeout(25_000L) {
                val instruction = buildString {
                    appendLine("Rewrite the following image generation prompt into a single ultra-detailed, studio-grade photography/art-direction prompt.")
                    appendLine("Keep the exact subject and intent, and enrich lighting, lens/camera perspective, texture, color harmony, and composition.")
                    if (styleHint.isNotBlank()) appendLine("Engine art direction: $styleHint")
                    appendLine("Output ONLY the enhanced prompt paragraph (max 85 words), with no preamble or quotes.")
                    appendLine("Original prompt: $cleaned")
                }
                directorTextModel.generateContent(instruction).text?.trim()
                    ?.ifBlank { cleaned } ?: cleaned
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cleaned
        }
    }

    suspend fun generateAdvancedImage(
        prompt: String,
        aspectRatio: AspectRatio = AspectRatio.SQUARE_1x1,
        imageSize: ImageSize = ImageSize.SIZE_2K,
        engineProfile: ImageEngineProfile = ImageEngineProfile.NANO_BANANA_2_2_1,
        editAction: ImageEditAction = ImageEditAction.CUSTOM,
        enhancePrompt: Boolean = true,
        referenceImage: Bitmap? = null
    ): GeneratedImageOutput {
        val raw = prompt.trim()
        require(raw.isNotBlank()) { "Prompt must not be empty." }

        val styledEditPrompt = buildString {
            if (referenceImage != null && editAction.instructionPrefix.isNotBlank()) {
                append(editAction.instructionPrefix).append(" ")
            }
            append(raw)
            append(". Aesthetic & engine look: ").append(engineProfile.styleDirective)
        }

        val finalPrompt = if (enhancePrompt && referenceImage == null) {
            enhanceVisualPrompt(raw, styleHint = engineProfile.styleDirective)
        } else {
            styledEditPrompt
        }

        val bitmap = synthesizeSingleImage(
            prompt = finalPrompt,
            aspectRatio = aspectRatio,
            imageSize = imageSize,
            modelId = engineProfile.modelTier.modelId,
            referenceImage = referenceImage
        )

        val resLabel = when (imageSize) {
            ImageSize.SIZE_4K -> "4K Ultra"
            ImageSize.SIZE_2K -> "2K HD"
            ImageSize.SIZE_512 -> "512px"
            else -> "1K"
        }

        return GeneratedImageOutput(
            bitmap = bitmap,
            rawPrompt = raw,
            enhancedPrompt = finalPrompt,
            modelLabel = engineProfile.displayName,
            resolutionLabel = resLabel
        )
    }

    suspend fun generateEnhancedVideo(
        prompt: String,
        aspectRatio: AspectRatio = AspectRatio.LANDSCAPE_16x9,
        qualityPreset: VideoQualityPreset = VideoQualityPreset.FULL_HD_1080P,
        stylePreset: VideoStylePreset = VideoStylePreset.CINEMATIC,
        videoEngine: VideoEngineProfile = VideoEngineProfile.GOOGLE_GEMINI_OMNI_VEO_3_1,
        referenceImage: Bitmap? = null,
        secondsPerScene: Int = 3,
        forcedMotion: VideoMotionStyle? = null,
        colorGrade: VideoColorGrade = VideoColorGrade.NATURAL,
        showCaptions: Boolean = true,
        onStageUpdate: (String, Float) -> Unit = { _, _ -> }
    ): GeneratedVideoResult {
        val cleaned = prompt.trim()
        require(cleaned.isNotBlank()) { "Video prompt must not be empty." }

        return try {
            withTimeout(210_000L) {
                onStageUpdate("${videoEngine.aliasName}: Directing storyboard...", 0.08f)
                val (summary, scenePlans) = planVideoStoryboard(
                    userPrompt = cleaned,
                    stylePreset = stylePreset,
                    videoEngine = videoEngine,
                    hasReferenceImage = referenceImage != null
                )

                val keyframeSize = if (qualityPreset == VideoQualityPreset.FULL_HD_1080P) {
                    ImageSize.SIZE_2K
                } else {
                    ImageSize.SIZE_1K
                }

                val sceneInputs = mutableListOf<RenderedSceneFrameInput>()
                scenePlans.forEachIndexed { idx, scene ->
                    val baseProgress = 0.12f + (idx.toFloat() / scenePlans.size) * 0.58f
                    onStageUpdate(
                        "${videoEngine.aliasName}: Rendering scene ${idx + 1}/${scenePlans.size} (${scene.sceneTitle})",
                        baseProgress
                    )
                    val anchorBitmap = if (idx == 0) referenceImage else (sceneInputs.firstOrNull()?.bitmap ?: referenceImage)
                    val frameBitmap = synthesizeSingleImage(
                        prompt = "${scene.visualPrompt}. Engine aesthetic: ${videoEngine.directionHint}. Style: ${stylePreset.directionHint}. Maintain strict continuity and ultra-clean detail.",
                        aspectRatio = aspectRatio,
                        imageSize = keyframeSize,
                        modelId = videoEngine.modelTier.modelId,
                        referenceImage = anchorBitmap
                    )
                    sceneInputs.add(RenderedSceneFrameInput(bitmap = frameBitmap, plan = scene))
                }

                onStageUpdate("Encoding ${qualityPreset.label} MP4 cinema video...", 0.75f)
                val mp4File = videoRenderer.renderCinemaMp4(
                    scenes = sceneInputs,
                    aspectRatio = aspectRatio,
                    qualityPreset = qualityPreset,
                    showCaptions = showCaptions,
                    secondsPerScene = secondsPerScene,
                    forcedMotionStyle = forcedMotion,
                    colorGrade = colorGrade,
                    onProgress = { encodePct ->
                        onStageUpdate(
                            "Encoding ${qualityPreset.label} MP4 (${(encodePct * 100).toInt()}%)...",
                            0.75f + encodePct * 0.25f
                        )
                    }
                )

                val (w, h) = EnhancedVideoRenderer.computeDimensions(aspectRatio, qualityPreset)
                GeneratedVideoResult(
                    file = mp4File,
                    prompt = cleaned,
                    directorSummary = summary,
                    scenes = scenePlans,
                    durationSeconds = scenePlans.size * secondsPerScene.coerceIn(2, 5),
                    width = w,
                    height = h,
                    qualityLabel = "${qualityPreset.label} • ${colorGrade.label} • 30fps MP4",
                    engineLabel = videoEngine.displayName
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Video generation timed out. Try 720p HD or shorter pacing.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateEnhancedVideo failed", e)
            throw e
        }
    }

    private suspend fun planVideoStoryboard(
        userPrompt: String,
        stylePreset: VideoStylePreset,
        videoEngine: VideoEngineProfile,
        hasReferenceImage: Boolean
    ): Pair<String, List<VideoScenePlan>> {
        val directive = """
            Plan a 4-scene cinematic video sequence for: "$userPrompt"
            Creative Engine Mode: ${videoEngine.displayName} (${videoEngine.directionHint})
            Visual style: ${stylePreset.label} (${stylePreset.directionHint})
            ${if (hasReferenceImage) "Starting from user's reference photo/frame — maintain the exact subject identity and transition naturally across shots." else ""}
            Ensure all 4 scenes maintain coherent character/subject appearance, environment progression, and clean framing with no embedded text overlays.
        """.trimIndent()

        val response = withTimeout(45_000L) { storyboardModel.generateContent(directive) }
        val json = JSONObject(response.text ?: throw IllegalStateException("Storyboard returned empty JSON."))
        val summary = json.optString("directorSummary", "Directed 4-scene sequence for $userPrompt")
        val arr = json.optJSONArray("scenes")
        val parsed = mutableListOf<VideoScenePlan>()
        if (arr != null) {
            for (i in 0 until arr.length().coerceAtMost(4)) {
                val obj = arr.getJSONObject(i)
                parsed.add(
                    VideoScenePlan(
                        sceneTitle = obj.optString("sceneTitle", "Scene ${i + 1}"),
                        visualPrompt = obj.optString("visualPrompt", userPrompt),
                        narration = obj.optString("narration", ""),
                        motionStyle = VideoMotionStyle.fromString(obj.optString("motionStyle", "ZOOM_IN"))
                    )
                )
            }
        }
        if (parsed.isEmpty()) {
            parsed.add(
                VideoScenePlan(
                    sceneTitle = "Opening Shot",
                    visualPrompt = userPrompt,
                    narration = userPrompt.take(60),
                    motionStyle = VideoMotionStyle.ZOOM_IN
                )
            )
        }
        return summary to parsed
    }

    private suspend fun synthesizeSingleImage(
        prompt: String,
        aspectRatio: AspectRatio,
        imageSize: ImageSize,
        modelId: String,
        referenceImage: Bitmap?
    ): Bitmap {
        return try {
            withTimeout(60_000L) {
                val config = generationConfig {
                    responseModalities = listOf(ResponseModality.IMAGE)
                    imageConfig = ImageConfig.builder()
                        .setAspectRatio(aspectRatio)
                        .setImageSize(imageSize)
                        .build()
                }
                val model = Firebase.ai.generativeModel(
                    modelName = modelId,
                    generationConfig = config
                )
                val response = if (referenceImage != null) {
                    model.generateContent(
                        content {
                            image(referenceImage)
                            text(prompt)
                        }
                    )
                } else {
                    model.generateContent(prompt)
                }
                val part = response.candidates.firstOrNull()
                    ?.content?.parts?.filterIsInstance<ImagePart>()
                    ?.firstOrNull()
                part?.image ?: throw IllegalStateException("No image returned by $modelId.")
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Image synthesis timed out after 60 seconds.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "synthesizeSingleImage failed with $modelId", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "AiCreativeMediaService"
    }
}
