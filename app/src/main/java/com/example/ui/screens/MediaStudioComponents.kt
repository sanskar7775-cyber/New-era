package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ai.GeneratedImageOutput
import com.example.ai.ImageEditAction
import com.example.ai.ImageEngineProfile
import com.example.ai.VideoEngineProfile
import com.example.ai.VideoStylePreset
import com.example.video.GeneratedVideoResult
import com.example.video.VideoColorGrade
import com.example.video.VideoMotionStyle
import com.example.video.VideoQualityPreset
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageSize

@Composable
fun ImageStudioPanel(
    isEditMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    selectedRatio: AspectRatio,
    onRatioChange: (AspectRatio) -> Unit,
    selectedSize: ImageSize,
    onSizeChange: (ImageSize) -> Unit,
    selectedEngine: ImageEngineProfile,
    onEngineChange: (ImageEngineProfile) -> Unit,
    selectedEditAction: ImageEditAction,
    onEditActionChange: (ImageEditAction) -> Unit,
    enhancePrompt: Boolean,
    onEnhancePromptChange: (Boolean) -> Unit,
    referenceBitmap: Bitmap?,
    onPickReference: () -> Unit,
    onClearReference: () -> Unit,
    isLoading: Boolean,
    onGenerate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Image Creation & Editor",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !isEditMode,
                        onClick = { onEditModeChange(false) },
                        label = { Text("Create") }
                    )
                    FilterChip(
                        selected = isEditMode,
                        onClick = { onEditModeChange(true) },
                        label = { Text("Edit Photo") }
                    )
                }
            }

            Text(
                text = "Engine Preset",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImageEngineProfile.entries.forEach { engine ->
                    FilterChip(
                        selected = selectedEngine == engine,
                        onClick = { onEngineChange(engine) },
                        label = { Text(engine.displayName) },
                        modifier = Modifier.testTag("img_engine_${engine.name.lowercase()}")
                    )
                }
            }
            Text(
                text = "${selectedEngine.displayName} • ${selectedEngine.specialty}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (isEditMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ImageEditAction.entries.forEach { action ->
                        FilterChip(
                            selected = selectedEditAction == action,
                            onClick = { onEditActionChange(action) },
                            label = { Text(action.label) }
                        )
                    }
                }
            }

            if (isEditMode || referenceBitmap != null) {
                if (referenceBitmap != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            bitmap = referenceBitmap.asImageBitmap(),
                            contentDescription = "Reference photo for editing",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Text(
                            text = "Source photo loaded for editing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearReference) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear reference image")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onPickReference,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Photo to Edit")
                    }
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                placeholder = {
                    Text(
                        if (isEditMode || referenceBitmap != null) {
                            "Describe exact photo edits (e.g., change outfit, studio lighting, remove background)..."
                        } else {
                            "Describe the artwork, scene, poster, or photoreal shot..."
                        }
                    )
                },
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("image_gen_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "1K" to ImageSize.SIZE_1K,
                    "2K HD" to ImageSize.SIZE_2K,
                    "4K Ultra" to ImageSize.SIZE_4K
                ).forEach { (label, size) ->
                    FilterChip(
                        selected = selectedSize == size,
                        onClick = { onSizeChange(size) },
                        label = { Text(label) }
                    )
                }
                listOf(
                    "1:1" to AspectRatio.SQUARE_1x1,
                    "16:9" to AspectRatio.LANDSCAPE_16x9,
                    "9:16" to AspectRatio.PORTRAIT_9x16,
                    "4:3" to AspectRatio.LANDSCAPE_4x3,
                    "21:9" to AspectRatio.LANDSCAPE_21x9
                ).forEach { (label, ratio) ->
                    FilterChip(
                        selected = selectedRatio == ratio,
                        onClick = { onRatioChange(ratio) },
                        label = { Text(label) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI Prompt Enhancer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = enhancePrompt,
                    onCheckedChange = onEnhancePromptChange
                )
            }

            Button(
                onClick = onGenerate,
                enabled = prompt.isNotBlank() && !isLoading && (!isEditMode || referenceBitmap != null),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("generate_image_button")
            ) {
                Icon(
                    imageVector = if (isEditMode || referenceBitmap != null) Icons.Filled.AutoFixHigh else Icons.Filled.Image,
                    contentDescription = "Run image studio",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isEditMode || referenceBitmap != null) {
                        "Edit with ${selectedEngine.aliasName}"
                    } else {
                        "Create with ${selectedEngine.aliasName}"
                    }
                )
            }
        }
    }
}

