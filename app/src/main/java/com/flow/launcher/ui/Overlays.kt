package com.flow.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
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

// ======================= shared helpers =======================
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != null) last = value }
    return value ?: last
}

@Composable
internal fun noRipple() = remember { MutableInteractionSource() }

// ======================= Folder =======================
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

// ======================= Menu colours =======================
private data class MenuColors(
    val bg: Color,
    val chip: Color,
    val text: Color,
    val sub: Color,
    val danger: Color,
)

private val LightMenu = MenuColors(
    bg = Color(0xFFF3F2EE),
    chip = Color(0xFFE4E3DE),
    text = Color(0xFF1C1C1E),
    sub = Color(0xFF1C1C1E).copy(alpha = 0.55f),
    danger = Color(0xFFD93025),
)

private val DarkMenu = MenuColors(
    bg = Color(0xFF2C2C30),
    chip = Color(0xFF3C3C42),
    text = Color(0xFFF2F2F2),
    sub = Color(0xFFF2F2F2).copy(alpha = 0.6f),
    danger = Color(0xFFFF6B6B),
)

// ======================= Menu icons (Material-style outlines) =======================
private object MenuIcons {
    private fun icon(d: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = runCatching { addPathNodes(d) }.getOrDefault(emptyList()),
        fill = SolidColor(Color.Black),
    ).build()

    val Timer by lazy { icon("M6,2v6h0.01L6,8.01 10,12l-4,4 0.01,0.01H6V22h12v-5.99h-0.01L18,16l-4,-4 4,-3.99 -0.01,-0.01H18V2H6zM16,16.5V20H8v-3.5l4,-4 4,4zM12,11.5l-4,-4V4h8v3.5l-4,4z") }
    val Info by lazy { icon("M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z") }
    val Lock by lazy { icon("M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM9,6c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v2H9V6zM18,20H6V10h12v10zM12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2z") }
    val LockOpen by lazy { icon("M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h2c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM18,20H6V10h12v10z") }
    val Delete by lazy { icon("M16,9v10H8V9h8m-1.5,-6h-5l-1,1H5v2h14V4h-3.5l-1,-1zM18,7H6v12c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7z") }
    val AddBox by lazy { icon("M19,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM17,13h-4v4h-2v-4H7v-2h4V7h2v4h4v2z") }
    val Next by lazy { icon("M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z") }
    val Close by lazy { icon("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z") }
    val Edit by lazy { icon("M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z") }
    val Apps by lazy { icon("M4,8h4V4H4v4zM10,20h4v-4h-4v4zM4,20h4v-4H4v4zM4,14h4v-4H4v4zM10,14h4v-4h-4v4zM16,4v4h4V4h-4zM10,8h4V4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z") }
    val Grow by lazy { icon("M21,11V3h-8l3.29,3.29 -10,10L3,13v8h8l-3.29,-3.29 10,-10z") }
    val Shrink by lazy { icon("M22,3.41l-5.29,5.29L20,12h-8V4l3.29,3.29L20.59,2 22,3.41zM3.41,22l5.29,-5.29L12,20v-8H4l3.29,3.29L2,20.59 3.41,22z") }
}

private class MenuAction(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

private fun openAppTimer(ctx: Context, vm: LauncherViewModel, app: AppInfo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_USAGE_SETTINGS)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, app.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (ok) {
            vm.menu = null
            return
        }
    }
    vm.appInfo(app)
}

