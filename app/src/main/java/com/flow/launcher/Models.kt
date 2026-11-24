package com.flow.launcher

import android.content.ComponentName
import android.os.UserHandle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

data class AppInfo(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
    val category: String,
)

sealed interface HomeItem {
    data class App(val key: String) : HomeItem
    data class Folder(val id: String, val name: String, val apps: List<String>) : HomeItem
}

data class ClockState(
    val x: Float = 0.05f,
    val y: Float = 0.01f,
    val scale: Float = 1f,
    val visible: Boolean = true,
)

data class LayoutState(
    val pages: List<List<HomeItem?>> = listOf(emptyList()),
    val dock: List<String> = emptyList(),
    val clock: ClockState = ClockState(),
    val known: Set<String> = emptySet(),
)

data class LauncherSettings(
    val cols: Int = 4,
    val rows: Int = 6,
    val iconScale: Float = 1f,
    val showLabels: Boolean = true,
    val blur: Boolean = true,
    val doubleTapLock: Boolean = true,
    val hidden: Set<String> = emptySet(),
    val drawerMode: Boolean = true,
)

data class DragState(val page: Int, val index: Int, val offset: Offset = Offset.Zero)

sealed interface MenuTarget {
    data class HomeApp(val page: Int, val index: Int, val app: AppInfo) : MenuTarget
    data class HomeFolder(val page: Int, val index: Int, val folder: HomeItem.Folder) : MenuTarget
    data class DockApp(val index: Int, val app: AppInfo) : MenuTarget
    data class DrawerApp(val app: AppInfo) : MenuTarget
    data class FolderApp(val folderId: String, val app: AppInfo) : MenuTarget
}
