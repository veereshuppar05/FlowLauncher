package com.flow.launcher.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.flow.launcher.*

@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != null) last = value }
    return value ?: last
}

@Composable
private fun noRipple() = remember { MutableInteractionSource() }

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
                vm.renameFolder(f.id, it); renaming = false
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

// ---------------- Long-press menu ----------------
@Composable
fun ContextMenuOverlay(vm: LauncherViewModel) {
    val target = rememberLast(vm.menu)
    val ctx = LocalContext.current
    AnimatedVisibility(
        vm.menu != null,
        enter = fadeIn(tween(150)) + slideInVertically(tween(220)) { it / 8 },
        exit = fadeOut(tween(120)),
    ) {
        val t = target ?: return@AnimatedVisibility
        val app: AppInfo? = when (t) {
            is MenuTarget.HomeApp -> t.app
            is MenuTarget.DockApp -> t.app
            is MenuTarget.DrawerApp -> t.app
            is MenuTarget.FolderApp -> t.app
            is MenuTarget.HomeFolder -> null
        }
        val shortcuts = remember(app) { app?.let { vm.repo.shortcuts(it) }.orEmpty() }

        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = noRipple(), indication = null) { vm.menu = null },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth()
                    .glass(RoundedCornerShape(28.dp), 0.22f)
                    .clickable(interactionSource = noRipple(), indication = null) {}
                    .padding(vertical = 8.dp)
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app != null) {
                        Image(app.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        app?.label ?: (t as MenuTarget.HomeFolder).folder.name,
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
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
                        MenuRow(sc.shortLabel?.toString() ?: "", icon) { vm.launchShortcut(sc) }
                    }
                }
                if (shortcuts.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).height(1.dp)
                        .background(Color.White.copy(alpha = 0.15f)))
                }
                when (t) {
                    is MenuTarget.HomeApp -> {
                        MenuRow("Add to dock") { vm.addToDock(t.page, t.index, t.app.key) }
                        MenuRow("Move to next page") { vm.moveToNextPage(t.page, t.index) }
                        MenuRow("Remove from home screen") { vm.removeFromHome(t.page, t.index) }
                        MenuRow("Hide app") { vm.hide(t.app.key) }
                        MenuRow("App info") { vm.appInfo(t.app) }
                        MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                    }
                    is MenuTarget.HomeFolder -> {
                        MenuRow("Open and rename") { vm.menu = null; vm.openFolderId = t.folder.id }
                        MenuRow("Move to next page") { vm.moveToNextPage(t.page, t.index) }
                        MenuRow("Ungroup folder") { vm.ungroup(t.page, t.index) }
                        MenuRow("Remove from home screen") { vm.removeFromHome(t.page, t.index) }
                    }
                    is MenuTarget.DockApp -> {
                        MenuRow("Move to home screen") { vm.removeFromDock(t.index) }
                        MenuRow("App info") { vm.appInfo(t.app) }
                        MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                    }
                    is MenuTarget.DrawerApp -> {
                        MenuRow("Add to home screen") { vm.addToHome(t.app.key) }
                        MenuRow("Hide app") { vm.hide(t.app.key) }
                        MenuRow("App info") { vm.appInfo(t.app) }
                        MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                    }
                    is MenuTarget.FolderApp -> {
                        MenuRow("Remove from folder") { vm.removeFromFolder(t.folderId, t.app.key) }
                        MenuRow("App info") { vm.appInfo(t.app) }
                        MenuRow("Uninstall", danger = true) { vm.uninstall(t.app) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageBitmap? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = if (danger) Color(0xFFFF7A7A) else Color.White, fontSize = 15.sp)
    }
}

// ---------------- Settings ----------------
@Composable
fun SettingsPanel(vm: LauncherViewModel) {
    val s = vm.settings
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = noRipple(), indication = null) { vm.showSettings = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.systemBarsPadding().padding(20.dp).fillMaxWidth()
                .glass(RoundedCornerShape(30.dp), 0.18f)
                .clickable(interactionSource = noRipple(), indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Home screen", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

            Choice("Columns", listOf(4, 5), s.cols) { vm.updateSettings(s.copy(cols = it)) }
            Choice("Rows", listOf(5, 6, 7), s.rows) { vm.updateSettings(s.copy(rows = it)) }

            Column {
                Text("Icon size", color = Color.White, fontSize = 15.sp)
                Slider(s.iconScale, { vm.updateSettings(s.copy(iconScale = it)) }, valueRange = 0.8f..1.25f)
            }

            Toggle("Show app names", s.showLabels) { vm.updateSettings(s.copy(showLabels = it)) }
            Toggle("Frosted glass blur", s.blur) { vm.updateSettings(s.copy(blur = it)) }
            Toggle("Double-tap to lock", s.doubleTapLock) { vm.updateSettings(s.copy(doubleTapLock = it)) }
            Toggle("Flow clock on home screen", vm.layout.clock.visible) { vm.setClockVisible(it) }

            GlassPill("Auto-organize by category", Modifier.fillMaxWidth()) {
                vm.organizeByCategory(); vm.showSettings = false
            }
            GlassPill("Set as default launcher", Modifier.fillMaxWidth()) {
                (ctx as? Activity)?.let { requestDefaultLauncher(it) }
            }

            if (s.hidden.isNotEmpty()) {
                Text("Hidden apps", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                s.hidden.mapNotNull { vm.appMap[it] }.forEach { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(app.icon, null, Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text(app.label, color = Color.White, modifier = Modifier.weight(1f))
                        TextButton({ vm.unhide(app.key) }) { Text("Unhide") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, options: List<Int>, current: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> GlassPill("$o", selected = o == current) { onPick(o) } }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Suppress("DEPRECATION")
private fun requestDefaultLauncher(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = activity.getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            activity.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7)
            return
        }
    }
    activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
}