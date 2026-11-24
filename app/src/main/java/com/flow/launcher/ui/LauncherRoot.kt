package com.flow.launcher.ui

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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun LauncherRoot(vm: LauncherViewModel, setWindowBlur: (Int) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val overlay = vm.drawerOpen || vm.openFolderId != null || vm.showSettings
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
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f * blur)))

            AnimatedVisibility(
                vm.drawerOpen,
                enter = fadeIn(tween(220)) + slideInVertically(tween(320)) { it / 4 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(260)) { it / 4 },
            ) { AppDrawer(vm) }

            FolderOverlay(vm)

            AnimatedVisibility(
                vm.showSettings,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
            ) { SettingsPanel(vm) }

            ContextMenuOverlay(vm)
        }
    }
}

@Composable
private fun HomeScreen(vm: LauncherViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { vm.layout.pages.size }
    val entry = remember { Animatable(1f) }

    LaunchedEffect(vm.homeTick) { if (vm.homeTick > 0) pager.animateScrollToPage(0) }
    LaunchedEffect(vm.resumeTick) {
        entry.snapTo(0f)
        entry.animateTo(1f, tween(700, easing = LinearEasing))
    }

    Column(
        modifier
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -120f) vm.drawerOpen = true
                        else if (total > 120f) vm.expandNotifications()
                    },
                    onVerticalDrag = { _, d -> total += d },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { if (vm.settings.doubleTapLock) LockService.lock(ctx) },
                    onLongPress = {
                        scope.launch {
                            delay(60)
                            if (vm.drag == null && vm.menu == null) vm.editMode = true
                        }
                    },
                    onTap = { if (vm.editMode) vm.editMode = false },
                )
            }
            .systemBarsPadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                userScrollEnabled = vm.drag == null,
                beyondViewportPageCount = 1,
            ) { page -> PageGrid(vm, page, entry.value) }
        }
        PageDots(vm.layout.pages.size, pager.currentPage)
        Dock(vm)
        AnimatedVisibility(vm.editMode) { EditBar(vm) }
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
        infiniteRepeatable(tween(120 + (seed % 3) * 25), RepeatMode.Reverse),
        label = "r",
    )
    return graphicsLayer { rotationZ = r }
}

@Composable
private fun PageGrid(vm: LauncherViewModel, page: Int, entry: Float) {
    val s = vm.settings
    val slots = vm.layout.pages.getOrNull(page) ?: return
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        val cellW = maxWidth / s.cols
        val cellH = maxHeight / s.rows
        val density = LocalDensity.current
        val cw = with(density) { cellW.toPx() }
        val ch = with(density) { cellH.toPx() }
        val iconSize = (minOf(cellW, cellH) * 0.62f * s.iconScale).coerceAtMost(76.dp)
        val clock = vm.layout.clock

        if (page == 0 && clock.visible) FlowClock(vm, maxWidth, maxHeight, entry)

        // Icons "flow" outward from wherever the clock sits
        val originX = if (clock.visible) clock.x + 0.3f else 0.5f
        val originY = if (clock.visible) clock.y + 0.1f else 0f

        slots.forEachIndexed { index, item ->
            if (item == null) return@forEachIndexed
            val col = index % s.cols
            val row = index / s.cols
            val dragging = vm.drag?.takeIf { it.page == page && it.index == index }
            val dist = hypot((col + 0.5f) / s.cols - originX, (row + 0.5f) / s.rows - originY)
            val t = ((entry - dist * 0.4f) / 0.6f).coerceIn(0f, 1f)

            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (col * cw + (dragging?.offset?.x ?: 0f)).roundToInt(),
                            (row * ch + (dragging?.offset?.y ?: 0f)).roundToInt(),
                        )
                    }
                    .size(cellW, cellH)
                    .zIndex(if (dragging != null) 10f else 0f)
                    .graphicsLayer {
                        val sc = if (dragging != null) 1.12f else 0.7f + 0.3f * backOut(t)
                        scaleX = sc; scaleY = sc
                        alpha = if (dragging != null) 0.92f else t
                    }
                    .then(if (vm.editMode && dragging == null) Modifier.wiggle(index) else Modifier)
                    .pointerInput(page, index, item, s.cols, s.rows) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.startDrag(page, index)
                            },
                            onDrag = { change, amount -> change.consume(); vm.dragBy(amount) },
                            onDragEnd = {
                                val d = vm.drag
                                if (d == null || d.offset.getDistance() < 24f) {
                                    vm.cancelDrag()
                                    vm.showMenuFor(page, index)
                                } else {
                                    val tc = ((col * cw + d.offset.x + cw / 2) / cw).toInt().coerceIn(0, s.cols - 1)
                                    val tr = ((row * ch + d.offset.y + ch / 2) / ch).toInt().coerceIn(0, s.rows - 1)
                                    vm.endDrag(tr * s.cols + tc)
                                }
                            },
                            onDragCancel = { vm.cancelDrag() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (item) {
                    is HomeItem.App -> vm.appMap[item.key]?.let { app ->
                        AppIcon(app, iconSize, s.showLabels) {
                            if (vm.editMode) vm.editMode = false else vm.launch(app)
                        }
                    }
                    is HomeItem.Folder -> FolderIcon(item, vm.appMap, iconSize, s.showLabels) {
                        vm.openFolderId = item.id
                    }
                }
            }
        }
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
    val settle = 1.25f - 0.25f * FastOutSlowInEasing.transform(entry)  // shrinks into place on unlock/return

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
private fun Dock(vm: LauncherViewModel) {
    val apps = vm.layout.dock.mapNotNull { vm.appMap[it] }
    if (apps.isEmpty()) return
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()
            .glass(RoundedCornerShape(30.dp), 0.12f).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassPill("Wallpaper", Modifier.weight(1f)) {
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Set wallpaper")) }
        }
        GlassPill("Settings", Modifier.weight(1f)) { vm.showSettings = true }
        GlassPill("Done", Modifier.weight(1f), selected = true) { vm.editMode = false }
    }
}