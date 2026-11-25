package com.flow.launcher.ui

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.flow.launcher.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal val GridPad = 16.dp

/** One icon size for the whole launcher (home, dock, drawer, folders, private space, drag). */
@Composable
internal fun rememberIconSize(vm: LauncherViewModel): Dp {
    val density = LocalDensity.current
    val s = vm.settings
    val g = vm.gridSize
    if (g.width <= 0f || g.height <= 0f) return (56 * s.iconScale).dp
    val pad = with(density) { GridPad.toPx() }
    val cw = (g.width - 2 * pad) / s.cols
    val ch = g.height / s.rows
    return with(density) { (minOf(cw, ch) * 0.62f * s.iconScale).toDp() }.coerceAtMost(76.dp)
}

/** Drag facts that do NOT change while the finger moves (so the grid doesn't redraw every frame). */
private data class DragInfo(val page: Int, val index: Int, val group: List<HomeItem>, val menuOpen: Boolean)

private fun HomeItem.uid(): String = when (this) {
    is HomeItem.App -> "a:$key"
    is HomeItem.Folder -> "f:$id"
}

// ======================= Root =======================
@Composable
fun LauncherRoot(vm: LauncherViewModel, widgets: WidgetController, setWindowBlur: (Int) -> Unit) {
    val activity = LocalContext.current as Activity
    val badgeCounts by BadgeService.counts.collectAsState()
    val menuAnchor = remember { MenuAnchor() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(
            LocalIconShape provides vm.settings.iconShape.toShape(),
            LocalBadges provides (if (vm.settings.badges) badgeCounts else emptyMap()),
            LocalWidgets provides widgets,
            LocalMenuAnchor provides menuAnchor,
        ) {
            val folderDragOut by remember {
                derivedStateOf { vm.drag?.page == LauncherViewModel.FOLDER_PAGE && vm.menu == null }
            }
            val overlay = vm.drawerOpen || (vm.openFolderId != null && !folderDragOut) || vm.showSettings ||
                vm.widgetPickerOpen || vm.privateOpen
            val blur by animateFloatAsState(if (overlay) 1f else 0f, tween(300), label = "blur")
            LaunchedEffect(blur, vm.settings.blur) {
                setWindowBlur(if (vm.settings.blur) (blur * 80).toInt() else 0)
            }
            val ghostOn by remember { derivedStateOf { vm.drag != null && vm.menu == null } }

            Box(Modifier.fillMaxSize()) {
                HomeScreen(
                    vm,
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val s = 1f - 0.08f * blur
                            scaleX = s; scaleY = s; alpha = 1f - 0.5f * blur
                        }
                        .then(if (vm.settings.blur && blur > 0.01f) Modifier.blur((20 * blur).dp) else Modifier),
                ) { g -> performAction(vm.settings.gestures[g] ?: GestureAction.NONE, activity, vm) }

                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f * blur)))

                AnimatedVisibility(
                    vm.drawerOpen,
                    enter = fadeIn(tween(220)) + slideInVertically(tween(320)) { it / 4 },
                    exit = fadeOut(tween(180)) + slideOutVertically(tween(260)) { it / 4 },
                ) { AppDrawer(vm) }

                FolderOverlay(vm)

                AnimatedVisibility(
                    vm.privateOpen,
                    enter = fadeIn() + scaleIn(initialScale = 0.9f),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f),
                ) { PrivateSpaceOverlay(vm) }

                AnimatedVisibility(
                    vm.widgetPickerOpen,
                    enter = fadeIn() + slideInVertically { it / 5 },
                    exit = fadeOut() + slideOutVertically { it / 5 },
                ) { WidgetPicker(vm, widgets) }

                AnimatedVisibility(
                    vm.showSettings,
                    enter = fadeIn() + scaleIn(initialScale = 0.92f),
                    exit = fadeOut() + scaleOut(targetScale = 0.92f),
                ) { SettingsPanel(vm) }

                if (ghostOn) DragGhost(vm)

                ContextMenuOverlay(vm)
            }
        }
    }
}

