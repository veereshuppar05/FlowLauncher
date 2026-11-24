package com.flow.launcher.ui

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
            val overlay = vm.drawerOpen || vm.openFolderId != null || vm.showSettings ||
                vm.widgetPickerOpen || vm.privateOpen
            val blur by animateFloatAsState(if (overlay) 1f else 0f, tween(300), label = "blur")
            LaunchedEffect(blur, vm.settings.blur) {
                setWindowBlur(if (vm.settings.blur) (blur * 80).toInt() else 0)
            }
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

                if (vm.menu == null) vm.drag?.let { DragGhost(vm, it) }

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
    val padPx = with(density) { 12.dp.toPx() }
    val edgePx = with(density) { 36.dp.toPx() }
    val pager = rememberPagerState { vm.layout.pages.size }
    val entry = remember { Animatable(1f) }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    var dockBounds by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(vm.homeTick) { if (vm.homeTick > 0) pager.animateScrollToPage(0) }
    LaunchedEffect(vm.scrollTo) {
        vm.scrollTo?.let {
            pager.animateScrollToPage(it)
            vm.scrollTo = null
        }
    }
    LaunchedEffect(vm.resumeTick) {
        entry.snapTo(0f)
        entry.animateTo(1f, tween(700, easing = LinearEasing))
    }
    LaunchedEffect(pager.currentPage) { vm.currentPage = pager.currentPage }

    // Hold a dragged icon at the screen edge to flip pages
    val dragging = vm.drag != null
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
            val target = pager.currentPage + dir
            if (target == vm.layout.pages.size && vm.layout.pages.lastOrNull()?.any { it != null } == true) {
                vm.addEmptyPage()
            }
            if (target in 0 until vm.layout.pages.size) {
                pager.animateScrollToPage(target)
                delay(400)
            }
        }
    }

    val drop: () -> Unit = {
        val d = vm.drag
        if (d != null) {
            val s = vm.settings
            val cw = (gridBounds.width - 2 * padPx) / s.cols
            val ch = gridBounds.height / s.rows
            val center = d.pointer - d.grab + Offset(cw / 2, ch / 2)
            when {
                dockBounds.contains(d.pointer) -> vm.endDrag(null, null, toDock = true)
                gridBounds.contains(center) -> {
                    val col = ((center.x - gridBounds.left - padPx) / cw).toInt().coerceIn(0, s.cols - 1)
                    val row = ((center.y - gridBounds.top) / ch).toInt().coerceIn(0, s.rows - 1)
                    vm.endDrag(pager.currentPage, row * s.cols + col, toDock = false)
                }
                else -> vm.endDrag(null, null, toDock = false)
            }
        }
    }

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
                vm.gridSize = c.size.toSize()
            }
        ) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                userScrollEnabled = vm.drag == null,
                beyondViewportPageCount = 2,
            ) { page ->
                PageGrid(vm, page, entry.value, { Offset(gridBounds.left + padPx, gridBounds.top) }, drop)
            }
        }
        PageDots(vm.layout.pages.size, pager.currentPage)
        Dock(vm) { dockBounds = it }
        AnimatedVisibility(vm.editMode) {
            if (vm.selected.isEmpty()) EditBar(vm) else SelectionBar(vm)
        }
    }
}

