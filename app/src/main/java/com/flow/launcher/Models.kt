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

data class WidgetItem(
    val appWidgetId: Int,
    val page: Int,
    val col: Int,
    val row: Int,
    val spanX: Int,
    val spanY: Int,
)

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
    val widgets: List<WidgetItem> = emptyList(),
)

enum class IconShape(val label: String) {
    SQUIRCLE("Squircle"), CIRCLE("Circle"), SQUARE("Square"), TEARDROP("Teardrop")
}

enum class GestureAction(val label: String) {
    NONE("Do nothing"),
    DRAWER("App drawer / search"),
    NOTIFICATIONS("Notifications"),
    QUICK_SETTINGS("Quick settings"),
    LOCK("Lock screen"),
    RECENTS("Recent apps"),
    SCREENSHOT("Screenshot"),
    POWER_MENU("Power menu"),
    PRIVATE_SPACE("Private space"),
    EDIT_MODE("Edit home screen"),
    SETTINGS("Launcher settings"),
    FIRST_PAGE("Go to first page"),
}

enum class Gesture(val label: String, val default: GestureAction) {
    SWIPE_UP("Swipe up", GestureAction.DRAWER),
    SWIPE_DOWN("Swipe down", GestureAction.NOTIFICATIONS),
    DOUBLE_TAP("Double-tap", GestureAction.LOCK),
    TWO_UP("Two-finger swipe up", GestureAction.PRIVATE_SPACE),
    TWO_DOWN("Two-finger swipe down", GestureAction.QUICK_SETTINGS),
    PINCH_IN("Pinch in", GestureAction.EDIT_MODE),
    PINCH_OUT("Pinch out", GestureAction.NONE),
}

data class LauncherSettings(
    val cols: Int = 4,
    val rows: Int = 6,
    val iconScale: Float = 1f,
    val showLabels: Boolean = true,
    val blur: Boolean = true,
    val hidden: Set<String> = emptySet(),          // apps in the private space
    val drawerMode: Boolean = true,
    val iconPack: String? = null,
    val iconShape: IconShape = IconShape.SQUIRCLE,
    val badges: Boolean = true,
    val lockPrivate: Boolean = true,
    val gestures: Map<Gesture, GestureAction> = Gesture.entries.associateWith { it.default },
)

data class DragState(
    val page: Int,
    val index: Int,
    val item: HomeItem,
    val start: Offset,
    val pointer: Offset,
    val grab: Offset,
    val group: List<HomeItem> = emptyList(),   // non-empty when moving several selected items
)

sealed interface MenuTarget {
    data class HomeApp(val page: Int, val index: Int, val app: AppInfo) : MenuTarget
    data class HomeFolder(val page: Int, val index: Int, val folder: HomeItem.Folder) : MenuTarget
    data class DockApp(val index: Int, val app: AppInfo) : MenuTarget
    data class DrawerApp(val app: AppInfo) : MenuTarget
    data class FolderApp(val folderId: String, val app: AppInfo) : MenuTarget
    data class HomeWidget(val widget: WidgetItem) : MenuTarget
    data class PrivateApp(val app: AppInfo) : MenuTarget
}