// ======================= Gesture actions =======================
fun performAction(action: GestureAction, activity: Activity, vm: LauncherViewModel) {
    when (action) {
        GestureAction.NONE -> {}
        GestureAction.DRAWER -> vm.drawerOpen = true
        GestureAction.NOTIFICATIONS -> vm.expandNotifications()
        GestureAction.QUICK_SETTINGS -> vm.expandQuickSettings()
        GestureAction.LOCK -> LockService.perform(activity, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        GestureAction.RECENTS -> LockService.perform(activity, AccessibilityService.GLOBAL_ACTION_RECENTS)
        GestureAction.SCREENSHOT -> LockService.perform(activity, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        GestureAction.POWER_MENU -> LockService.perform(activity, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        GestureAction.PRIVATE_SPACE -> openPrivateSpace(activity, vm)
        GestureAction.EDIT_MODE -> vm.editMode = true
        GestureAction.SETTINGS -> vm.showSettings = true
        GestureAction.FIRST_PAGE -> vm.goToFirstPage()
    }
}

/** Swipes, two-finger swipes and pinches on the home screen. */
private fun Modifier.homeGestures(vm: LauncherViewModel, onGesture: (Gesture) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (vm.editMode || vm.drag != null) return@awaitEachGesture
        val edgeGuard = 14.dp.toPx()                       // curved-screen palm rejection
        if (down.position.x < edgeGuard || down.position.x > size.width - edgeGuard) return@awaitEachGesture
        val onWidget = vm.widgetBounds.values.any { it.contains(down.position) }
        var multi = false
        var pan = Offset.Zero
        var zoom = 1f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2 && !multi) {
                multi = true
                pan = Offset.Zero
                zoom = 1f
            }
            if (multi) {
                zoom *= event.calculateZoom()
                pan += event.calculatePan()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else {
                pan += event.calculatePan()
            }
            if (pressed == 0) break
        }
        if (vm.drag != null || vm.menu != null) return@awaitEachGesture
        val ax = abs(pan.x)
        val ay = abs(pan.y)
        val gesture = if (multi) {
            when {
                zoom < 0.72f -> Gesture.PINCH_IN
                zoom > 1.4f -> Gesture.PINCH_OUT
                ay > ax && pan.y > 180f -> Gesture.TWO_DOWN
                ay > ax && pan.y < -180f -> Gesture.TWO_UP
                else -> null
            }
        } else if (!onWidget && ay > 140f && ay > ax * 1.5f) {
            if (pan.y < 0) Gesture.SWIPE_UP else Gesture.SWIPE_DOWN
        } else null
        gesture?.let(onGesture)
    }
}

// ======================= Home =======================
@Composable
private fun HomeScreen(vm: LauncherViewModel, modifier: Modifier, onGesture: (Gesture) -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val padPx = with(density) { GridPad.toPx() }
    val edgePx = with(density) { 36.dp.toPx() }
    val pager = rememberPagerState { vm.layout.pages.size }
    // One shared clock drives all edit-mode icon/widget tilts instead of one per item.
    val editWiggle: State<Float> = if (vm.editMode) {
        val transition = rememberInfiniteTransition(label = "editWiggle")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 6.2831855f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "editWiggle",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(vm.homeTick) { if (vm.homeTick > 0) pager.animateScrollToPage(0) }
    LaunchedEffect(vm.scrollTo) {
        vm.scrollTo?.let {
            pager.animateScrollToPage(it)
            vm.scrollTo = null
        }
    }
    LaunchedEffect(pager.currentPage) { vm.currentPage = pager.currentPage }

    /** Cell under the dragged icon, and whether it's over an icon's centre (→ folder). */
    fun target(d: DragState): Pair<Int, Boolean>? {
        val s = vm.settings
        val cw = (gridBounds.width - 2 * padPx) / s.cols
        val ch = gridBounds.height / s.rows
        if (cw <= 0f || ch <= 0f) return null
        val center = d.pointer - d.grab + Offset(cw / 2, ch / 2)
        if (!gridBounds.contains(center)) return null
        val fx = (center.x - gridBounds.left - padPx) / cw
        val fy = (center.y - gridBounds.top) / ch
        val col = fx.toInt().coerceIn(0, s.cols - 1)
        val row = fy.toInt().coerceIn(0, s.rows - 1)
        val idx = row * s.cols + col
        val dst = vm.layout.pages.getOrNull(pager.currentPage)?.getOrNull(idx)
        val nearCentre = abs(fx - col - 0.5f) < 0.28f && abs(fy - row - 0.5f) < 0.28f
        val sameCell = pager.currentPage == d.page && idx == d.index
        val merge = nearCentre && !sameCell && d.group.isEmpty() && d.item is HomeItem.App && dst != null
        return idx to merge
    }

    /** Which gap in the dock the dragged app would land in. */
    fun dockSlot(d: DragState): Int? {
        if (dockBounds.width <= 0f || !dockBounds.contains(d.pointer)) return null
        val fromDock = d.page == LauncherViewModel.DOCK_PAGE
        if (!fromDock && (d.item !is HomeItem.App || d.group.isNotEmpty() || vm.layout.dock.size >= 5)) return null
        val m = vm.layout.dock.size - if (fromDock) 1 else 0
        val frac = (d.pointer.x - dockBounds.left) / dockBounds.width
        return (frac * (m + 1)).toInt().coerceIn(0, m)
    }

    // These only change when the result changes – not on every finger movement
    val dragInfo by remember {
        derivedStateOf { vm.drag?.let { DragInfo(it.page, it.index, it.group, vm.menu != null) } }
    }
    val hover by remember {
        derivedStateOf { vm.drag?.takeIf { vm.menu == null }?.let { target(it) } }
    }
    val dockHover by remember {
        derivedStateOf { vm.drag?.takeIf { vm.menu == null }?.let { dockSlot(it) } }
    }
    val preview by remember {
        derivedStateOf {
            val d = vm.drag
            val h = hover
            if (d == null || h == null || h.second || d.group.isNotEmpty() || vm.menu != null) emptyMap()
            else vm.shiftPreview(pager.currentPage, h.first)
        }
    }

    // Hold a dragged icon at the screen edge to flip pages
    val dragging = dragInfo != null
    LaunchedEffect(dragging) {
        while (vm.drag != null) {
            val d = vm.drag ?: break
            val moved = (d.pointer - d.start).getDistance() > 40f
            val x = d.pointer.x
            val dir = when {
                !moved -> 0
                x < gridBounds.left + edgePx -> -1
                x > gridBounds.right - edgePx -> 1
                else -> 0
            }
            if (dir == 0) {
                delay(60)
                continue
            }
            delay(500)
            val x2 = vm.drag?.pointer?.x ?: break
            val still = if (dir < 0) x2 < gridBounds.left + edgePx else x2 > gridBounds.right - edgePx
            if (!still) continue
            val targetPage = pager.currentPage + dir
            if (targetPage == vm.layout.pages.size && vm.layout.pages.lastOrNull()?.any { it != null } == true) {
                vm.addEmptyPage()
            }
            if (targetPage in 0 until vm.layout.pages.size) {
                pager.animateScrollToPage(targetPage)
                delay(400)
            }
        }
    }

    val drop: () -> Unit = {
        val d = vm.drag
        if (d != null) {
            if (dockBounds.contains(d.pointer)) {
                vm.endDrag(null, null, toDock = true, dockIndex = dockSlot(d))
            } else {
                val tg = target(d)
                if (tg != null) vm.endDrag(pager.currentPage, tg.first, toDock = false, merge = tg.second)
                else vm.endDrag(null, null, toDock = false)
            }
        }
    }
    SideEffect { vm.dropHandler = drop }

    Column(
        modifier
            .homeGestures(vm, onGesture)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onGesture(Gesture.DOUBLE_TAP) },
                    onLongPress = {
                        scope.launch {
                            delay(60)
                            if (vm.drag == null && vm.menu == null) vm.editMode = true
                        }
                    },
                    onTap = { if (vm.editMode) vm.exitEditMode() },
                )
            }
            .systemBarsPadding(),
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { c ->
                gridBounds = c.boundsInRoot()
                vm.gridSize = Size(c.size.width.toFloat(), c.size.height.toFloat())
            }
        ) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                userScrollEnabled = dragInfo == null,
                // Keep only the current page and its immediate neighbors composed.
                // Each page contains multiple icon animations and may host Android widgets.
                beyondViewportPageCount = 1,
            ) { page ->
                PageGrid(
                    vm = vm,
                    page = page,
                    origin = { Offset(gridBounds.left + padPx, gridBounds.top) },
                    onDrop = drop,
                    dragInfo = dragInfo,
                    hover = hover,
                    preview = if (page == pager.currentPage) preview else emptyMap(),
                    hoverPage = pager.currentPage,
                    editWiggle = editWiggle,
                )
            }
        }
        PageDots(vm.layout.pages.size, pager.currentPage)
        Dock(vm, dragInfo, dockHover, drop) { dockBounds = it }
        AnimatedVisibility(vm.editMode) {
            if (vm.selected.isEmpty()) EditBar(vm) else SelectionBar(vm)
        }
    }
}

