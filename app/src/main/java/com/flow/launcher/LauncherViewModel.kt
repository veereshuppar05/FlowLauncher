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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var drawerOpen by mutableStateOf(false)
    var openFolderId by mutableStateOf<String?>(null)
    var editMode by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var menu by mutableStateOf<MenuTarget?>(null)

    val visibleApps: List<AppInfo> get() = apps.filter { it.key !in settings.hidden }
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
            val list = withContext(Dispatchers.Default) { repo.loadApps() }
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

    // ---------- layout building ----------
    private fun defaultLayout(): LayoutState {
        val dock = repo.defaultDockPackages()
            .mapNotNull { p -> apps.firstOrNull { it.packageName == p }?.key }
            .distinct().take(4)
        val items = visibleApps.filter { it.key !in dock }.map { HomeItem.App(it.key) }
        val clock = ClockState()
        return LayoutState(flow(items, clock.visible), dock, clock, apps.map { it.key }.toSet())
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
        val size = settings.cols * settings.rows
        val pages = l.pages.map { p -> MutableList(size) { i -> clean(p.getOrNull(i)) } }.toMutableList()
        if (pages.isEmpty()) pages += MutableList<HomeItem?>(size) { null }
        apps.filter { it.key !in l.known && it.key !in settings.hidden }
            .forEach { place(pages, HomeItem.App(it.key), l.clock.visible) }
        return l.copy(pages = trim(pages), dock = l.dock.filter { it in valid }, known = valid.toSet())
    }

    private fun flow(items: List<HomeItem>, clockVisible: Boolean): List<List<HomeItem?>> {
        val size = settings.cols * settings.rows
        val pages = mutableListOf(MutableList<HomeItem?>(size) { null })
        var i = if (clockVisible) settings.cols * 2 else 0
        for (item in items) {
            if (i >= size) {
                pages += MutableList<HomeItem?>(size) { null }
                i = 0
            }
            pages.last()[i++] = item
        }
        return pages
    }

    private fun place(
        p: MutableList<MutableList<HomeItem?>>,
        item: HomeItem,
        clockVisible: Boolean,
        startPage: Int = 0,
    ) {
        val size = settings.cols * settings.rows
        for (pi in startPage until p.size) {
            val from = if (pi == 0 && clockVisible) settings.cols * 2 else 0
            val slot = (from until p[pi].size).firstOrNull { p[pi][it] == null }
            if (slot != null) {
                p[pi][slot] = item
                return
            }
        }
        p += MutableList<HomeItem?>(size) { null }.also { it[0] = item }
    }

    private fun trim(p: MutableList<MutableList<HomeItem?>>): List<List<HomeItem?>> {
        while (p.size > 1 && p.last().all { it == null }) p.removeAt(p.lastIndex)
        return p
    }

    private fun editPages(block: (MutableList<MutableList<HomeItem?>>) -> Unit) {
        val p = layout.pages.map { it.toMutableList() }.toMutableList()
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

    // ---------- drag & drop ----------
    fun startDrag(page: Int, index: Int) {
        drag = DragState(page, index)
        menu = null
    }

    fun dragBy(delta: Offset) {
        drag = drag?.let { it.copy(offset = it.offset + delta) }
    }

    fun cancelDrag() {
        drag = null
    }

    fun endDrag(target: Int) {
        val d = drag ?: return
        drag = null
        if (target == d.index) return
        editPages { p ->
            val page = p[d.page]
            val src = page[d.index] ?: return@editPages
            val dst = page[target]
            when {
                dst == null -> {
                    page[target] = src; page[d.index] = null
                }
                src is HomeItem.App && dst is HomeItem.App -> {
                    val keys = listOf(dst.key, src.key)
                    page[target] = HomeItem.Folder(newId(), autoName(keys), keys)
                    page[d.index] = null
                }
                src is HomeItem.App && dst is HomeItem.Folder -> {
                    page[target] = dst.copy(apps = dst.apps + src.key)
                    page[d.index] = null
                }
                else -> {
                    page[target] = src; page[d.index] = dst
                }
            }
        }
    }

    // ---------- menu actions ----------
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
            place(p, item, layout.clock.visible, page + 1)
        }
        menu = null
    }

    private fun isOnHome(key: String) = layout.pages.flatten().any {
        (it is HomeItem.App && it.key == key) || (it is HomeItem.Folder && key in it.apps)
    }

    fun addToHome(key: String) {
        if (!isOnHome(key) && key !in layout.dock) {
            editPages { place(it, HomeItem.App(key), layout.clock.visible) }
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
            f.apps.drop(1).forEach { place(p, HomeItem.App(it), layout.clock.visible) }
        }
        menu = null
    }

    fun removeFromFolder(folderId: String, key: String) {
        val (pg, idx, f) = findFolder(folderId) ?: return
        editPages { p ->
            val rest = f.apps - key
            p[pg][idx] = if (rest.size == 1) HomeItem.App(rest[0]) else f.copy(apps = rest)
            place(p, HomeItem.App(key), layout.clock.visible)
        }
        if (findFolder(folderId) == null) openFolderId = null
        menu = null
    }

    fun renameFolder(id: String, name: String) {
        val (pg, idx, f) = findFolder(id) ?: return
        editPages { it[pg][idx] = f.copy(name = name.trim().ifBlank { f.name }) }
    }

    fun hide(key: String) {
        updateSettings(settings.copy(hidden = settings.hidden + key))
        layout = layout.copy(dock = layout.dock - key)
        editPages { p ->
            p.forEach { page ->
                page.indices.forEach { i ->
                    when (val item = page[i]) {
                        is HomeItem.App -> if (item.key == key) page[i] = null
                        is HomeItem.Folder -> if (key in item.apps) {
                            val r = item.apps - key
                            page[i] = if (r.size == 1) HomeItem.App(r[0]) else item.copy(apps = r)
                        }
                        null -> {}
                    }
                }
            }
        }
        menu = null
    }

    fun unhide(key: String) {
        updateSettings(settings.copy(hidden = settings.hidden - key))
        addToHome(key)
    }

    // ---------- clock ----------
    fun setClock(c: ClockState) {
        layout = layout.copy(clock = c)
    }

    fun setClockVisible(visible: Boolean) {
        val items = layout.pages.flatten().filterNotNull()
        layout = layout.copy(clock = layout.clock.copy(visible = visible), pages = flow(items, visible))
        saveLayout()
    }

    // ---------- settings & organizing ----------
    fun updateSettings(s: LauncherSettings) {
        val reflow = s.cols != settings.cols || s.rows != settings.rows
        settings = s
        repo.saveSettings(s)
        if (reflow) {
            layout = layout.copy(pages = flow(layout.pages.flatten().filterNotNull(), layout.clock.visible))
            saveLayout()
        }
        if (!s.drawerMode) ensureAllOnHome()
        suggestions = repo.suggestions(visibleApps)
    }

    /** Standard mode: every visible app must be somewhere on the home screen or dock. */
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
        editPages { p -> missing.forEach { place(p, HomeItem.App(it.key), layout.clock.visible) } }
    }

    fun organizeByCategory() {
        val all = layout.pages.flatten().filterNotNull()
        val folders = all.filterIsInstance<HomeItem.Folder>()
        val grouped = all.filterIsInstance<HomeItem.App>().map { it.key }
            .groupBy { appMap[it]?.category ?: "Other" }.toSortedMap()
        val items = folders + grouped.flatMap { (cat, keys) ->
            if (keys.size >= 2) listOf(HomeItem.Folder(newId(), cat, keys)) else keys.map { HomeItem.App(it) }
        }
        layout = layout.copy(pages = flow(items, layout.clock.visible))
        saveLayout()
        toast("Home screen organized by category")
    }

    // ---------- launching & system actions ----------
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
    fun expandNotifications() {
        runCatching {
            val sb = ctx.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }
    }

    // ---------- navigation ----------
    fun closeAll() {
        drawerOpen = false; openFolderId = null; menu = null; showSettings = false; editMode = false
    }

    fun onResume() {
        resumeTick++
        suggestions = repo.suggestions(visibleApps)
    }

    fun onBack() {
        when {
            menu != null -> menu = null
            showSettings -> showSettings = false
            openFolderId != null -> openFolderId = null
            drawerOpen -> drawerOpen = false
            editMode -> editMode = false
        }
    }

    fun onHomePressed() {
        if (menu != null || showSettings || openFolderId != null || drawerOpen || editMode) closeAll()
        else homeTick++
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}