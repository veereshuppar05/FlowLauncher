package com.flow.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.launcher.AppInfo
import com.flow.launcher.HomeItem

/** Frosted panel with a soft top-lit edge ("contour glow"). */
fun Modifier.glass(shape: Shape, tint: Float = 0.14f): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = tint + 0.08f), Color.White.copy(alpha = tint))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f))), shape)

val LabelStyle = TextStyle(
    color = Color.White,
    fontSize = 11.5.sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 6f),
)

/** Springy squish on press. */
@Composable
private fun bounceScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.84f else 1f,
        spring(dampingRatio = 0.4f, stiffness = 420f),
        label = "bounce",
    )
    return scale
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(
    app: AppInfo,
    iconSize: Dp,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = bounceScale(interaction)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(app.icon, app.label, Modifier.size(iconSize).clip(RoundedCornerShape(iconSize * 0.3f)))
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                app.label, style = LabelStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun FolderIcon(
    folder: HomeItem.Folder,
    apps: Map<String, AppInfo>,
    iconSize: Dp,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = bounceScale(interaction)
    val mini = (iconSize * 0.74f - 4.dp) / 2
    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(iconSize)
                .glass(RoundedCornerShape(iconSize * 0.3f), 0.24f)
                .padding(iconSize * 0.13f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                folder.apps.take(4).mapNotNull { apps[it] }.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach {
                            Image(it.icon, null, Modifier.size(mini).clip(RoundedCornerShape(mini * 0.3f)))
                        }
                    }
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(folder.name, style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .glass(RoundedCornerShape(50), if (selected) 0.40f else 0.14f)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}
