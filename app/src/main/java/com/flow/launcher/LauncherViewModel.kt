package com.flow.launcher

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias Pages = MutableList<MutableList<HomeItem?>>

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Application get() = getApplication()
    val repo = LauncherRepo(application)
    private var loaded = false
    private var idSeq = 0

    var apps by mutableStateOf<List<AppInfo>>(emptyList()); private set
    var appMap by mutableStateOf<Map<String, AppInfo>>(emptyMap()); private set
    var layout by mutableStateOf(LayoutState()); private set
    var settings by mutableStateOf(repo.loadSettings()); private set
    var suggestions by mutableStateOf<List<AppInfo>>(emptyList()); private set
    var drag by mutableStateOf<DragState?>(null); private set
    var homeTick by mutableIntStateOf(0); private set
    var resumeTick by mutableIntStateOf(0); private set
    var scrollTo by mutableStateOf<Int?>(null)
    var gridSize by mutableStateOf(Size.Zero)

    var drawerOpen by mutableStateOf(false)
    var openFolderId by mutableStateOf<String?>(null)
    var editMode by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var widgetPickerOpen by mutableStateOf(false)
    var privateOpen by mutableStateOf(false)
    var menu by mutableStateOf<MenuTarget?>(null)

    var currentPage = 0
    var suppressClick = false
    var selected by mutableStateOf<Set<String>>(emptySet())
    private val uninstallQueue = ArrayDeque<AppInfo>()
    val widgetBounds = HashMap<Int, Rect>()

    val visibleApps: List<AppInfo> get() = apps.filter { it.key !in settings.hidden }
    val privateApps: List<AppInfo> get() = apps.filter { it.key in settings.hidden }
    val openFolder: HomeItem.Folder? get() = openFolderId?.let { findFolder(it)?.third }

    init {
        repo.registerChanges { refresh() }
        refresh()
    }

    override fun onCleared() {
        repo.unregisterChanges()
    }

    fun refresh() {
        viewModelScope.launch {
            val pack = settings.iconPack
            val list = withContext(Dispatchers.Default) { repo.loadApps(pack) }
            apps = list
            appMap = list.associateBy { it.key }
            val base = if (loaded) layout else repo.loadLayout()
            layout = if (base == null) defaultLayout() else reconcile(base)
            loaded = true
            if (!settings.drawerMode) ensureAllOnHome()
            saveLayout()
            suggestions = repo.suggestions(visibleApps)
        }
    }

    // =============== grid helpers ===============
    private val slotCount get() = settings.cols * settings.rows
    private fun emptyPage(): MutableList<HomeItem?> = MutableList(slotCount) { null }

    private fun WidgetItem.covers(page: Int, index: Int): Boolean {
        if (page != this.page) return false
        val c = index % settings.cols
        val r = index / settings.cols
        return c >= col && c < col + spanX && r >= row && r < row + spanY
    }

    private fun blocked(page: Int, index: Int, clockVisible: Boolean, widgets: List<WidgetItem>) =
        (page == 0 && clockVisible && index < settings.cols * 2) || widgets.any { it.covers(page, index) }

    private fun place(
        p: Pages,
        item: HomeItem,
        clockVisible: Boolean = layout.clock.visible,
        widgets: List<WidgetItem> = layout.widgets,
        startPage: Int = 0,
    ) {
        while (p.size < startPage) p += emptyPage()
        for (pi in startPage until p.size) {
            val slot = p[pi].indices.firstOrNull { p[pi][it] == null && !blocked(pi, it, clockVisible, widgets) }
            if (slot != null) {
                p[pi][slot] = item
                return
            }
        }
        p += emptyPage().also { it[0] = item }
    }

    private fun findRect(
        pages: Pages,
        widgets: List<WidgetItem>,
        sx: Int,
        sy: Int,
        startPage: Int,
        clockVisible: Boolean,
    ): Triple<Int, Int, Int> {
        while (pages.size <= startPage) pages += emptyPage()
        var pi = startPage
        while (true) {
            if (pi == pages.size) pages += emptyPage()
            for (r in 0..settings.rows - sy) {
                for (c in 0..settings.cols - sx) {
                    val fits = (r until r + sy).all { rr ->
                        (c until c + sx).all { cc ->
                            val idx = rr * settings.cols + cc
                            pages[pi][idx] == null && !blocked(pi, idx, clockVisible, widgets)
                        }
                    }
                    if (fits) return Triple(pi, c, r)
                }
            }
            pi++
        }
    }

    /** Re-flows icons around widgets (used after grid-size changes, organizing, clock toggle). */
    private fun relayout(
        items: List<HomeItem>,
        clockVisible: Boolean,
        widgets: List<WidgetItem>,
    ): Pair<List<List<HomeItem?>>, List<WidgetItem>> {
        val pages: Pages = mutableListOf(emptyPage())
        val placed = mutableListOf<WidgetItem>()
        widgets.sortedWith(compareBy({ it.page }, { it.row }, { it.col })).forEach { w ->
            val sx = w.spanX.coerceIn(1, settings.cols)
            val sy = w.spanY.coerceIn(1, settings.rows)
            val (pg, c, r) = findRect(pages, placed, sx, sy, w.page, clockVisible)
            placed += w.copy(page = pg, col = c, row = r, spanX = sx, spanY = sy)
        }
        var pi = 0
        var i = 0
        for (item in items) {
            while (true) {
                if (pi >= pages.size) pages += emptyPage()
                if (i >= slotCount) { pi++; i = 0; continue }
                if (pages[pi][i] == null && !blocked(pi, i, clockVisible, placed)) break
                i++
            }
            pages[pi][i++] = item
        }
        return pages to placed
    }

    private fun trim(p: Pages, widgets: List<WidgetItem> = layout.widgets): List<List<HomeItem?>> {
        while (p.size > 1 && p.last().all { it == null } && widgets.none { it.page == p.lastIndex }) {
            p.removeAt(p.lastIndex)
        }
        return p
    }

    private fun editPages(block: (Pages) -> Unit) {
        val p: Pages = layout.pages.map { it.toMutableList() }.toMutableList()
        block(p)
        layout = layout.copy(pages = trim(p))
        saveLayout()
    }

    private fun findFolder(id: String): Triple<Int, Int, HomeItem.Folder>? {
        layout.pages.forEachIndexed { p, page ->
            page.forEachIndexed { i, item ->
                if (item is HomeItem.Folder && item.id == id) return Triple(p, i, item)
            }
        }
        return null
    }

    private fun newId() = "f${System.currentTimeMillis()}_${idSeq++}"

    private fun autoName(keys: List<String>): String {
        val cats = keys.mapNotNull { appMap[it]?.category }.distinct()
        return if (cats.size == 1 && cats[0] != "Other") cats[0] else "Folder"
    }

    fun saveLayout() = repo.saveLayout(layout)

    // =============== building ===============
    private fun defaultLayout(): LayoutState {
        val dock = repo.defaultDockPackages()
            .mapNotNull { p -> apps.firstOrNull { it.packageName == p }?.key }
            .distinct().take(4)
        val items = visibleApps.filter { it.key !in dock }.map { HomeItem.App(it.key) }
        val clock = ClockState()
        val (pages, _) = relayout(items, clock.visible, emptyList())
        return LayoutState(pages, dock, clock, apps.map { it.key }.toSet())
    }

    private fun reconcile(l: LayoutState): LayoutState {
        val valid = appMap.keys
        fun clean(item: HomeItem?): HomeItem? = when (item) {
            null -> null
            is HomeItem.App -> item.takeIf { it.key in valid }
            is HomeItem.Folder -> item.apps.filter { it in valid }.let { a ->
                when (a.size) {
                    0 -> null
                    1 -> HomeItem.App(a[0])
                    else -> item.copy(apps = a)
                }
            }
        }
        val pages: Pages = l.pages.map { p -> MutableList(slotCount) { i -> clean(p.getOrNull(i)) } }.toMutableList()
        if (pages.isEmpty()) pages += emptyPage()
        val lastWidgetPage = l.widgets.maxOfOrNull { it.page } ?: 0
        while (pages.size <= lastWidgetPage) pages += emptyPage()
        apps.filter { it.key !in l.known && it.key !in settings.hidden }
            .forEach { place(pages, HomeItem.App(it.key), l.clock.visible, l.widgets) }
        return l.copy(
            pages = trim(pages, l.widgets),
            dock = l.dock.filter { it in valid },
            known = valid.toSet(),
        )
    }

    /** Standard mode: every visible app lives on the home screen or dock. */
    private fun ensureAllOnHome() {
        val onHome = layout.pages.flatten().flatMap {
            when (it) {
                is HomeItem.App -> listOf(it.key)
                is HomeItem.Folder -> it.apps
                null -> emptyList()
            }
        }.toSet() + layout.dock
        val missing = visibleApps.filter { it.key !in onHome }
        if (missing.isEmpty()) return
        editPages { p -> missing.forEach { place(p, HomeItem.App(it.key)) } }
    }

    // =============== drag & drop ===============
    fun startDrag(page: Int, index: Int, item: HomeItem, pointer: Offset, grab: Offset) {
        drag = DragState(page, index, item, pointer, pointer, grab)
        menu = null
    }

    fun dragBy(delta: Offset) {
        val d = drag ?: return
        val moved = d.copy(pointer = d.pointer + delta)
        drag = moved
        // Finger started moving → close the menu and switch to dragging
        if (menu != null && (moved.pointer - moved.start).getDistance() > 24f) menu = null
    }

    fun cancelDrag() {
        drag = null
        editPages { }   // removes a temporary empty page, if any
    }

    fun addEmptyPage() {
        layout = layout.copy(pages = layout.pages + listOf(emptyPage()))
    }

    fun endDrag(targetPage: Int?, targetIndex: Int?, toDock: Boolean) {
        val d = drag ?: return
        drag = null
        if (toDock) {
            val item = d.item
            when {
                item !is HomeItem.App -> toast("Folders can't go in the dock")
                layout.dock.size >= 5 -> toast("Dock is full")
                else -> {
                    layout = layout.copy(dock = layout.dock + (item as HomeItem.App).key)
                    editPages { it[d.page][d.index] = null }
                    return
                }
            }
            editPages { }
            return
        }
        if (targetPage == null || targetIndex == null ||
            (targetPage == d.page && targetIndex == d.index) ||
            layout.widgets.any { it.covers(targetPage, targetIndex) }
        ) {
            editPages { }
            return
        }
        editPages { p ->
            while (p.size <= targetPage) p += emptyPage()
            val src = p[d.page][d.index] ?: return@editPages
            val dst = p[targetPage][targetIndex]
            when {
                dst == null -> {
                    p[targetPage][targetIndex] = src
                    p[d.page][d.index] = null
                }
                src is HomeItem.App && dst is HomeItem.App -> {
                    val keys = listOf(dst.key, src.key)
                    p[targetPage][targetIndex] = HomeItem.Folder(newId(), autoName(keys), keys)
                    p[d.page][d.index] = null
                }
                src is HomeItem.App && dst is HomeItem.Folder -> {
                    p[targetPage][targetIndex] = dst.copy(apps = dst.apps + src.key)
                    p[d.page][d.index] = null
                }
                else -> {
                    p[targetPage][targetIndex] = src
                    p[d.page][d.index] = dst
                }
            }
        }
    }

    // =============== widgets ===============
    fun addWidget(id: Int, spanX: Int, spanY: Int) {
        val sx = spanX.coerceIn(1, settings.cols)
        val sy = spanY.coerceIn(1, settings.rows)
        val p: Pages = layout.pages.map { it.toMutableList() }.toMutableList()
        val (pg, c, r) = findRect(p, layout.widgets, sx, sy, currentPage.coerceAtLeast(0), layout.clock.visible)
        layout = layout.copy(pages = p, widgets = layout.widgets + WidgetItem(id, pg, c, r, sx, sy))
        saveLayout()
        widgetPickerOpen = false
        editMode = false
        scrollTo = pg
    }

    fun removeWidget(id: Int) {
        layout = layout.copy(widgets = layout.widgets.filter { it.appWidgetId != id })
        widgetBounds.remove(id)
        editPages { }
        menu = null
    }

    fun resizeWidget(id: Int, dx: Int, dy: Int) {
        val w = layout.widgets.firstOrNull { it.appWidgetId == id } ?: return
        val nx = w.spanX + dx
        val ny = w.spanY + dy
        if (nx < 1 || ny < 1 || w.col + nx > settings.cols || w.row + ny > settings.rows) {
            toast("No room to resize")
            return
        }
        val others = layout.widgets.filter { it.appWidgetId != id }
        val page = layout.pages[w.page]
        val free = (w.row until w.row + ny).all { r ->
            (w.col until w.col + nx).all { c ->
                val idx = r * settings.cols + c
                page[idx] == null && others.none { it.covers(w.page, idx) }
            }
        }
        if (!free) {
            toast("Move icons out of the way first")
            return
        }
        val updated = w.copy(spanX = nx, spanY = ny)
        layout = layout.copy(widgets = others + updated)
        saveLayout()
        menu = MenuTarget.HomeWidget(updated)
    }

    fun moveWidgetToNextPage(id: Int) {
        val w = layout.widgets.firstOrNull { it.appWidgetId == id } ?: return
        val others = layout.widgets.filter { it.appWidgetId != id }
        val p: Pages = layout.pages.map { it.toMutableList() }.toMutableList()
        val (pg, c, r) = findRect(p, others, w.spanX, w.spanY, w.page + 1, layout.clock.visible)
        layout = layout.copy(pages = p, widgets = others + w.copy(page = pg, col = c, row = r))
        editPages { }
        menu = null
        scrollTo = pg
    }

    // =============== menu actions ===============
    fun showMenuFor(page: Int, index: Int) {
        menu = when (val item = layout.pages.getOrNull(page)?.getOrNull(index)) {
            is HomeItem.App -> appMap[item.key]?.let { MenuTarget.HomeApp(page, index, it) }
            is HomeItem.Folder -> MenuTarget.HomeFolder(page, index, item)
            null -> null
        }
    }

    fun removeFromHome(page: Int, index: Int) {
        editPages { it[page][index] = null }
        menu = null
    }

    fun moveToNextPage(page: Int, index: Int) {
        editPages { p ->
            val item = p[page][index] ?: return@editPages
            p[page][index] = null
            place(p, item, startPage = page + 1)
        }
        menu = null
        scrollTo = page + 1
    }

    private fun isOnHome(key: String) = layout.pages.flatten().any {
        (it is HomeItem.App && it.key == key) || (it is HomeItem.Folder && key in it.apps)
    }

    fun addToHome(key: String) {
        if (!isOnHome(key) && key !in layout.dock) {
            editPages { place(it, HomeItem.App(key)) }
            toast("Added to home screen")
        }
        menu = null
    }

    fun addToDock(page: Int, index: Int, key: String) {
        if (layout.dock.size >= 5) {
            toast("Dock is full")
            menu = null
            return
        }
        layout = layout.copy(dock = layout.dock + key)
        removeFromHome(page, index)
    }

    fun removeFromDock(index: Int) {
        val key = layout.dock.getOrNull(index) ?: return
        layout = layout.copy(dock = layout.dock.filterIndexed { i, _ -> i != index })
        addToHome(key)
    }

    fun ungroup(page: Int, index: Int) {
        editPages { p ->
            val f = p[page][index] as? HomeItem.Folder ?: return@editPages
            p[page][index] = HomeItem.App(f.apps.first())
            f.apps.drop(1).forEach { place(p, HomeItem.App(it)) }
        }
        menu = null
    }

    fun removeFromFolder(folderId: String, key: String) {
        val (pg, idx, f) = findFolder(folderId) ?: return
        editPages { p ->
            val rest = f.apps - key
            p[pg][idx] = if (rest.size == 1) HomeItem.App(rest[0]) else f.copy(apps = rest)
            place(p, HomeItem.App(key))
        }
        if (findFolder(folderId) == null) openFolderId = null
        menu = null
    }

    fun renameFolder(id: String, name: String) {
        val (pg, idx, f) = findFolder(id) ?: return
        editPages { it[pg][idx] = f.copy(name = name.trim().ifBlank { f.name }) }
    }

    /** Move to private space. */
    fun hide(key: String) = hideAll(listOf(key))

    fun hideAll(keys: Collection<String>) {
        val set = keys.toSet()
        if (set.isEmpty()) return
        updateSettings(settings.copy(hidden = settings.hidden + set))
        layout = layout.copy(dock = layout.dock - set)
        editPages { p ->
            p.forEach { page ->
                page.indices.forEach { i ->
                    when (val item = page[i]) {
                        is HomeItem.App -> if (item.key in set) page[i] = null
                        is HomeItem.Folder -> {
                            val r = item.apps - set
                            page[i] = when (r.size) {
                                item.apps.size -> item
                                0 -> null
                                1 -> HomeItem.App(r[0])
                                else -> item.copy(apps = r)
                            }
                        }
                        null -> {}
                    }
                }
            }
        }
        menu = null
        toast(if (set.size == 1) "Moved to private space" else "${set.size} apps moved to private space")
    }

    // =============== multi-select ===============
    private fun HomeItem.selKey(): String = when (this) {
        is HomeItem.App -> key
        is HomeItem.Folder -> id
    }

    fun isSelected(item: HomeItem) = item.selKey() in selected

    fun toggleSelect(item: HomeItem) {
        val k = item.selKey()
        selected = if (k in selected) selected - k else selected + k
    }

    fun startSelection(item: HomeItem) {
        menu = null
        editMode = true
        selected = setOf(item.selKey())
    }

    fun exitEditMode() {
        editMode = false
        selected = emptySet()
    }

    private fun selectedAppKeys(): List<String> =
        layout.pages.flatten().filterNotNull()
            .filter { it.selKey() in selected }
            .flatMap {
                when (it) {
                    is HomeItem.App -> listOf(it.key)
                    is HomeItem.Folder -> it.apps
                }
            }
            .distinct()

    fun groupSelected() {
        val keys = selectedAppKeys()
        if (keys.size < 2) {
            toast("Select at least 2 apps")
            return
        }
        editPages { p ->
            var first: Pair<Int, Int>? = null
            p.forEachIndexed { pi, page ->
                page.indices.forEach { i ->
                    val item = page[i]
                    if (item != null && item.selKey() in selected) {
                        if (first == null) first = pi to i
                        page[i] = null
                    }
                }
            }
            val (pg, idx) = first ?: return@editPages
            p[pg][idx] = HomeItem.Folder(newId(), autoName(keys), keys)
        }
        exitEditMode()
    }

    fun hideSelected() {
        val keys = selectedAppKeys()
        exitEditMode()
        hideAll(keys)
    }

    fun removeSelected() {
        editPages { p ->
            p.forEach { page ->
                page.indices.forEach { i ->
                    val item = page[i]
                    if (item != null && item.selKey() in selected) page[i] = null
                }
            }
        }
        exitEditMode()
    }

    fun uninstallSelected() {
        val list = selectedAppKeys().mapNotNull { appMap[it] }.distinctBy { it.packageName }
        exitEditMode()
        uninstallQueue.clear()
        uninstallQueue.addAll(list)
        uninstallNext()
    }

    private fun uninstallNext() {
        val next = uninstallQueue.removeFirstOrNull() ?: return
        uninstall(next)
    }

    /** Move out of private space. */
    fun unhide(key: String) {
        updateSettings(settings.copy(hidden = settings.hidden - key))
        addToHome(key)
    }

    // =============== clock ===============
    fun setClock(c: ClockState) {
        layout = layout.copy(clock = c)
    }

    fun setClockVisible(visible: Boolean) {
        val (pages, widgets) = relayout(layout.pages.flatten().filterNotNull(), visible, layout.widgets)
        layout = layout.copy(clock = layout.clock.copy(visible = visible), pages = pages, widgets = widgets)
        saveLayout()
    }

    // =============== settings ===============
    fun updateSettings(s: LauncherSettings) {
        val old = settings
        settings = s
        repo.saveSettings(s)
        if (s.cols != old.cols || s.rows != old.rows) {
            val (pages, widgets) = relayout(layout.pages.flatten().filterNotNull(), layout.clock.visible, layout.widgets)
            layout = layout.copy(pages = pages, widgets = widgets)
            saveLayout()
        }
        if (s.iconPack != old.iconPack) refresh()
        if (!s.drawerMode) ensureAllOnHome()
        suggestions = repo.suggestions(visibleApps)
    }

    fun organizeByCategory() {
        val all = layout.pages.flatten().filterNotNull()
        val folders = all.filterIsInstance<HomeItem.Folder>()
        val grouped = all.filterIsInstance<HomeItem.App>().map { it.key }
            .groupBy { appMap[it]?.category ?: "Other" }.toSortedMap()
        val items = folders + grouped.flatMap { (cat, keys) ->
            if (keys.size >= 2) listOf(HomeItem.Folder(newId(), cat, keys)) else keys.map { HomeItem.App(it) }
        }
        val (pages, widgets) = relayout(items, layout.clock.visible, layout.widgets)
        layout = layout.copy(pages = pages, widgets = widgets)
        saveLayout()
        toast("Home screen organized by category")
    }

    // =============== launching & system ===============
    fun launch(app: AppInfo) {
        runCatching { repo.launcherApps.startMainActivity(app.component, app.user, null, null) }
            .onFailure { toast("Can't open ${app.label}") }
        repo.recordLaunch(app.key)
        closeAll()
    }

    fun launchShortcut(s: ShortcutInfo) {
        runCatching { repo.launcherApps.startShortcut(s, null, null) }
            .onFailure { toast("Can't open shortcut") }
        closeAll()
    }

    fun appInfo(app: AppInfo) {
        runCatching { repo.launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
        menu = null
    }

    fun uninstall(app: AppInfo) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        menu = null
    }

    @SuppressLint("WrongConstant")
    private fun statusBar(method: String) {
        runCatching {
            val sb = ctx.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod(method).invoke(sb)
        }
    }

    fun expandNotifications() = statusBar("expandNotificationsPanel")
    fun expandQuickSettings() = statusBar("expandSettingsPanel")

    // =============== navigation ===============
    fun goToFirstPage() {
        homeTick++
    }

    fun closeAll() {
        drawerOpen = false; openFolderId = null; menu = null; showSettings = false
        editMode = false; widgetPickerOpen = false; privateOpen = false
        selected = emptySet()
    }

    fun onResume() {
        resumeTick++
        suggestions = repo.suggestions(visibleApps)
        if (uninstallQueue.isNotEmpty()) uninstallNext()   // next app in a multi-uninstall
    }

    fun onBack() {
        when {
            menu != null -> menu = null
            widgetPickerOpen -> widgetPickerOpen = false
            privateOpen -> privateOpen = false
            showSettings -> showSettings = false
            openFolderId != null -> openFolderId = null
            drawerOpen -> drawerOpen = false
            selected.isNotEmpty() -> selected = emptySet()
            editMode -> editMode = false
        }
    }

    fun onHomePressed() {
        val anyOpen = menu != null || showSettings || openFolderId != null || drawerOpen ||
            editMode || widgetPickerOpen || privateOpen
        if (anyOpen) closeAll() else homeTick++
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}