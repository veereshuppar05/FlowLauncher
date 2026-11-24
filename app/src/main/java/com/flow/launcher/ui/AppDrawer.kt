package com.flow.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.launcher.LauncherViewModel
import com.flow.launcher.MenuTarget

@Composable
fun AppDrawer(vm: LauncherViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val all = vm.visibleApps
    val categories = remember(all) { all.map { it.category }.distinct().sorted() }
    val q = query.trim()
    val shown = remember(all, q, category) {
        all.filter { a ->
            (category == null || a.category == category) &&
                (q.isEmpty() || a.label.contains(q, ignoreCase = true) ||
                    a.label.split(' ').joinToString("") { it.take(1) }.startsWith(q, ignoreCase = true))
        }
    }
    val iconSize = (52 * vm.settings.iconScale).dp

    // Pull down at the top of the list to close
    val closeOnPull = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) pulled += available.y else if (consumed.y < 0f) pulled = 0f
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled > 180f) vm.drawerOpen = false
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .nestedScroll(closeOnPull),
    ) {
        Box(
            Modifier.fillMaxWidth().height(30.dp).pointerInput(Unit) {
                detectVerticalDragGestures { _, d -> if (d > 12f) vm.drawerOpen = false }
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(38.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f)))
        }

        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let(vm::launch) }),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .glass(RoundedCornerShape(26.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search apps", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    inner()
                }
            },
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GlassPill("All", selected = category == null) { category = null } }
            items(categories) { c -> GlassPill(c, selected = category == c) { category = c } }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(vm.settings.cols),
            modifier = Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (q.isEmpty() && category == null && vm.suggestions.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Suggested for now") }
                items(vm.suggestions, key = { "s_" + it.key }) { app ->
                    AppIcon(app, iconSize, vm.settings.showLabels,
                        onLongClick = { vm.menu = MenuTarget.DrawerApp(app) }) { vm.launch(app) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("All apps") }
            }
            items(shown, key = { it.key }) { app ->
                AppIcon(app, iconSize, vm.settings.showLabels,
                    onLongClick = { vm.menu = MenuTarget.DrawerApp(app) }) { vm.launch(app) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}
