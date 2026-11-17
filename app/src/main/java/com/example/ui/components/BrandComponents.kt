package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun NewEraLogoBadge(
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_new_era_crest),
            contentDescription = "New Era calligraphic S logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        )
    }
}

@Composable
fun OwnerSanskarTag(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Text(
            text = stringResource(id = R.string.owner_badge_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun E2eeShieldBadge(
    fingerprint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .testTag("e2ee_shield_button"),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "End-to-end encrypted vault",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (fingerprint.isNotBlank()) "E2EE ${fingerprint.take(4)}" else "E2EE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun AlexaVoiceOrb(
    isListening: Boolean,
    isSpeaking: Boolean,
    isThinking: Boolean,
    onClick: () -> Unit,
    orbSize: Dp = 64.dp,
    rmsLevel: Float = 0f,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1f + (rmsLevel.coerceIn(0f, 1f) * 0.16f) else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "voice_scale"
    )
    val transition = rememberInfiniteTransition(label = "orb_swirl")
    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isListening || isThinking) 4200 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_angle"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (isListening || isSpeaking) Color.White else MaterialTheme.colorScheme.onPrimaryContainer

    Box(
        modifier = modifier
            .size(orbSize + 14.dp)
            .minimumInteractiveComponentSize()
            .scale(scale)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("alexa_voice_orb"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(orbSize + 14.dp)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Outer ambient nebula glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (isListening || isSpeaking) 0.55f else 0.30f),
                        secondaryColor.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )

            // Inner glowing plasma sphere
            val coreRadius = radius * 0.74f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tertiaryColor.copy(alpha = 0.85f),
                        primaryColor.copy(alpha = 0.90f),
                        secondaryColor.copy(alpha = 0.85f),
                        surfaceColor
                    ),
                    center = Offset(center.x - coreRadius * 0.25f, center.y - coreRadius * 0.25f),
                    radius = coreRadius * 1.25f
                ),
                radius = coreRadius,
                center = center
            )

            // Rotating luminous wave rings inspired by futuristic voice orb
            rotate(degrees = orbitAngle, pivot = center) {
                drawArc(
                    color = Color.White.copy(alpha = if (isListening || isSpeaking) 0.65f else 0.28f),
                    startAngle = 15f,
                    sweepAngle = 135f,
                    useCenter = false,
                    topLeft = Offset(center.x - coreRadius * 0.94f, center.y - coreRadius * 0.94f),
                    size = androidx.compose.ui.geometry.Size(coreRadius * 1.88f, coreRadius * 1.88f),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawArc(
                    color = tertiaryColor.copy(alpha = 0.55f),
                    startAngle = 195f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - coreRadius * 0.84f, center.y - coreRadius * 0.84f),
                    size = androidx.compose.ui.geometry.Size(coreRadius * 1.68f, coreRadius * 1.68f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        Icon(
            imageVector = when {
                isListening -> Icons.Filled.Stop
                isSpeaking -> Icons.Filled.GraphicEq
                else -> Icons.Filled.Mic
            },
            contentDescription = stringResource(id = R.string.voice_orb_cd),
            tint = iconTint,
            modifier = Modifier.size(if (orbSize >= 96.dp) 34.dp else 24.dp)
        )
    }
}