/** Returns (icon buttons for the top bar, rows below). */
private fun buildActions(
    t: MenuTarget,
    vm: LauncherViewModel,
    ctx: Context,
    widgets: WidgetController?,
): Pair<List<MenuAction>, List<MenuAction>> {
    val drawerMode = vm.settings.drawerMode
    fun timer(a: AppInfo) = MenuAction(MenuIcons.Timer, "App timer") { openAppTimer(ctx, vm, a) }
    fun info(a: AppInfo) = MenuAction(MenuIcons.Info, "App info") { vm.appInfo(a) }
    fun lock(a: AppInfo) = MenuAction(MenuIcons.Lock, "Move to private space") { vm.hide(a.key) }
    fun uninstall(a: AppInfo) = MenuAction(MenuIcons.Delete, "Uninstall", danger = true) { vm.uninstall(a) }

    return when (t) {
        is MenuTarget.HomeApp -> listOf(timer(t.app), info(t.app), lock(t.app), uninstall(t.app)) to buildList {
            add(MenuAction(MenuIcons.AddBox, "Add to dock") { vm.addToDock(t.page, t.index, t.app.key) })
            add(MenuAction(MenuIcons.Next, "Move to next page") { vm.moveToNextPage(t.page, t.index) })
            if (drawerMode) add(MenuAction(MenuIcons.Close, "Remove from home") { vm.removeFromHome(t.page, t.index) })
        }
        is MenuTarget.DockApp -> listOf(timer(t.app), info(t.app), uninstall(t.app)) to listOf(
            MenuAction(MenuIcons.Next, "Move to home screen") { vm.removeFromDock(t.index) },
        )
        is MenuTarget.DrawerApp -> listOf(timer(t.app), info(t.app), lock(t.app), uninstall(t.app)) to buildList {
            if (drawerMode) add(MenuAction(MenuIcons.AddBox, "Add to home screen") { vm.addToHome(t.app.key) })
        }
        is MenuTarget.FolderApp -> listOf(timer(t.app), info(t.app), uninstall(t.app)) to listOf(
            MenuAction(MenuIcons.Close, "Remove from folder") { vm.removeFromFolder(t.folderId, t.app.key) },
        )
        is MenuTarget.PrivateApp -> listOf(
            info(t.app),
            MenuAction(MenuIcons.LockOpen, "Move out of private space") { vm.unhide(t.app.key); vm.menu = null },
            uninstall(t.app),
        ) to emptyList()
        is MenuTarget.HomeFolder -> buildList {
            add(MenuAction(MenuIcons.Edit, "Rename") { vm.menu = null; vm.openFolderId = t.folder.id })
            add(MenuAction(MenuIcons.Apps, "Ungroup") { vm.ungroup(t.page, t.index) })
            if (drawerMode) add(MenuAction(MenuIcons.Close, "Remove") { vm.removeFromHome(t.page, t.index) })
        } to listOf(
            MenuAction(MenuIcons.Next, "Move to next page") { vm.moveToNextPage(t.page, t.index) },
        )
        is MenuTarget.HomeWidget -> {
            val id = t.widget.appWidgetId
            listOf(
                MenuAction(MenuIcons.Delete, "Remove widget", danger = true) {
                    vm.removeWidget(id)
                    widgets?.delete(id)
                },
            ) to listOf(
                MenuAction(MenuIcons.Grow, "Make wider") { vm.resizeWidget(id, 1, 0) },
                MenuAction(MenuIcons.Shrink, "Make narrower") { vm.resizeWidget(id, -1, 0) },
                MenuAction(MenuIcons.Grow, "Make taller") { vm.resizeWidget(id, 0, 1) },
                MenuAction(MenuIcons.Shrink, "Make shorter") { vm.resizeWidget(id, 0, -1) },
                MenuAction(MenuIcons.Next, "Move to next page") { vm.moveWidgetToNextPage(id) },
            )
        }
    }
}

