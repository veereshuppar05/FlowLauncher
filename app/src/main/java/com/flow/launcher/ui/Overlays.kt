package com.flow.launcher.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.flow.launcher.*
import kotlin.math.roundToInt

@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != null) last = value }
    return value ?: last
}

@Composable
internal fun noRipple() = remember { MutableInteractionSource() }

// ---------------- Folder ----------------
@Composable
fun FolderOverlay(vm: LauncherViewModel) {
    val folder = vm.openFolder
    val shown = rememberLast(folder)
    var renaming by remember { mutableStateOf(false) }

    AnimatedVisibility(
        folder != null,
        enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.6f),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.75f),
    ) {
        val f = shown ?: return@AnimatedVisibility
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = noRipple(), indication = null) { vm.openFolderId = null },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    f.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(interactionSource = noRipple(), indication = null) { renaming = true }
                        .padding(bottom = 18.dp),
                )
                Box(
                    Modifier.width(310.dp).glass(RoundedCornerShape(34.dp), 0.18f)
                        .clickable(interactionSource = noRipple(), indication = null) {}
                        .padding(18.dp)
                ) {
                    LazyVerticalGrid(
                        GridCells.Fixed(3),
                        Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(f.apps.mapNotNull { vm.appMap[it] }, key = { it.key }) { app ->
                            AppIcon(app, 56.dp, true,
                                onLongClick = { vm.menu = MenuTarget.FolderApp(f.id, app) }) { vm.launch(app) }
                        }
                    }
                }
            }
        }
        if (renaming) {
            RenameDialog(f.name, onDismiss = { renaming = false }) {
                vm.renameFolder(f.id, it)
                renaming = false
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder name") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton({ onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Long-press menu (appears next to the icon) ----------------
@Composable
fun ContextMenuOverlay(vm: LauncherViewModel) {
    val target = rememberLast(vm.menu)
    val ctx = LocalContext.current
    val widgets = LocalWidgets.current
    val anchorHolder = LocalMenuAnchor.current
    val density = LocalDensity.current

    AnimatedVisibility(
        vm.menu != null,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(100)),
    ) {
        val t = target ?: return@AnimatedVisibility
        val anchor = anchorHolder.rect
        val app: AppInfo? = when (t) {
            is MenuTarget.HomeApp -> t.app
            is MenuTarget.DockApp -> t.app
            is MenuTarget.DrawerApp -> t.app
            is MenuTarget.FolderApp -> t.app
            is MenuTarget.PrivateApp -> t.app
            is MenuTarget.HomeFolder -> null
            is MenuTarget.HomeWidget -> null
        }
        val shortcuts = remember(app) { app?.let { vm.repo.shortcuts(it) }.orEmpty() }
        val widgetLabel = remember(t) {
            (t as? MenuTarget.HomeWidget)?.let {
                widgets?.manager?.getAppWidgetInfo(it.widget.appWidgetId)?.loadLabel(ctx.packageManager)
            }
        }
        val title = when (t) {
            is MenuTarget.HomeFolder -> t.folder.name
            is MenuTarget.HomeWidget -> "${widgetLabel ?: "Widget"} · ${t.widget.spanX}×${t.widget.spanY}"
            else -> app?.label ?: ""
        }

        val marginPx = with(density) { 12.dp.toPx() }
        val gapPx = with(density) { 8.dp.toPx() }
        val widthPx = with(density) { 230.dp.roundToPx() }
        val topSafe = WindowInsets.statusBars.getTop(density) + marginPx
        val bottomSafe = WindowInsets.navigationBars.getBottom(density) + marginPx

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = noRipple(), indication = null) { vm.menu = null }
        ) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Column(
                        Modifier
                            .animateEnterExit(
                                enter = scaleIn(tween(160), initialScale = 0.85f),
                                exit = scaleOut(tween(100), targetScale = 0.92f),
                            )
                            .glass(RoundedCornerShape(20.dp), 0.26f)
                            .clickable(interactionSource = noRipple(), indication = null) {}
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp)
                    ) {
                        // Header
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (app != null) {
                                Image(app.icon, null, Modifier.size(28.dp).clip(LocalIconShape.current))
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MenuDivider()

                        // App shortcuts
                        shortcuts.forEach { sc ->
                            key(sc.id) {
                                val icon = remember(sc.id) {
                                    runCatching {
                                        vm.repo.launcherApps
                                            .getShortcutIconDrawable(sc, ctx.resources.displayMetrics.densityDpi)
                                            ?.toBitmap(96, 96)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                MenuRow(sc.shortLabel?.toString() ?: "", icon) { vm.launchShortcut(sc) }
                            }
                        }
                        if (shortcuts.isNotEmpty()) MenuDivider()

                        // Actions
                        when (t) {
                            is MenuTarget.HomeApp -> {
                                MenuRow("Add to dock") { vm.addToDock(t.page, t.index, t.app.key) }
                                MenuRow("Move to next page") { vm.moveToNextPage(t.page, t.index) }
                                if (vm.settings.drawerMode) {
                                    MenuRow("Remove from home") { vm.removeFromHome(t.page, t.index) }
                                }
                                MenuRow("Move to private space") { vm.hide(t.app.key) }
                                MenuRow("App info") { vm.appInfo(t.app) }
                                MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                            }
                            is MenuTarget.HomeFolder -> {
                                MenuRow("Open and rename") { vm.menu = null; vm.openFolderId = t.folder.id }
                                MenuRow("Move to next page") { vm.moveToNextPage(t.page, t.index) }
                                MenuRow("Ungroup folder") { vm.ungroup(t.page, t.index) }
                                if (vm.settings.drawerMode) {
                                    MenuRow("Remove from home") { vm.removeFromHome(t.page, t.index) }
                                }
                            }
                            is MenuTarget.DockApp -> {
                                MenuRow("Move to home screen") { vm.removeFromDock(t.index) }
                                MenuRow("App info") { vm.appInfo(t.app) }
                                MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                            }
                            is MenuTarget.DrawerApp -> {
                                if (vm.settings.drawerMode) {
                                    MenuRow("Add to home screen") { vm.addToHome(t.app.key) }
                                }
                                MenuRow("Move to private space") { vm.hide(t.app.key) }
                                MenuRow("App info") { vm.appInfo(t.app) }
                                MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                            }
                            is MenuTarget.FolderApp -> {
                                MenuRow("Remove from folder") { vm.removeFromFolder(t.folderId, t.app.key) }
                                MenuRow("App info") { vm.appInfo(t.app) }
                                MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                            }
                            is MenuTarget.PrivateApp -> {
                                MenuRow("Move out of private space") { vm.unhide(t.app.key); vm.menu = null }
                                MenuRow("App info") { vm.appInfo(t.app) }
                                MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                            }
                            is MenuTarget.HomeWidget -> {
                                val id = t.widget.appWidgetId
                                MenuRow("Make wider") { vm.resizeWidget(id, 1, 0) }
                                MenuRow("Make narrower") { vm.resizeWidget(id, -1, 0) }
                                MenuRow("Make taller") { vm.resizeWidget(id, 0, 1) }
                                MenuRow("Make shorter") { vm.resizeWidget(id, 0, -1) }
                                MenuRow("Move to next page") { vm.moveWidgetToNextPage(id) }
                                MenuRow("Remove widget", danger = true) {
                                    vm.removeWidget(id)
                                    widgets?.delete(id)
                                }
                            }
                        }
                    }
                },
            ) { measurables, constraints ->
                val maxH = (constraints.maxHeight - topSafe - bottomSafe).toInt().coerceAtLeast(0)
                val card = measurables.first().measure(
                    Constraints(minWidth = widthPx, maxWidth = widthPx, maxHeight = maxH)
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val screenW = constraints.maxWidth.toFloat()
                    val screenH = constraints.maxHeight.toFloat()
                    val x: Float
                    val y: Float
                    if (anchor == Rect.Zero) {
                        // Unknown position: fall back to bottom centre
                        x = (screenW - card.width) / 2f
                        y = screenH - bottomSafe - card.height
                    } else {
                        x = (anchor.center.x - card.width / 2f)
                            .coerceIn(marginPx, screenW - card.width - marginPx)
                        val above = anchor.top - gapPx - card.height
                        val below = anchor.bottom + gapPx
                        y = when {
                            above >= topSafe -> above                                   // prefer above the icon
                            below + card.height <= screenH - bottomSafe -> below        // otherwise below
                            else -> (screenH - bottomSafe - card.height).coerceAtLeast(topSafe)
                        }
                    }
                    card.place(x.roundToInt(), y.roundToInt())
                }
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).height(1.dp)
            .background(Color.White.copy(alpha = 0.15f))
    )
}

@Composable
private fun MenuRow(text: String, icon: ImageBitmap? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text, color = if (danger) Color(0xFFFF7A7A) else Color.White, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}