private fun Modifier.wiggle(seed: Int, phase: State<Float>): Modifier =
    graphicsLayer {
        // Read the shared animation state in the layer block so icon composables don't recompose each frame.
        rotationZ = sin(phase.value + seed * 0.73f) * 1.2f
    }

@Composable
private fun PageGrid(
    vm: LauncherViewModel,
    page: Int,
    origin: () -> Offset,
    onDrop: () -> Unit,
    dragInfo: DragInfo?,
    hover: Pair<Int, Boolean>?,
    preview: Map<Int, Int>,
    hoverPage: Int,
    editWiggle: State<Float>,
) {
    val s = vm.settings
    val slots = vm.layout.pages.getOrNull(page) ?: return
    val haptic = LocalHapticFeedback.current
    val widgets = LocalWidgets.current
    val anchor = LocalMenuAnchor.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = GridPad)) {
        val cellW = maxWidth / s.cols
        val cellH = maxHeight / s.rows
        val density = LocalDensity.current
        val cw = with(density) { cellW.toPx() }
        val ch = with(density) { cellH.toPx() }
        val iconSize = rememberIconSize(vm)

        if (page == 0 && vm.layout.clock.visible) FlowClock(vm, maxWidth, maxHeight)

        // ---- widgets ----
        vm.layout.widgets.filter { it.page == page }.forEach { w ->
            key("w:${w.appWidgetId}") {
                Box(
                    Modifier
                        .offset { IntOffset((w.col * cw).roundToInt(), (w.row * ch).roundToInt()) }
                        .size(cellW * w.spanX, cellH * w.spanY)
                        .padding(6.dp)
                        .onGloballyPositioned { vm.widgetBounds[w.appWidgetId] = it.boundsInRoot() }
                        .then(if (vm.editMode) Modifier.wiggle(w.appWidgetId, editWiggle) else Modifier),
                ) {
                    if (widgets != null) {
                        WidgetHostCompose(widgets, w.appWidgetId) {
                            anchor.rect = vm.widgetBounds[w.appWidgetId] ?: Rect.Zero
                            vm.menu = MenuTarget.HomeWidget(w)
                        }
                    }
                }
            }
        }

        // ---- icons ----
        slots.forEachIndexed { index, item ->
            if (item == null) return@forEachIndexed
            key(item.uid()) {
                val col = index % s.cols
                val row = index / s.cols
                val isDragged = dragInfo != null && !dragInfo.menuOpen &&
                    ((dragInfo.page == page && dragInfo.index == index) || dragInfo.group.contains(item))

                // Where this icon should be right now (live "move aside" preview)
                val shown = preview[index] ?: index
                val leaving = shown < 0
                val goal = if (leaving) {
                    IntOffset((col * cw).roundToInt(), (row * ch).roundToInt())
                } else {
                    IntOffset(((shown % s.cols) * cw).roundToInt(), ((shown / s.cols) * ch).roundToInt())
                }
                val pos = remember { Animatable(goal, IntOffset.VectorConverter) }
                val wasDragged = remember { booleanArrayOf(false) }
                LaunchedEffect(goal, isDragged) {
                    when {
                        isDragged -> wasDragged[0] = true
                        wasDragged[0] -> {
                            wasDragged[0] = false
                            pos.snapTo(goal)
                        }
                        else -> pos.animateTo(goal, spring(dampingRatio = 0.82f, stiffness = 420f))
                    }
                }
                val fade by animateFloatAsState(if (leaving) 0f else 1f, tween(180), label = "fade")
                val folderHover = hover != null && hover.second && hoverPage == page && hover.first == index

                Box(
                    Modifier
                        .offset { pos.value }
                        .size(cellW, cellH)
                        .graphicsLayer {
                            val sc = 0.6f + 0.4f * fade
                            scaleX = sc; scaleY = sc
                            alpha = if (isDragged) 0f else fade
                        }
                        .then(if (vm.editMode && !isDragged) Modifier.wiggle(index, editWiggle) else Modifier)
                        .pointerInput(page, index, item, s.cols, s.rows) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { off ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.suppressClick = true
                                    val pointer = origin() + Offset(col * cw, row * ch) + off
                                    vm.startDrag(page, index, item, pointer, off)
                                    if (vm.drag?.group.isNullOrEmpty()) {
                                        anchor.rect = Rect(origin() + Offset(col * cw, row * ch), Size(cw, ch))
                                        vm.showMenuFor(page, index)
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    vm.dragBy(amount)
                                },
                                onDragEnd = {
                                    val d = vm.drag
                                    if (d != null && (d.pointer - d.start).getDistance() < 24f) vm.cancelDrag()
                                    else onDrop()
                                    vm.suppressClick = false
                                },
                                onDragCancel = {
                                    vm.cancelDrag()
                                    vm.suppressClick = false
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (folderHover) {
                        Box(Modifier.size(iconSize * 1.3f).glass(RoundedCornerShape(iconSize * 0.38f), 0.32f))
                    }
                    Box(Modifier.graphicsLayer {
                        val sc = if (folderHover) 1.08f else 1f
                        scaleX = sc; scaleY = sc
                    }) {
                        when (item) {
                            is HomeItem.App -> vm.appMap[item.key]?.let { app ->
                                AppIcon(app, iconSize, s.showLabels) {
                                    when {
                                        vm.suppressClick -> vm.suppressClick = false
                                        vm.editMode -> vm.toggleSelect(item)
                                        else -> vm.launch(app)
                                    }
                                }
                            }
                            is HomeItem.Folder -> FolderIcon(item, vm.appMap, iconSize, s.showLabels) {
                                when {
                                    vm.suppressClick -> vm.suppressClick = false
                                    vm.editMode -> vm.toggleSelect(item)
                                    else -> vm.openFolderId = item.id
                                }
                            }
                        }
                    }
                    if (vm.editMode && !isDragged) {
                        SelectBadge(
                            vm.isSelected(item),
                            Modifier.align(Alignment.TopCenter).offset(x = -(iconSize / 2), y = (-4).dp),
                        )
                    }
                }
            }
        }
    }
}

/** The icon that follows your finger. Only its position changes per frame. */
@Composable
private fun DragGhost(vm: LauncherViewModel) {
    val content by remember { derivedStateOf { vm.drag?.let { it.item to it.group } } }
    val (item, group) = content ?: return
    val density = LocalDensity.current
    val s = vm.settings
    val g = vm.gridSize
    if (g.width <= 0f) return
    val padPx = with(density) { GridPad.toPx() }
    val w = with(density) { ((g.width - 2 * padPx) / s.cols).toDp() }
    val h = with(density) { (g.height / s.rows).toDp() }
    val iconSize = rememberIconSize(vm)
    Box(
        Modifier
            .offset {
                vm.drag?.let {
                    IntOffset((it.pointer.x - it.grab.x).roundToInt(), (it.pointer.y - it.grab.y).roundToInt())
                } ?: IntOffset.Zero
            }
            .size(w, h)
            .zIndex(20f)
            .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.95f },
        contentAlignment = Alignment.Center,
    ) {
        if (group.size > 1) {
            Box {
                val stack = group.take(3)
                stack.reversed().forEachIndexed { i, item ->
                    val shift = ((stack.size - 1 - i) * 6).dp
                    Box(Modifier.offset(x = shift, y = shift)) { GhostItem(vm, item, iconSize, false) }
                }
                NotifDot(group.size, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
            }
        } else {
            GhostItem(vm, item, iconSize, s.showLabels)
        }
    }
}

@Composable
private fun GhostItem(vm: LauncherViewModel, item: HomeItem, iconSize: Dp, showLabel: Boolean) {
    when (item) {
        is HomeItem.App -> vm.appMap[item.key]?.let { AppIcon(it, iconSize, showLabel) {} }
        is HomeItem.Folder -> FolderIcon(item, vm.appMap, iconSize, showLabel) {}
    }
}

// ======================= Clock =======================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlowClock(vm: LauncherViewModel, areaW: Dp, areaH: Dp) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val c = vm.layout.clock
    val is24 = DateFormat.is24HourFormat(ctx)
    val hour = now.format(DateTimeFormatter.ofPattern(if (is24) "HH" else "hh"))
    val minute = now.format(DateTimeFormatter.ofPattern("mm"))
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMM"))

    val density = LocalDensity.current
    val wPx = with(density) { areaW.toPx() }
    val hPx = with(density) { areaH.toPx() }

    val transform = rememberTransformableState { zoom, pan, _ ->
        val cur = vm.layout.clock
        val k = cur.scale
        vm.setClock(
            cur.copy(
                x = (cur.x + pan.x * k / wPx).coerceIn(0f, 0.65f),
                y = (cur.y + pan.y * k / hPx).coerceIn(0f, 0.8f),
                scale = (cur.scale * zoom).coerceIn(0.6f, 1.8f),
            )
        )
    }
    LaunchedEffect(transform.isTransformInProgress) {
        if (!transform.isTransformInProgress) vm.saveLayout()
    }
    val tapSource = remember { MutableInteractionSource() }
    val anchor = LocalMenuAnchor.current
    val clockBounds = remember { arrayOf(Rect.Zero) }
    val glow = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0.72f)))
    val digitStyle = TextStyle(
        brush = glow, fontSize = 96.sp, fontWeight = FontWeight.Light,
        lineHeight = 92.sp, letterSpacing = (-3).sp,
        shadow = Shadow(Color.Black.copy(alpha = 0.25f), Offset(0f, 4f), 18f),
    )

    Column(
        Modifier
            .offset { IntOffset((c.x * wPx).roundToInt(), (c.y * hPx).roundToInt()) }
            .zIndex(1f)
            .graphicsLayer {
                scaleX = c.scale; scaleY = c.scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .then(
                if (vm.editMode) Modifier
                    .transformable(transform)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(10.dp)
                else Modifier
                    .onGloballyPositioned { clockBounds[0] = it.boundsInRoot() }
                    .combinedClickable(
                        interactionSource = tapSource,
                        indication = null,
                        onLongClick = {
                            anchor.rect = clockBounds[0]
                            vm.menu = MenuTarget.Clock
                        },
                    ) {
                        runCatching {
                            ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
            ),
    ) {
        Text(hour, style = digitStyle)
        Text(minute, style = digitStyle)
        Text(date, style = LabelStyle.copy(fontSize = 16.sp), modifier = Modifier.padding(start = 6.dp, top = 6.dp))
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    if (count <= 1) {
        Spacer(Modifier.height(14.dp))
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 16.dp else 6.dp, label = "dot")
            Box(
                Modifier.padding(horizontal = 3.dp).size(w, 6.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 0.95f else 0.45f))
            )
        }
    }
}

