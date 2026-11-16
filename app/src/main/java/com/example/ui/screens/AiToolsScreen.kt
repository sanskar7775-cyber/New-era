package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ai.ImageEditAction
import com.example.ai.ImageEngineProfile
import com.example.ai.VideoEngineProfile
import com.example.ai.VideoStylePreset
import com.example.ai.engine.GatewayChannel
import com.example.ai.engine.GraphMemoryState
import com.example.ai.engine.MemoryNodeType
import com.example.ai.engine.ScopedEphemeralToken
import com.example.data.model.AiToolKind
import com.example.media.MediaExportHelper
import com.example.ui.ToolResultState
import com.example.video.VideoColorGrade
import com.example.video.VideoMotionStyle
import com.example.video.VideoQualityPreset
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageSize
import kotlinx.coroutines.launch

@Composable
fun AiToolsScreen(
    toolResultState: ToolResultState,
    requestedTabIndex: Int? = null,
    onRequestedTabConsumed: () -> Unit = {},
    graphMemoryState: GraphMemoryState = GraphMemoryState(),
    gatewayTokens: List<ScopedEphemeralToken> = emptyList(),
    onRunAiTool: (AiToolKind, String, Bitmap?) -> Unit,
    onGenerateImage: (String, AspectRatio, ImageSize, ImageEngineProfile, ImageEditAction, Boolean, Bitmap?) -> Unit,
    onGenerateVideo: (String, AspectRatio, VideoQualityPreset, VideoStylePreset, VideoEngineProfile, Bitmap?, Int, VideoMotionStyle?, VideoColorGrade, Boolean) -> Unit,
    onSynthesizeBlueprint: (String, String?) -> Unit = { _, _ -> },
    onAddMemoryNode: (MemoryNodeType, String, String) -> Unit = { _, _, _ -> },
    onDeleteMemoryNode: (String) -> Unit = {},
    onRotateGatewayToken: (GatewayChannel) -> Unit = {},
    onRevokeGatewayToken: (GatewayChannel) -> Unit = {},
    onAnalyzeVision: (Bitmap, String) -> Unit,
    onSaveResultToVault: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedTool by remember { mutableStateOf(AiToolKind.WEB_BRIEF) }
    var toolPrompt by remember { mutableStateOf("") }

    var isImageEditMode by remember { mutableStateOf(false) }
    var imagePrompt by remember { mutableStateOf("") }
    var selectedImageRatio by remember { mutableStateOf(AspectRatio.SQUARE_1x1) }
    var selectedImageSize by remember { mutableStateOf(ImageSize.SIZE_2K) }
    var selectedImageEngine by remember { mutableStateOf(ImageEngineProfile.NANO_BANANA_2_2_1) }
    var selectedEditAction by remember { mutableStateOf(ImageEditAction.CUSTOM) }
    var enhanceImagePrompt by remember { mutableStateOf(true) }
    var imageRefBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var isVideoEditMode by remember { mutableStateOf(false) }
    var videoPrompt by remember { mutableStateOf("") }
    var selectedVideoEngine by remember { mutableStateOf(VideoEngineProfile.GOOGLE_GEMINI_OMNI_VEO_3_1) }
    var selectedVideoRatio by remember { mutableStateOf(AspectRatio.LANDSCAPE_16x9) }
    var selectedVideoQuality by remember { mutableStateOf(VideoQualityPreset.FULL_HD_1080P) }
    var selectedVideoStyle by remember { mutableStateOf(VideoStylePreset.CINEMATIC) }
    var secondsPerScene by remember { mutableIntStateOf(3) }
    var forcedVideoMotion by remember { mutableStateOf<VideoMotionStyle?>(null) }
    var videoColorGrade by remember { mutableStateOf(VideoColorGrade.NATURAL) }
    var videoRefBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showVideoCaptions by remember { mutableStateOf(true) }

    var visionBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var visionPrompt by remember { mutableStateOf("") }
    var architectGoalPrompt by remember { mutableStateOf("") }

    LaunchedEffect(requestedTabIndex) {
        if (requestedTabIndex != null && requestedTabIndex in 0..5) {
            selectedTab = requestedTabIndex
            onRequestedTabConsumed()
        }
    }

    val refImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            imageRefBitmap = loadScaledBitmap(context, uri)
            if (imageRefBitmap != null) isImageEditMode = true
        }
    }

    val refVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            videoRefBitmap = loadScaledBitmap(context, uri)
            if (videoRefBitmap != null) isVideoEditMode = true
        }
    }

    val visionPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            visionBitmap = loadScaledBitmap(context, uri)
        }
    }

    val isLoading = toolResultState is ToolResultState.Loading

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "AI Tools & Creative Studios",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Image & Video Creation/Editing Studios, Web Research & Vision",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Text & Web") },
                        modifier = Modifier.testTag("tab_text_tools")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Image Studio") },
                        modifier = Modifier.testTag("tab_image_forge")
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Video Studio") },
                        modifier = Modifier.testTag("tab_video_studio")
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text("Vision Lab") },
                        modifier = Modifier.testTag("tab_vision_lab")
                    )
                    Tab(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        text = { Text("Architect") },
                        modifier = Modifier.testTag("tab_zero_shot_architect")
                    )
                    Tab(
                        selected = selectedTab == 5,
                        onClick = { selectedTab = 5 },
                        text = { Text("Memory & Vault") },
                        modifier = Modifier.testTag("tab_graph_gateway")
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AiToolKind.entries.forEach { kind ->
                                FilterChip(
                                    selected = selectedTool == kind,
                                    onClick = { selectedTool = kind },
                                    label = { Text(kind.title) },
                                    modifier = Modifier.testTag("chip_tool_${kind.name.lowercase()}")
                                )
                            }
                        }
                    }

                    item {
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
                                Text(
                                    text = selectedTool.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = selectedTool.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = toolPrompt,
                                    onValueChange = { toolPrompt = it },
                                    placeholder = { Text(selectedTool.placeholder) },
                                    minLines = 3,
                                    maxLines = 6,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ai_tool_input")
                                )
                                Button(
                                    onClick = { onRunAiTool(selectedTool, toolPrompt, null) },
                                    enabled = toolPrompt.isNotBlank() && !isLoading,
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("run_ai_tool_button")
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Run ${selectedTool.title}")
                                }
                            }
                        }
                    }
                }

                1 -> {
                    item {
                        ImageStudioPanel(
                            isEditMode = isImageEditMode,
                            onEditModeChange = { isImageEditMode = it },
                            prompt = imagePrompt,
                            onPromptChange = { imagePrompt = it },
                            selectedRatio = selectedImageRatio,
                            onRatioChange = { selectedImageRatio = it },
                            selectedSize = selectedImageSize,
                            onSizeChange = { selectedImageSize = it },
                            selectedEngine = selectedImageEngine,
                            onEngineChange = { selectedImageEngine = it },
                            selectedEditAction = selectedEditAction,
                            onEditActionChange = { selectedEditAction = it },
                            enhancePrompt = enhanceImagePrompt,
                            onEnhancePromptChange = { enhanceImagePrompt = it },
                            referenceBitmap = imageRefBitmap,
                            onPickReference = {
                                refImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onClearReference = { imageRefBitmap = null },
                            isLoading = isLoading,
                            onGenerate = {
                                onGenerateImage(
                                    imagePrompt,
                                    selectedImageRatio,
                                    selectedImageSize,
                                    selectedImageEngine,
                                    selectedEditAction,
                                    enhanceImagePrompt,
                                    if (isImageEditMode) imageRefBitmap else imageRefBitmap
                                )
                            }
                        )
                    }
                }

                2 -> {
                    item {
                        VideoStudioPanel(
                            isEditVideoMode = isVideoEditMode,
                            onEditVideoModeChange = { isVideoEditMode = it },
                            prompt = videoPrompt,
                            onPromptChange = { videoPrompt = it },
                            selectedEngine = selectedVideoEngine,
                            onEngineChange = { selectedVideoEngine = it },
                            selectedRatio = selectedVideoRatio,
                            onRatioChange = { selectedVideoRatio = it },
                            selectedQuality = selectedVideoQuality,
                            onQualityChange = { selectedVideoQuality = it },
                            selectedStyle = selectedVideoStyle,
                            onStyleChange = { selectedVideoStyle = it },
                            secondsPerScene = secondsPerScene,
                            onSecondsPerSceneChange = { secondsPerScene = it },
                            forcedMotion = forcedVideoMotion,
                            onForcedMotionChange = { forcedVideoMotion = it },
                            colorGrade = videoColorGrade,
                            onColorGradeChange = { videoColorGrade = it },
                            referenceBitmap = videoRefBitmap,
                            onPickReference = {
                                refVideoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onClearReference = { videoRefBitmap = null },
                            showCaptions = showVideoCaptions,
                            onShowCaptionsChange = { showVideoCaptions = it },
                            isLoading = isLoading,
                            onGenerate = {
                                onGenerateVideo(
                                    videoPrompt,
                                    selectedVideoRatio,
                                    selectedVideoQuality,
                                    selectedVideoStyle,
                                    selectedVideoEngine,
                                    videoRefBitmap,
                                    secondsPerScene,
                                    forcedVideoMotion,
                                    videoColorGrade,
                                    showVideoCaptions
                                )
                            }
                        )
                    }
                }

                3 -> {
                    item {
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
                                Text(
                                    text = "Vision Analyzer",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedButton(
                                    onClick = {
                                        visionPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("pick_vision_photo_button")
                                ) {
                                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (visionBitmap == null) "Select Photo" else "Change Photo")
                                }

                                if (visionBitmap != null) {
                                    Image(
                                        bitmap = visionBitmap!!.asImageBitmap(),
                                        contentDescription = "Selected photo for analysis",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }

                                OutlinedTextField(
                                    value = visionPrompt,
                                    onValueChange = { visionPrompt = it },
                                    placeholder = { Text("Ask anything about this image...") },
                                    minLines = 2,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("vision_prompt_input")
                                )

                                Button(
                                    onClick = { visionBitmap?.let { onAnalyzeVision(it, visionPrompt) } },
                                    enabled = visionBitmap != null && !isLoading,
                                    shape = RoundedCornerShape(999.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("run_vision_button")
                                ) {
                                    Text("Analyze Image")
                                }
                            }
                        }
                    }
                }

                4 -> {
                    item {
                        ZeroShotArchitectPanel(
                            goalPrompt = architectGoalPrompt,
                            onGoalPromptChange = { architectGoalPrompt = it },
                            isLoading = isLoading,
                            onSynthesize = { onSynthesizeBlueprint(architectGoalPrompt, null) }
                        )
                    }
                }

                5 -> {
                    item {
                        GraphMemoryAndGatewayPanel(
                            graphState = graphMemoryState,
                            gatewayTokens = gatewayTokens,
                            onAddNode = onAddMemoryNode,
                            onDeleteNode = onDeleteMemoryNode,
                            onRotateToken = onRotateGatewayToken,
                            onRevokeToken = onRevokeGatewayToken
                        )
                    }
                }
            }

            // Output Area
            item {
                when (toolResultState) {
                    is ToolResultState.Idle -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Choose any tool, Image Studio preset, or Video Studio engine above.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }

                    is ToolResultState.Loading -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (toolResultState.progress != null) {
                                    LinearProgressIndicator(
                                        progress = { toolResultState.progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                                Text(
                                    text = toolResultState.statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is ToolResultState.TextSuccess -> {
                        ResultTextCard(
                            title = toolResultState.tool.title,
                            body = toolResultState.output,
                            onSaveToVault = { onSaveResultToVault(toolResultState.tool.title, toolResultState.output) },
                            onShare = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, toolResultState.output)
                                }
                                runCatching { context.startActivity(Intent.createChooser(share, "Share AI Result")) }
                            }
                        )
                    }

                    is ToolResultState.VisionSuccess -> {
                        ResultTextCard(
                            title = "Vision Analysis",
                            body = toolResultState.analysis,
                            onSaveToVault = { onSaveResultToVault("Vision Analysis", toolResultState.analysis) },
                            onShare = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, toolResultState.analysis)
                                }
                                runCatching { context.startActivity(Intent.createChooser(share, "Share Vision Analysis")) }
                            }
                        )
                    }

                    is ToolResultState.ImageSuccess -> {
                        val out = toolResultState.output
                        ImageResultCard(
                            output = out,
                            onEditThis = { bmp ->
                                imageRefBitmap = bmp
                                isImageEditMode = true
                                selectedTab = 1
                            },
                            onAnimateToVideo = { bmp ->
                                videoRefBitmap = bmp
                                isVideoEditMode = true
                                if (videoPrompt.isBlank()) videoPrompt = out.rawPrompt
                                selectedTab = 2
                            },
                            onSaveGallery = {
                                scope.launch {
                                    MediaExportHelper.saveBitmapAndGetUri(context, out.bitmap)
                                        .onSuccess {
                                            Toast.makeText(context, "Saved PNG to Pictures/NewEra", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(context, it.localizedMessage ?: "Save failed", Toast.LENGTH_SHORT).show()
                                        }
                                }
                            },
                            onShare = {
                                MediaExportHelper.shareBitmap(context, out.bitmap, "Share New Era Artwork")
                            }
                        )
                    }

                    is ToolResultState.VideoSuccess -> {
                        val vid = toolResultState.video
                        VideoResultCard(
                            video = vid,
                            onSaveGallery = {
                                scope.launch {
                                    MediaExportHelper.saveVideoToGallery(context, vid.file)
                                        .onSuccess {
                                            Toast.makeText(context, "Saved MP4 to Movies/NewEra", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(context, it.localizedMessage ?: "Save failed", Toast.LENGTH_SHORT).show()
                                        }
                                }
                            },
                            onShare = {
                                MediaExportHelper.shareFile(context, vid.file, "video/mp4", "Share New Era Video")
                            }
                        )
                    }

                    is ToolResultState.BlueprintSuccess -> {
                        val bp = toolResultState.blueprint
                        BlueprintResultCard(
                            blueprint = bp,
                            onOverrideArchitecture = { overrideReq ->
                                onSynthesizeBlueprint(architectGoalPrompt.ifBlank { bp.projectName }, overrideReq)
                            },
                            onSaveToVault = {
                                onSaveResultToVault(
                                    "Architecture: ${bp.projectName}",
                                    "${bp.chosenStack}\n\n${bp.databaseAndStateDesign}\n\nChangelog:\n${bp.architecturalChangelog.joinToString("\n- ")}"
                                )
                            }
                        )
                    }

                    is ToolResultState.Error -> {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = toolResultState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultTextCard(
    title: String,
    body: String,
    onSaveToVault: () -> Unit,
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSaveToVault,
                    modifier = Modifier.testTag("save_tool_result_button")
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Note")
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.testTag("share_tool_result_button")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}