@Composable
fun VideoStudioPanel(
    isEditVideoMode: Boolean,
    onEditVideoModeChange: (Boolean) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    selectedEngine: VideoEngineProfile,
    onEngineChange: (VideoEngineProfile) -> Unit,
    selectedRatio: AspectRatio,
    onRatioChange: (AspectRatio) -> Unit,
    selectedQuality: VideoQualityPreset,
    onQualityChange: (VideoQualityPreset) -> Unit,
    selectedStyle: VideoStylePreset,
    onStyleChange: (VideoStylePreset) -> Unit,
    secondsPerScene: Int,
    onSecondsPerSceneChange: (Int) -> Unit,
    forcedMotion: VideoMotionStyle?,
    onForcedMotionChange: (VideoMotionStyle?) -> Unit,
    colorGrade: VideoColorGrade,
    onColorGradeChange: (VideoColorGrade) -> Unit,
    referenceBitmap: Bitmap?,
    onPickReference: () -> Unit,
    onClearReference: () -> Unit,
    showCaptions: Boolean,
    onShowCaptionsChange: (Boolean) -> Unit,
    isLoading: Boolean,
    onGenerate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Video Creation & Editor",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !isEditVideoMode,
                        onClick = { onEditVideoModeChange(false) },
                        label = { Text("Create") }
                    )
                    FilterChip(
                        selected = isEditVideoMode,
                        onClick = { onEditVideoModeChange(true) },
                        label = { Text("Edit / Animate") }
                    )
                }
            }

            Text(
                text = "Video Engine",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoEngineProfile.entries.forEach { engine ->
                    FilterChip(
                        selected = selectedEngine == engine,
                        onClick = { onEngineChange(engine) },
                        label = { Text(engine.displayName) },
                        modifier = Modifier.testTag("vid_engine_${engine.name.lowercase()}")
                    )
                }
            }
            Text(
                text = "${selectedEngine.displayName} • ${selectedEngine.specialty}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (isEditVideoMode || referenceBitmap != null) {
                if (referenceBitmap != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            bitmap = referenceBitmap.asImageBitmap(),
                            contentDescription = "Starting frame for video animation/editing",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Text(
                            text = "Key frame loaded — scenes will animate & edit from this image",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearReference) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear video frame")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onPickReference,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Photo/Frame to Animate or Edit")
                    }
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                placeholder = {
                    Text(
                        if (isEditVideoMode || referenceBitmap != null) {
                            "Describe how to animate or edit the video scenes, motion & atmosphere..."
                        } else {
                            "Describe the video story, camera motion, and mood..."
                        }
                    )
                },
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video_gen_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoStylePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedStyle == preset,
                        onClick = { onStyleChange(preset) },
                        label = { Text(preset.label) }
                    )
                }
                VideoColorGrade.entries.forEach { grade ->
                    FilterChip(
                        selected = colorGrade == grade,
                        onClick = { onColorGradeChange(grade) },
                        label = { Text(grade.label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoQualityPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedQuality == preset,
                        onClick = { onQualityChange(preset) },
                        label = { Text(preset.label) }
                    )
                }
                listOf(2 to "8s Fast", 3 to "12s Std", 4 to "16s Slow").forEach { (sec, label) ->
                    FilterChip(
                        selected = secondsPerScene == sec,
                        onClick = { onSecondsPerSceneChange(sec) },
                        label = { Text(label) }
                    )
                }
                listOf(
                    "16:9" to AspectRatio.LANDSCAPE_16x9,
                    "9:16" to AspectRatio.PORTRAIT_9x16,
                    "1:1" to AspectRatio.SQUARE_1x1
                ).forEach { (label, ratio) ->
                    FilterChip(
                        selected = selectedRatio == ratio,
                        onClick = { onRatioChange(ratio) },
                        label = { Text(label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = forcedMotion == null,
                    onClick = { onForcedMotionChange(null) },
                    label = { Text("Motion: Auto") }
                )
                VideoMotionStyle.entries.forEach { m ->
                    FilterChip(
                        selected = forcedMotion == m,
                        onClick = { onForcedMotionChange(m) },
                        label = { Text(m.name.replace('_', ' ')) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scene Narration Captions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = showCaptions,
                    onCheckedChange = onShowCaptionsChange
                )
            }

            Button(
                onClick = onGenerate,
                enabled = prompt.isNotBlank() && !isLoading,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("generate_video_button")
            ) {
                Icon(Icons.Filled.Movie, contentDescription = "Generate video", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Render with ${selectedEngine.aliasName}")
            }
        }
    }
}

@Composable
fun ImageResultCard(
    output: GeneratedImageOutput,
    onEditThis: (Bitmap) -> Unit,
    onAnimateToVideo: (Bitmap) -> Unit,
    onSaveGallery: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${output.modelLabel} • ${output.resolutionLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Image(
                bitmap = output.bitmap.asImageBitmap(),
                contentDescription = "AI generated image: ${output.rawPrompt}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            )
            Text(
                text = output.enhancedPrompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onEditThis(output.bitmap) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = { onAnimateToVideo(output.bitmap) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Video")
                }
                OutlinedButton(onClick = onSaveGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
fun VideoResultCard(
    video: GeneratedVideoResult,
    onSaveGallery: () -> Unit,
    onShare: () -> Unit
) {
    val videoAspect = (video.width.toFloat() / video.height.coerceAtLeast(1)).coerceIn(0.5f, 2.2f)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("video_result_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${video.engineLabel} • ${video.qualityLabel} • ${video.durationSeconds}s (${video.width}x${video.height})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(Uri.fromFile(video.file))
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            start()
                        }
                    }
                },
                update = { view ->
                    val targetUri = Uri.fromFile(video.file)
                    if (view.tag != targetUri.toString()) {
                        view.tag = targetUri.toString()
                        view.setVideoURI(targetUri)
                        view.start()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoAspect)
                    .clip(RoundedCornerShape(12.dp))
            )

            Text(
                text = video.directorSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            video.scenes.forEachIndexed { idx, scene ->
                Text(
                    text = "${idx + 1}. ${scene.sceneTitle}: ${scene.narration}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSaveGallery,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_video_button")
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save MP4")
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("share_video_button")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share MP4")
                }
            }
        }
    }
}