// ======================= Dock =======================
/** State of a drag that is happening inside the dock. */
private class DockLocalDrag(val from: Int) {
    var dx by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
    var global by mutableStateOf(false)     // true once pulled up out of the dock
}

@Composable
private fun Dock(
    vm: LauncherViewModel,
    dragInfo: DragInfo?,
    insertAt: Int?,
    onDrop: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val keys = vm.layout.dock
    if (keys.isEmpty() && dragInfo == null) return
    val iconSize = rememberIconSize(vm)
    val density = LocalDensity.current
    val liftPx = with(density) { 60.dp.toPx() }

    var local by remember { mutableStateOf<DockLocalDrag?>(null) }
    var rowWidth by remember { mutableFloatStateOf(0f) }
    val n = keys.size
    val slotW = if (n > 0 && rowWidth > 0f) rowWidth / n else 1f

    // Which slot the held dock app is over (changes only when it crosses into another slot)
    val localTarget by remember(n, slotW) {
        derivedStateOf {
            local?.takeIf { !it.global }?.let { d ->
                (d.from + (d.dx / slotW).roundToInt()).coerceIn(0, (n - 1).coerceAtLeast(0))
            }
        }
    }
    // Gap for apps dragged IN from the home screen / folders
    val external = insertAt != null && local == null && dragInfo?.page != LauncherViewModel.DOCK_PAGE

    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()
            .heightIn(min = 78.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .glass(RoundedCornerShape(30.dp), if (dragInfo != null || local != null) 0.22f else 0.12f)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width.toFloat() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            keys.forEachIndexed { i, k ->
                DockGap(external && insertAt == i, iconSize)
                key(k) {
                    val from = local?.from ?: -1
                    val t = localTarget
                    val goal = when {
                        t == null || i == from -> 0f
                        t > from && i in (from + 1)..t -> -slotW      // slide left
                        t < from && i in t until from -> slotW        // slide right
                        else -> 0f
                    }
                    val shift by animateFloatAsState(
                        goal, spring(dampingRatio = 0.85f, stiffness = 500f), label = "dockShift",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .zIndex(if (i == from) 1f else 0f)
                            .graphicsLayer {
                                val d = local
                                if (d != null && d.from == i) {
                                    if (d.global) {
                                        alpha = 0f
                                    } else {
                                        translationX = d.dx
                                        translationY = d.dy
                                        scaleX = 1.12f; scaleY = 1.12f
                                    }
                                } else {
                                    translationX = if (d == null) 0f else shift
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        vm.appMap[k]?.let { app ->
                            DockIcon(
                                vm = vm,
                                index = i,
                                app = app,
                                iconSize = iconSize,
                                liftPx = liftPx,
                                onDrop = onDrop,
                                onStart = { local = DockLocalDrag(i) },
                                getLocal = { local },
                                onFinish = { local = null },
                                target = { localTarget },
                            )
                        }
                    }
                }
            }
            DockGap(external && insertAt != null && insertAt >= n, iconSize)
        }
    }
}

/** A gap that smoothly opens where a dragged app will land in the dock. */
@Composable
private fun DockGap(open: Boolean, iconSize: Dp) {
    val w by animateDpAsState(
        if (open) iconSize + 16.dp else 0.dp,
        spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "gap",
    )
    Spacer(Modifier.width(w))
}

@Composable
private fun DockIcon(
    vm: LauncherViewModel,
    index: Int,
    app: AppInfo,
    iconSize: Dp,
    liftPx: Float,
    onDrop: () -> Unit,
    onStart: () -> Unit,
    getLocal: () -> DockLocalDrag?,
    onFinish: () -> Unit,
    target: () -> Int?,
) {
    val haptic = LocalHapticFeedback.current
    val anchor = LocalMenuAnchor.current
    val bounds = remember { arrayOf(Rect.Zero) }
    Box(
        Modifier
            .onGloballyPositioned { if (getLocal() == null) bounds[0] = it.boundsInRoot() }
            .pointerInput(index, app.key) {
                var startOff = Offset.Zero
                var startBounds = Rect.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.suppressClick = true
                        startOff = off
                        startBounds = bounds[0]
                        onStart()
                        anchor.rect = startBounds
                        vm.menu = MenuTarget.DockApp(index, app)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val d = getLocal()
                        if (d != null) {
                            d.dx += amount.x
                            d.dy += amount.y
                            if (vm.menu != null && Offset(d.dx, d.dy).getDistance() > 24f) vm.menu = null
                            if (!d.global && d.dy < -liftPx) {
                                // Pulled up out of the dock → continue as a normal drag onto the home screen
                                d.global = true
                                val s = vm.settings
                                val pad = GridPad.toPx()
                                val cw = (vm.gridSize.width - 2 * pad) / s.cols
                                val ch = vm.gridSize.height / s.rows
                                val grab = Offset(cw / 2f, ch / 2f) + startOff -
                                    Offset(startBounds.width / 2f, startBounds.height / 2f)
                                val pointer = startBounds.topLeft + startOff + Offset(d.dx, d.dy)
                                vm.startDockDrag(index, app.key, pointer, grab)
                            } else if (d.global) {
                                vm.dragBy(amount)
                            }
                        }
                    },
                    onDragEnd = {
                        val d = getLocal()
                        val t = target()                 // read before clearing the drag
                        onFinish()
                        when {
                            d == null -> {}
                            d.global -> vm.dropHandler?.invoke() ?: vm.cancelDrag()
                            Offset(d.dx, d.dy).getDistance() < 24f -> {}   // just a long-press: keep menu
                            t != null && t != index -> vm.moveDockApp(index, t)
                        }
                        vm.suppressClick = false
                    },
                    onDragCancel = {
                        onFinish()
                        vm.cancelDrag()
                        vm.suppressClick = false
                    },
                )
            },
    ) {
        AppIcon(app, iconSize, showLabel = false) {
            if (vm.suppressClick) vm.suppressClick = false else vm.launch(app)
        }
    }
}

