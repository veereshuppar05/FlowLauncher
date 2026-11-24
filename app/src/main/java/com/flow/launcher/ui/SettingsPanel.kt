package com.flow.launcher.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.launcher.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsPanel(vm: LauncherViewModel) {
    val s = vm.settings
    val activity = LocalContext.current as Activity
    val packs by produceState(emptyList<IconPackInfo>()) {
        value = withContext(Dispatchers.IO) { IconPacks.installed(activity) }
    }
    var badgeAccess by remember { mutableStateOf(BadgeService.hasAccess(activity)) }
    LaunchedEffect(vm.resumeTick) { badgeAccess = BadgeService.hasAccess(activity) }

    Box(
        Modifier.fillMaxSize().clickable(interactionSource = noRipple(), indication = null) { vm.showSettings = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.systemBarsPadding().padding(16.dp).fillMaxWidth()
                .glass(RoundedCornerShape(30.dp), 0.18f)
                .clickable(interactionSource = noRipple(), indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Launcher settings", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

            // ----- Home screen -----
            Section("Home screen")
            Text("Mode", color = Color.White, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill("Drawer", Modifier.weight(1f), selected = s.drawerMode) {
                    vm.updateSettings(s.copy(drawerMode = true))
                }
                GlassPill("Standard", Modifier.weight(1f), selected = !s.drawerMode) {
                    vm.updateSettings(s.copy(drawerMode = false))
                }
            }
            Choice("Columns", listOf(4, 5), s.cols) { vm.updateSettings(s.copy(cols = it)) }
            Choice("Rows", listOf(5, 6, 7), s.rows) { vm.updateSettings(s.copy(rows = it)) }
            Toggle("Show app names", s.showLabels) { vm.updateSettings(s.copy(showLabels = it)) }
            Toggle("Flow clock", vm.layout.clock.visible) { vm.setClockVisible(it) }

            // ----- Look -----
            Section("Icons & look")
            Column {
                Text("Icon size", color = Color.White, fontSize = 15.sp)
                Slider(s.iconScale, { vm.updateSettings(s.copy(iconScale = it)) }, valueRange = 0.8f..1.25f)
            }
            Text("Icon shape", color = Color.White, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconShape.entries.forEach { shape ->
                    GlassPill(shape.label, Modifier.weight(1f), selected = s.iconShape == shape) {
                        vm.updateSettings(s.copy(iconShape = shape))
                    }
                }
            }
            PickerRow(
                label = "Icon pack",
                current = packs.firstOrNull { it.packageName == s.iconPack }?.label ?: "System icons",
                options = listOf<Pair<String?, String>>(null to "System icons") + packs.map { it.packageName to it.label },
            ) { vm.updateSettings(s.copy(iconPack = it)) }
            if (packs.isEmpty()) {
                Hint("Install any Nova-compatible icon pack from the Play Store and it will show up here.")
            }
            Toggle("Frosted glass blur", s.blur) { vm.updateSettings(s.copy(blur = it)) }

            // ----- Badges -----
            Section("Notification dots")
            Toggle("Show notification dots", s.badges) { vm.updateSettings(s.copy(badges = it)) }
            if (s.badges && !badgeAccess) {
                GlassPill("Allow notification access", Modifier.fillMaxWidth()) { BadgeService.openSettings(activity) }
            }

            // ----- Private space -----
            Section("Private space")
            Toggle("Require fingerprint / PIN", s.lockPrivate) { vm.updateSettings(s.copy(lockPrivate = it)) }
            GlassPill("Open private space (${vm.privateApps.size} apps)", Modifier.fillMaxWidth()) {
                openPrivateSpace(activity, vm)
            }

            // ----- Gestures -----
            Section("Gestures")
            Gesture.entries.forEach { g ->
                PickerRow(
                    label = g.label,
                    current = (s.gestures[g] ?: g.default).label,
                    options = GestureAction.entries.map { it to it.label },
                ) { action -> vm.updateSettings(s.copy(gestures = s.gestures + (g to action))) }
            }
            Hint("Lock, recent apps, screenshot and power menu need Flow Launcher turned on in Accessibility settings. Long-press empty space always opens edit mode.")

            // ----- More -----
            Section("More")
            GlassPill("Auto-organize by category", Modifier.fillMaxWidth()) {
                vm.organizeByCategory()
                vm.showSettings = false
            }
            GlassPill("Set as default launcher", Modifier.fillMaxWidth()) { requestDefaultLauncher(activity) }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
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

@Composable
private fun <T> PickerRow(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box {
            GlassPill(current, Modifier.widthIn(max = 190.dp)) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(value) })
                }
            }
        }
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
