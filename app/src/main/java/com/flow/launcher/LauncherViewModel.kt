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
    private var emptyRetries = 0
    private var unavailable: Set<Long> = emptySet()

    /** An app key is kept if the app is installed, or belongs to a paused/locked profile. */
    private fun stillValid(key: String): Boolean =
        key in appMap || key.substringAfterLast('#').toLongOrNull()?.let { it in unavailable } == true

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
    var dropHandler: (() -> Unit)? = null
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
            val list = withContext(Dispatchers.Default) {
                runCatching { repo.loadApps(pack) }.getOrDefault(emptyList())
            }
            val base = if (loaded) layout else repo.loadLayout()

            if (list.isEmpty()) {
                // System not ready yet (e.g. just after reboot): never overwrite the saved layout.
                if (base != null) layout = base
                if (emptyRetries++ < 5) {
                    kotlinx.coroutines.delay(1500)
                    refresh()
                }
                return@launch
            }
            emptyRetries = 0

            apps = list
            appMap = list.associateBy { it.key }
            unavailable = repo.unavailableSerials()
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

    /** Never save before the real layout has been loaded (protects against wiping it). */
    fun saveLayout() {
        if (loaded) repo.saveLayout(layout)
    }

    /** Write everything to storage immediately (called when the launcher goes to background). */
    fun flush() {
        if (loaded) repo.saveLayout(layout, sync = true)
        repo.saveSettings(settings, sync = true)
    }

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
        fun clean(item: HomeItem?): HomeItem? = when (item) {
            null -> null
            is HomeItem.App -> item.takeIf { stillValid(it.key) }
            is HomeItem.Folder -> item.apps.filter { stillValid(it) }.let { a ->
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
            dock = l.dock.filter { stillValid(it) },
            known = l.known.filter { stillValid(it) }.toSet() + appMap.keys,
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
        val group = if (editMode && selected.size > 1 && isSelected(item)) {
            listOf(item) + layout.pages.flatten().filterNotNull().filter { it != item && isSelected(it) }
        } else emptyList()
        drag = DragState(page, index, item, pointer, pointer, grab, group)
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

    fun endDrag(targetPage: Int?, targetIndex: Int?, toDock: Boolean, merge: Boolean = false, dockIndex: Int? = null) {
        val d = drag ?: return
        drag = null
        if (d.group.isNotEmpty()) { moveGroup(d, targetPage, targetIndex, toDock); return }
        if (d.page == DOCK_PAGE) { endDockDrag(d, targetPage, targetIndex, toDock, dockIndex, merge); return }
        if (d.page == FOLDER_PAGE) { endFolderDrag(d, targetPage, targetIndex, toDock, dockIndex, merge); return }

        if (toDock) {
            val item = d.item
            when {
                item !is HomeItem.App -> toast("Folders can't go in the dock")
                layout.dock.size >= 5 -> toast("Dock is full")
                else -> {
                    val list = layout.dock.toMutableList()
                    list.add((dockIndex ?: list.size).coerceIn(0, list.size), (item as HomeItem.App).key)
                    layout = layout.copy(dock = list)
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

            // Dropped on the centre of an icon → make / add to folder
            if (merge && src is HomeItem.App && dst != null) {
                p[targetPage][targetIndex] = when (dst) {
                    is HomeItem.App -> {
                        val keys = listOf(dst.key, src.key)
                        HomeItem.Folder(newId(), autoName(keys), keys)
                    }
                    is HomeItem.Folder -> dst.copy(apps = dst.apps + src.key)
                }
                p[d.page][d.index] = null
                return@editPages
            }

            // Otherwise → move apps aside
            moveWithShift(p, d.page, d.index, targetPage, targetIndex)
        }
    }

    fun startDockDrag(index: Int, key: String, pointer: Offset, grab: Offset) {
        drag = DragState(DOCK_PAGE, index, HomeItem.App(key), pointer, pointer, grab)
        menu = null
    }

    private fun endDockDrag(
        d: DragState,
        targetPage: Int?,
        targetIndex: Int?,
        toDock: Boolean,
        dockIndex: Int?,
        merge: Boolean,
    ) {
        val key = (d.item as? HomeItem.App)?.key ?: return

        // Reorder inside the dock
        if (toDock) {
            val dock = layout.dock.toMutableList()
            if (d.index !in dock.indices) return
            dock.removeAt(d.index)
            dock.add((dockIndex ?: dock.size).coerceIn(0, dock.size), key)
            layout = layout.copy(dock = dock)
            saveLayout()
            return
        }

        // Dock → home screen
        if (targetPage == null || targetIndex == null || layout.widgets.any { it.covers(targetPage, targetIndex) }) return
        layout = layout.copy(dock = layout.dock.filterIndexed { i, _ -> i != d.index })
        editPages { p ->
            while (p.size <= targetPage) p += emptyPage()
            val dst = p[targetPage][targetIndex]
            if (merge && dst != null) {
                p[targetPage][targetIndex] = when (dst) {
                    is HomeItem.App -> listOf(dst.key, key).let { HomeItem.Folder(newId(), autoName(it), it) }
                    is HomeItem.Folder -> dst.copy(apps = dst.apps + key)
                }
            } else {
                insertWithShift(p, targetPage, targetIndex, HomeItem.App(key))
            }
        }
    }

    fun startFolderDrag(folderId: String, index: Int, key: String, pointer: Offset, grab: Offset) {
        drag = DragState(FOLDER_PAGE, index, HomeItem.App(key), pointer, pointer, grab, folderId = folderId)
        menu = null
    }

    private fun endFolderDrag(
        d: DragState,
        targetPage: Int?,
        targetIndex: Int?,
        toDock: Boolean,
        dockIndex: Int?,
        merge: Boolean,
    ) {
        val key = (d.item as? HomeItem.App)?.key
        val fid = d.folderId
        openFolderId = null
        if (key == null || fid == null) return

        val onHome = !toDock && targetPage != null && targetIndex != null &&
            layout.widgets.none { it.covers(targetPage, targetIndex) }
        if (!toDock && !onHome) return                     // dropped nowhere: stays in the folder
        if (toDock && layout.dock.size >= 5) {
            toast("Dock is full")
            return
        }

        if (toDock) {
            val list = layout.dock.toMutableList()
            list.add((dockIndex ?: list.size).coerceIn(0, list.size), key)
            layout = layout.copy(dock = list)
        }

        editPages { p ->
            // 1. take the app out of its folder
            var fPage = -1
            var fIdx = -1
            p.forEachIndexed { pi, page ->
                page.forEachIndexed { i, cell ->
                    if (cell is HomeItem.Folder && cell.id == fid) { fPage = pi; fIdx = i }
                }
            }
            if (fPage >= 0) {
                val f = p[fPage][fIdx] as HomeItem.Folder
                val rest = f.apps - key
                p[fPage][fIdx] = when (rest.size) {
                    0 -> null
                    1 -> HomeItem.App(rest[0])          // last app left → folder becomes that app
                    else -> f.copy(apps = rest)
                }
            }
            if (toDock) return@editPages

            // 2. put it on the home screen
            val tp = targetPage ?: return@editPages
            val ti = targetIndex ?: return@editPages
            while (p.size <= tp) p += emptyPage()
            val dst = p[tp][ti]
            if (merge && dst != null) {
                p[tp][ti] = when (dst) {
                    is HomeItem.App -> listOf(dst.key, key).let { HomeItem.Folder(newId(), autoName(it), it) }
                    is HomeItem.Folder -> dst.copy(apps = dst.apps + key)
                }
            } else {
                insertWithShift(p, tp, ti, HomeItem.App(key))
            }
        }
    }

    /** Inserts a new item at a cell, pushing the others forward (overflow goes to the next page). */
    private fun insertWithShift(p: Pages, toPage: Int, toIndex: Int, item: HomeItem) {
        val page = p[toPage]
        val clock = layout.clock.visible
        val cells = (0 until slotCount).filter { i ->
            !layout.widgets.any { it.covers(toPage, i) } &&
                (!(toPage == 0 && clock && i < settings.cols * 2) || i == toIndex)
        }
        val t = cells.indexOf(toIndex)
        if (t < 0) {
            place(p, item)
            return
        }
        var carry: HomeItem? = item
        var k = t
        while (carry != null && k < cells.size) {
            val idx = cells[k]
            val previous = page[idx]
            page[idx] = carry
            carry = previous
            k++
        }
        if (carry != null) overflowToNextPage(p, toPage, carry)
    }

    /**
     * Live preview for dragging: where each icon on [page] would move if the dragged app
     * were dropped at [target]. Value -1 = that icon would be pushed to the next page.
     */
    fun shiftPreview(page: Int, target: Int): Map<Int, Int> {
        val d = drag ?: return emptyMap()
        val slots = layout.pages.getOrNull(page) ?: return emptyMap()
        if (target !in slots.indices) return emptyMap()
        if (layout.widgets.any { it.covers(page, target) }) return emptyMap()
        val samePage = d.page == page
        if (samePage && d.index == target) return emptyMap()
        val clock = layout.clock.visible

        fun usable(i: Int): Boolean {
            if (layout.widgets.any { it.covers(page, i) }) return false
            val inClock = page == 0 && clock && i < settings.cols * 2
            return !inClock || i == target || (samePage && i == d.index)
        }
        val cells = slots.indices.filter { usable(it) }
        val t = cells.indexOf(target)
        if (t < 0) return emptyMap()
        val result = HashMap<Int, Int>()

        if (samePage) {
            val s = cells.indexOf(d.index)
            if (s < 0) return emptyMap()
            val order = cells.toMutableList()
            val moving = order.removeAt(s)
            order.add(t, moving)
            order.forEachIndexed { k, orig ->
                if (orig != d.index && slots[orig] != null && orig != cells[k]) result[orig] = cells[k]
            }
            return result
        }

        if (slots[target] == null) return emptyMap()
        var k = t
        while (k < cells.size) {
            val idx = cells[k]
            if (slots[idx] == null) break
            result[idx] = if (k + 1 < cells.size) cells[k + 1] else -1
            k++
        }
        return result
    }

    /** Puts the item at the target cell and shifts the others along to make room. */
    private fun moveWithShift(p: Pages, fromPage: Int, fromIndex: Int, toPage: Int, toIndex: Int) {
        val item = p[fromPage][fromIndex] ?: return
        val clock = layout.clock.visible
        val widgets = layout.widgets
        val page = p[toPage]

        // Cells that can take part in shifting (skip widget cells and the clock area)
        fun usable(i: Int): Boolean {
            if (widgets.any { it.covers(toPage, i) }) return false
            val inClock = toPage == 0 && clock && i < settings.cols * 2
            return !inClock || i == toIndex || (fromPage == toPage && i == fromIndex)
        }
        val cells = (0 until slotCount).filter { usable(it) }
        val t = cells.indexOf(toIndex)
        if (t < 0) return

        // Same page: classic reorder – everything between old and new spot slides by one
        if (fromPage == toPage) {
            val s = cells.indexOf(fromIndex)
            if (s >= 0) {
                val seq = cells.map { page[it] }.toMutableList()
                val moving = seq.removeAt(s)
                seq.add(t, moving)
                cells.forEachIndexed { k, idx -> page[idx] = seq[k] }
                return
            }
        }

        // Different page: push items forward until a free cell is found
        p[fromPage][fromIndex] = null
        if (page[toIndex] == null) {
            page[toIndex] = item
            return
        }
        var carry: HomeItem? = item
        var k = t
        while (carry != null && k < cells.size) {
            val idx = cells[k]
            val previous = page[idx]
            page[idx] = carry
            carry = previous
            k++
        }
        if (carry != null) overflowToNextPage(p, toPage, carry)
    }

    /** Page full: last item goes to the page right after (or a new page inserted right after). */
    private fun overflowToNextPage(p: Pages, fromPage: Int, item: HomeItem) {
        val next = fromPage + 1
        if (next < p.size) {
            val free = p[next].indices.firstOrNull {
                p[next][it] == null && !blocked(next, it, layout.clock.visible, layout.widgets)
            }
            if (free != null) {
                p[next][free] = item
                return
            }
        }
        // Insert a brand-new page directly after the current one
        p.add(next, emptyPage().also { it[0] = item })
        if (layout.widgets.any { it.page >= next }) {
            layout = layout.copy(
                widgets = layout.widgets.map { if (it.page >= next) it.copy(page = it.page + 1) else it }
            )
        }
        toast("Moved last app to the next page")
    }

    /** Moves all selected items together, filling free cells from the drop point onward. */
    private fun moveGroup(d: DragState, targetPage: Int?, targetIndex: Int?, toDock: Boolean) {
        val sel = d.group.map { it.selKey() }.toSet()

        if (toDock) {
            val apps = d.group.filterIsInstance<HomeItem.App>().map { it.key }.filter { it !in layout.dock }
            val room = (5 - layout.dock.size).coerceAtLeast(0)
            val moving = apps.take(room)
            if (moving.isEmpty()) {
                toast("Dock is full")
                editPages { }
                return
            }
            if (moving.size < d.group.size) toast("${moving.size} app(s) added to dock")
            layout = layout.copy(dock = layout.dock + moving)
            editPages { p ->
                p.forEach { page ->
                    page.indices.forEach { i ->
                        val item = page[i]
                        if (item is HomeItem.App && item.key in moving) page[i] = null
                    }
                }
            }
            selected = emptySet()
            return
        }

        val dropPage = targetPage ?: run {
            editPages { }
            return
        }
        val dropIndex = targetIndex ?: run {
            editPages { }
            return
        }

        editPages { p ->
            // 1. lift every selected item off the grid
            p.forEach { page ->
                page.indices.forEach { i ->
                    val item = page[i]
                    if (item != null && item.selKey() in sel) page[i] = null
                }
            }
            // 2. drop them in order starting at the target cell
            while (p.size <= dropPage) p += emptyPage()
            var pi = dropPage
            var i = dropIndex
            for (item in d.group) {
                while (true) {
                    if (pi >= p.size) p += emptyPage()
                    if (i >= slotCount) { pi++; i = 0; continue }
                    if (p[pi][i] == null && !blocked(pi, i, layout.clock.visible, layout.widgets)) break
                    i++
                }
                p[pi][i++] = item
            }
        }
        selected = emptySet()
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
        saveLayout()
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

    /** Close short-lived UI (long-press menu, drag) when the launcher leaves the screen. */
    fun dismissTransient() {
        menu = null
        suppressClick = false
        if (drag != null) cancelDrag()
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

    companion object {
        const val DOCK_PAGE = -1
        const val FOLDER_PAGE = -2
    }
}
