package com.flow.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.flow.launcher.AppInfo
import com.flow.launcher.HomeItem
import com.flow.launcher.IconShape
import com.flow.launcher.WidgetController

val LocalIconShape = staticCompositionLocalOf<Shape> { RoundedCornerShape(30) }
val LocalBadges = compositionLocalOf<Map<String, Int>> { emptyMap() }
val LocalWidgets = staticCompositionLocalOf<WidgetController?> { null }

/** Remembers where the long-pressed item is, so the menu can appear next to it. */
class MenuAnchor { var rect: Rect = Rect.Zero }
val LocalMenuAnchor = staticCompositionLocalOf { MenuAnchor() }

fun IconShape.toShape(): Shape = when (this) {
    IconShape.SQUIRCLE -> RoundedCornerShape(30)
    IconShape.CIRCLE -> CircleShape
    IconShape.SQUARE -> RoundedCornerShape(12)
    IconShape.TEARDROP -> RoundedCornerShape(50, 50, 50, 12)
}

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

@Composable
private fun bounceScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.88f, stiffness = 850f),
        label = "pressScale",
    )
    return scale
}

@Composable
fun NotifDot(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(CircleShape)
            .background(Color(0xFFFF4D4F))
            .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    val count = LocalBadges.current[app.packageName] ?: 0
    val anchor = LocalMenuAnchor.current
    val bounds = remember { arrayOf(Rect.Zero) }
    Column(
        modifier
            .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick?.let { lc -> { anchor.rect = bounds[0]; lc() } },
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Image(app.icon, app.label, Modifier.size(iconSize).clip(LocalIconShape.current))
            if (count > 0) NotifDot(count, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
        }
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
    val badges = LocalBadges.current
    val count = folder.apps.sumOf { key -> apps[key]?.let { badges[it.packageName] } ?: 0 }
    val mini = (iconSize * 0.74f - 4.dp) / 2
    val shape = LocalIconShape.current
    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier.size(iconSize)
                    .glass(RoundedCornerShape(iconSize * 0.3f), 0.24f)
                    .padding(iconSize * 0.13f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    folder.apps.take(4).mapNotNull { apps[it] }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { Image(it.icon, null, Modifier.size(mini).clip(shape)) }
                        }
                    }
                }
            }
            if (count > 0) NotifDot(count, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
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
        Text(text, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