@Composable
private fun EditBar(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GlassPill("Wallpaper", Modifier.weight(1f)) {
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Set wallpaper")) }
        }
        GlassPill("Widgets", Modifier.weight(1f)) { vm.widgetPickerOpen = true }
        GlassPill("Settings", Modifier.weight(1f)) { vm.showSettings = true }
        GlassPill("Done", Modifier.weight(1f), selected = true) { vm.exitEditMode() }
    }
}

@Composable
private fun SelectBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) Color(0xFF2F80ED) else Color.Black.copy(alpha = 0.25f))
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SelectionBar(vm: LauncherViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            "${vm.selected.size} selected · hold & drag to move together",
            style = LabelStyle.copy(fontSize = 13.sp),
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelPill("Group", Modifier.weight(1f)) { vm.groupSelected() }
            SelPill("Hide", Modifier.weight(1f)) { vm.hideSelected() }
            SelPill("Uninstall", Modifier.weight(1f), danger = true) { vm.uninstallSelected() }
            if (vm.settings.drawerMode) SelPill("Remove", Modifier.weight(1f)) { vm.removeSelected() }
            SelPill("Done", Modifier.weight(1f), highlight = true) { vm.exitEditMode() }
        }
    }
}

@Composable
private fun SelPill(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .glass(RoundedCornerShape(50), if (highlight) 0.40f else 0.16f)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (danger) Color(0xFFFF8A8A) else Color.White,
            fontSize = 13.sp, maxLines = 1,
        )
    }
}