// ======================= Long-press menu =======================
@Composable
fun ContextMenuOverlay(vm: LauncherViewModel) {
    val target = rememberLast(vm.menu)
    val ctx = LocalContext.current
    val widgets = LocalWidgets.current
    val anchorHolder = LocalMenuAnchor.current
    val density = LocalDensity.current
    val c = if (isSystemInDarkTheme()) DarkMenu else LightMenu

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
        val canShortcuts = remember { vm.repo.canShowShortcuts() }
        val (topActions, rowActions) = buildActions(t, vm, ctx, widgets)

        val padPx = with(density) { 6.dp.toPx() }          // room for pill shadows
        val marginPx = with(density) { 10.dp.toPx() }
        val gapPx = with(density) { 2.dp.toPx() }
        val radiusPx = with(density) { 24.dp.toPx() }
        val widthPx = with(density) { 236.dp.roundToPx() }
        val topSafe = WindowInsets.statusBars.getTop(density) + marginPx
        val bottomSafe = WindowInsets.navigationBars.getBottom(density) + marginPx

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.15f))
                .clickable(interactionSource = noRipple(), indication = null) { vm.menu = null }
        ) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    // ---- the stacked pills ----
                    Column(
                        Modifier
                            .animateEnterExit(
                                enter = scaleIn(tween(160), initialScale = 0.85f),
                                exit = scaleOut(tween(100), targetScale = 0.92f),
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (topActions.isNotEmpty()) TopBar(topActions, c)

                        if (app != null && !canShortcuts) {
                            Text(
                                "Set Flow Launcher as default home app to see app shortcuts",
                                color = c.sub, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .shadow(6.dp, RoundedCornerShape(20.dp))
                                    .background(c.bg, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }

                        shortcuts.forEach { sc ->
                            key(sc.id) {
                                val icon = remember(sc.id) {
                                    runCatching {
                                        vm.repo.launcherApps
                                            .getShortcutIconDrawable(sc, ctx.resources.displayMetrics.densityDpi)
                                            ?.toBitmap(96, 96)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                val label = (sc.shortLabel ?: sc.longLabel)?.toString() ?: ""
                                ItemPill(c, label, bitmap = icon ?: app?.icon) { vm.launchShortcut(sc) }
                            }
                        }

                        rowActions.forEach { a ->
                            ItemPill(c, a.label, vector = a.icon, danger = a.danger, onClick = a.onClick)
                        }
                    }
                    // ---- pointer tails (only one gets placed) ----
                    Tail(c.bg, pointsDown = true)
                    Tail(c.bg, pointsDown = false)
                },
            ) { measurables, constraints ->
                val maxH = (constraints.maxHeight - topSafe - bottomSafe + 2 * padPx).toInt().coerceAtLeast(0)
                val card = measurables[0].measure(Constraints(minWidth = widthPx, maxWidth = widthPx, maxHeight = maxH))
                val tailDown = measurables[1].measure(Constraints())
                val tailUp = measurables[2].measure(Constraints())

                layout(constraints.maxWidth, constraints.maxHeight) {
                    val screenW = constraints.maxWidth.toFloat()
                    val screenH = constraints.maxHeight.toFloat()

                    if (anchor == Rect.Zero) {
                        card.place(
                            ((screenW - card.width) / 2f).roundToInt(),
                            (screenH - bottomSafe - card.height + padPx).roundToInt(),
                        )
                        return@layout
                    }

                    val x = (anchor.center.x - card.width / 2f)
                        .coerceIn(marginPx - padPx, screenW - card.width - marginPx + padPx)
                    val aboveY = anchor.top - gapPx - tailDown.height - card.height + padPx
                    val belowY = anchor.bottom + gapPx + tailUp.height - padPx
                    val tailX = (anchor.center.x - tailDown.width / 2f)
                        .coerceIn(x + padPx + radiusPx, x + card.width - padPx - radiusPx - tailDown.width)

                    when {
                        aboveY + padPx >= topSafe -> {
                            card.place(x.roundToInt(), aboveY.roundToInt())
                            tailDown.place(tailX.roundToInt(), (aboveY + card.height - padPx - 1).roundToInt())
                        }
                        belowY + card.height - padPx <= screenH - bottomSafe -> {
                            card.place(x.roundToInt(), belowY.roundToInt())
                            tailUp.place(tailX.roundToInt(), (belowY + padPx - tailUp.height + 1).roundToInt())
                        }
                        else -> {
                            val y = (screenH - bottomSafe - card.height + padPx).coerceAtLeast(topSafe - padPx)
                            card.place(x.roundToInt(), y.roundToInt())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(actions: List<MenuAction>, c: MenuColors) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().height(54.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(c.bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { a ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = a.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(a.icon, a.label, Modifier.size(22.dp), tint = if (a.danger) c.danger else c.text)
            }
        }
    }
}

@Composable
private fun ItemPill(
    c: MenuColors,
    label: String,
    bitmap: ImageBitmap? = null,
    vector: ImageVector? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth()
            .shadow(6.dp, shape)
            .clip(shape)
            .background(c.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.chip),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
            } else if (vector != null) {
                Icon(vector, null, Modifier.size(19.dp), tint = if (danger) c.danger else c.text)
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label, color = if (danger) c.danger else c.text, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Tail(color: Color, pointsDown: Boolean) {
    Canvas(Modifier.size(20.dp, 10.dp)) {
        val p = Path().apply {
            if (pointsDown) {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height)
            } else {
                moveTo(0f, size.height); lineTo(size.width, size.height); lineTo(size.width / 2f, 0f)
            }
            close()
        }
        drawPath(p, color)
    }
}