private fun backOut(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

@Composable
private fun Modifier.wiggle(seed: Int): Modifier {
    val inf = rememberInfiniteTransition(label = "wiggle")
    val r by inf.animateFloat(
        -1.6f, 1.6f,
        infiniteRepeatable(tween(120 + (abs(seed) % 3) * 25), RepeatMode.Reverse),
        label = "r",
    )
    return graphicsLayer { rotationZ = r }
}

@Composable
private fun PageGrid(
    vm: LauncherViewModel,
    page: Int,
    entry: Float,
    origin: () -> Offset,
    onDrop: () -> Unit,
) {
    val s = vm.settings
    val slots = vm.layout.pages.getOrNull(page) ?: return
    val haptic = LocalHapticFeedback.current
    val widgets = LocalWidgets.current
    val anchor = LocalMenuAnchor.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        val cellW = maxWidth / s.cols
        val cellH = maxHeight / s.rows
        val density = LocalDensity.current
        val cw = with(density) { cellW.toPx() }
        val ch = with(density) { cellH.toPx() }
        val iconSize = (minOf(cellW, cellH) * 0.62f * s.iconScale).coerceAtMost(76.dp)
        val clock = vm.layout.clock

        if (page == 0 && clock.visible) FlowClock(vm, maxWidth, maxHeight, entry)

        // ---- widgets ----
        vm.layout.widgets.filter { it.page == page }.forEach { w ->
            key(w.appWidgetId) {
                Box(
                    Modifier
                        .offset { IntOffset((w.col * cw).roundToInt(), (w.row * ch).roundToInt()) }
                        .size(cellW * w.spanX, cellH * w.spanY)
                        .padding(6.dp)
                        .onGloballyPositioned { vm.widgetBounds[w.appWidgetId] = it.boundsInRoot() }
                        .then(if (vm.editMode) Modifier.wiggle(w.appWidgetId) else Modifier),
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
        val originX = if (clock.visible) clock.x + 0.3f else 0.5f
        val originY = if (clock.visible) clock.y + 0.1f else 0f

        slots.forEachIndexed { index, item ->
            if (item == null) return@forEachIndexed
            val col = index % s.cols
            val row = index / s.cols
            val dragNow = vm.drag
            val isDragged = vm.menu == null && dragNow != null &&
                ((dragNow.page == page && dragNow.index == index) || dragNow.group.contains(item))
            val dist = hypot((col + 0.5f) / s.cols - originX, (row + 0.5f) / s.rows - originY)
            val t = ((entry - dist * 0.4f) / 0.6f).coerceIn(0f, 1f)

            Box(
                Modifier
                    .offset { IntOffset((col * cw).roundToInt(), (row * ch).roundToInt()) }
                    .size(cellW, cellH)
                    .graphicsLayer {
                        val sc = 0.7f + 0.3f * backOut(t)
                        scaleX = sc; scaleY = sc
                        alpha = if (isDragged) 0f else t
                    }
                    .then(if (vm.editMode && !isDragged) Modifier.wiggle(index) else Modifier)
                    .pointerInput(page, index, item, s.cols, s.rows) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { off ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.suppressClick = true               // the release must not open the app
                                val pointer = origin() + Offset(col * cw, row * ch) + off
                                vm.startDrag(page, index, item, pointer, off)
                                if (vm.drag?.group.isNullOrEmpty()) {          // no menu when moving a group
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
                                if (d != null && (d.pointer - d.start).getDistance() < 24f) {
                                    vm.cancelDrag()
                                } else {
                                    onDrop()
                                }
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
                Box {
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

/** The icon that follows your finger while dragging (drawn above everything, across pages). */
@Composable
private fun DragGhost(vm: LauncherViewModel, d: DragState) {
    val density = LocalDensity.current
    val s = vm.settings
    val g = vm.gridSize
    if (g.width <= 0f) return
    val padPx = with(density) { 12.dp.toPx() }
    val cwPx = (g.width - 2 * padPx) / s.cols
    val chPx = g.height / s.rows
    val w = with(density) { cwPx.toDp() }
    val h = with(density) { chPx.toDp() }
    val iconSize = (minOf(w, h) * 0.62f * s.iconScale).coerceAtMost(76.dp)
    Box(
        Modifier
            .offset { IntOffset((d.pointer.x - d.grab.x).roundToInt(), (d.pointer.y - d.grab.y).roundToInt()) }
            .size(w, h)
            .zIndex(20f)
            .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.95f },
        contentAlignment = Alignment.Center,
    ) {
        if (d.group.size > 1) {
            // stacked icons + count
            Box {
                val stack = d.group.take(3)
                stack.reversed().forEachIndexed { i, item ->
                    val shift = ((stack.size - 1 - i) * 6).dp
                    Box(Modifier.offset(x = shift, y = shift)) { GhostItem(vm, item, iconSize, false) }
                }
                NotifDot(d.group.size, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp))
            }
        } else {
            GhostItem(vm, d.item, iconSize, s.showLabels)
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

@Composable
private fun FlowClock(vm: LauncherViewModel, areaW: Dp, areaH: Dp, entry: Float) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000)
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
    val settle = 1.25f - 0.25f * FastOutSlowInEasing.transform(entry)

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
                scaleX = c.scale * settle; scaleY = c.scale * settle
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .then(
                if (vm.editMode) Modifier
                    .transformable(transform)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(10.dp)
                else Modifier.clickable(interactionSource = tapSource, indication = null) {
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

@Composable
private fun Dock(vm: LauncherViewModel, onBounds: (Rect) -> Unit) {
    val apps = vm.layout.dock.mapNotNull { vm.appMap[it] }
    if (apps.isEmpty() && vm.drag == null) return
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()
            .heightIn(min = 78.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .glass(RoundedCornerShape(30.dp), if (vm.drag != null) 0.22f else 0.12f)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        apps.forEachIndexed { i, app ->
            AppIcon(
                app, (54 * vm.settings.iconScale).dp, showLabel = false,
                onLongClick = { vm.menu = MenuTarget.DockApp(i, app) },
            ) { vm.launch(app) }
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
