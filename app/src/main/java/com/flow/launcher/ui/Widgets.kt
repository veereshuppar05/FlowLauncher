package com.flow.launcher.ui

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.flow.launcher.LauncherViewModel
import com.flow.launcher.LauncherWidgetHostView
import com.flow.launcher.WidgetController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WidgetHostCompose(controller: WidgetController, appWidgetId: Int, onLongPress: () -> Unit) {
    val longPress by rememberUpdatedState(onLongPress)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wDp = maxWidth.value.toInt()
        val hDp = maxHeight.value.toInt()
        AndroidView(
            factory = { ctx ->
                controller.createView(ctx, appWidgetId) ?: TextView(ctx).apply {
                    text = "Widget unavailable\n(long-press to remove)"
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = Gravity.CENTER
                    setOnLongClickListener { longPress(); true }
                }
            },
            update = { v ->
                if (v is LauncherWidgetHostView) {
                    v.onLongPress = { longPress() }
                    v.updateSizeDp(wDp, hDp)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private class WidgetEntry(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
    val preview: ImageBitmap?,
    val spanX: Int,
    val spanY: Int,
)

private fun Drawable.toPreview(): ImageBitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: 300
    val h = intrinsicHeight.takeIf { it > 0 } ?: 300
    val scale = minOf(1f, 360f / maxOf(w, h))
    return toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
}

@Composable
fun WidgetPicker(vm: LauncherViewModel, controller: WidgetController) {
    val ctx = LocalContext.current
    val entries by produceState<List<WidgetEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val dpi = ctx.resources.displayMetrics.densityDpi
            controller.providers().map { info ->
                val (sx, sy) = controller.spans(info)
                val preview = runCatching {
                    (info.loadPreviewImage(ctx, dpi) ?: info.loadIcon(ctx, dpi))?.toPreview()
                }.getOrNull()
                val appLabel = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                }.getOrDefault(info.provider.packageName)
                WidgetEntry(info, info.loadLabel(pm), appLabel, preview, sx, sy)
            }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
        }
    }
    val groups = remember(entries) { entries?.groupBy { it.appLabel }?.toList() }

    Column(
        Modifier.fillMaxSize()
            .clickable(interactionSource = noRipple(), indication = null) {}
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Widgets", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            GlassPill("Close") { vm.widgetPickerOpen = false }
        }
        if (groups == null) {
            Text("Loading widgets…", color = Color.White.copy(alpha = 0.7f))
        } else if (groups.isEmpty()) {
            Text("No widgets found on this phone.", color = Color.White.copy(alpha = 0.7f))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                groups.forEach { (app, list) ->
                    item {
                        Text(app, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(list) { e -> WidgetCard(e) { controller.pick(e.info) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(e: WidgetEntry, onClick: () -> Unit) {
    Column(
        Modifier.width(170.dp).glass(RoundedCornerShape(22.dp), 0.16f)
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
            if (e.preview != null) {
                Image(e.preview, e.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(e.label, color = Color.White, fontSize = 13.sp, maxLines = 2)
        Text("${e.spanX} × ${e.spanY}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}
