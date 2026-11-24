package com.flow.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class LauncherRepo(private val ctx: Context) {
    val launcherApps: LauncherApps = ctx.getSystemService(LauncherApps::class.java)
    private val users: UserManager = ctx.getSystemService(UserManager::class.java)
    private val layoutPrefs = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE)
    private val usagePrefs = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val settingsPrefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var callback: LauncherApps.Callback? = null

    /** Icon bitmap size matched to the screen (≈210 px on Edge 50 Fusion). */
    private val iconPx = (ctx.resources.displayMetrics.density * 80).toInt().coerceIn(144, 256)

    fun loadApps(iconPack: String?): List<AppInfo> {
        val pack = iconPack?.let { IconPacks.load(ctx, it) }
        val me = Process.myUserHandle()
        return users.userProfiles.flatMap { user ->
            launcherApps.getActivityList(null, user).map { info ->
                val packIcon = pack?.iconFor(info.componentName)
                val drawable = when {
                    packIcon == null -> info.getBadgedIcon(0)
                    user == me -> packIcon
                    else -> ctx.packageManager.getUserBadgedIcon(packIcon, user)
                }
                val category = ApplicationInfo.getCategoryTitle(ctx, info.applicationInfo.category)
                    ?.toString() ?: "Other"
                AppInfo(
                    key = "${info.componentName.flattenToShortString()}#${users.getSerialNumberForUser(user)}",
                    label = info.label.toString(),
                    packageName = info.componentName.packageName,
                    component = info.componentName,
                    user = user,
                    icon = drawable.toBitmap(iconPx, iconPx).asImageBitmap(),
                    category = category,
                )
            }
        }.filter { it.packageName != ctx.packageName }.sortedBy { it.label.lowercase() }
    }

    /** Serial numbers of profiles that are paused or locked (e.g. work profile turned off). */
    fun unavailableSerials(): Set<Long> = users.userProfiles
        .filter { runCatching { users.isQuietModeEnabled(it) || !users.isUserUnlocked(it) }.getOrDefault(false) }
        .map { users.getSerialNumberForUser(it) }
        .toSet()

    fun defaultDockPackages(): List<String> {
        val pm = ctx.packageManager
        return listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        ).mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .filter { it != "android" }
    }

    // ---------- layout ----------
    fun saveLayout(l: LayoutState, sync: Boolean = false) {
        val pages = JSONArray()
        l.pages.forEach { page ->
            val arr = JSONArray()
            page.forEach { item ->
                arr.put(
                    when (item) {
                        null -> JSONObject.NULL
                        is HomeItem.App -> JSONObject().put("t", "a").put("k", item.key)
                        is HomeItem.Folder -> JSONObject().put("t", "f").put("id", item.id)
                            .put("n", item.name).put("a", JSONArray(item.apps))
                    }
                )
            }
            pages.put(arr)
        }
        val widgets = JSONArray()
        l.widgets.forEach { w ->
            widgets.put(
                JSONObject().put("id", w.appWidgetId).put("p", w.page).put("c", w.col)
                    .put("r", w.row).put("x", w.spanX).put("y", w.spanY)
            )
        }
        val clock = JSONObject().put("x", l.clock.x.toDouble()).put("y", l.clock.y.toDouble())
            .put("s", l.clock.scale.toDouble()).put("v", l.clock.visible)
        val json = JSONObject().put("pages", pages).put("dock", JSONArray(l.dock))
            .put("known", JSONArray(l.known.toList())).put("clock", clock).put("widgets", widgets)
        val e = layoutPrefs.edit().putString("layout", json.toString())
        if (sync) e.commit() else e.apply()
    }

    fun loadLayout(): LayoutState? {
        val raw = layoutPrefs.getString("layout", null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val pagesJ = j.getJSONArray("pages")
            val pages = (0 until pagesJ.length()).map { p ->
                val arr = pagesJ.getJSONArray(p)
                (0 until arr.length()).map { i ->
                    val o = arr.optJSONObject(i)
                    when (o?.getString("t")) {
                        "a" -> HomeItem.App(o.getString("k"))
                        "f" -> HomeItem.Folder(o.getString("id"), o.getString("n"), o.getJSONArray("a").strings())
                        else -> null
                    }
                }
            }
            val wj = j.optJSONArray("widgets") ?: JSONArray()
            val widgets = (0 until wj.length()).map { i ->
                val o = wj.getJSONObject(i)
                WidgetItem(o.getInt("id"), o.getInt("p"), o.getInt("c"), o.getInt("r"), o.getInt("x"), o.getInt("y"))
            }
            val c = j.getJSONObject("clock")
            LayoutState(
                pages = pages,
                dock = j.getJSONArray("dock").strings(),
                clock = ClockState(
                    c.getDouble("x").toFloat(), c.getDouble("y").toFloat(),
                    c.getDouble("s").toFloat(), c.getBoolean("v"),
                ),
                known = j.getJSONArray("known").strings().toSet(),
                widgets = widgets,
            )
        }.getOrNull()
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    // ---------- settings ----------
    fun loadSettings() = with(settingsPrefs) {
        LauncherSettings(
            cols = getInt("cols", 4),
            rows = getInt("rows", 6),
            iconScale = getFloat("iconScale", 1f),
            showLabels = getBoolean("labels", true),
            blur = getBoolean("blur", true),
            hidden = getStringSet("hidden", emptySet())!!.toSet(),
            drawerMode = getBoolean("drawer", true),
            iconPack = getString("pack", null),
            iconShape = IconShape.entries.firstOrNull { it.name == getString("shape", null) } ?: IconShape.SQUIRCLE,
            badges = getBoolean("badges", true),
            lockPrivate = getBoolean("lockPrivate", true),
            highRefresh = getBoolean("hz", true),
            gestures = Gesture.entries.associateWith { g ->
                val saved = getString("g_${g.name}", null)
                GestureAction.entries.firstOrNull { it.name == saved } ?: g.default
            },
        )
    }

    fun saveSettings(s: LauncherSettings, sync: Boolean = false) {
        val e = settingsPrefs.edit()
            .putInt("cols", s.cols).putInt("rows", s.rows).putFloat("iconScale", s.iconScale)
            .putBoolean("labels", s.showLabels).putBoolean("blur", s.blur)
            .putStringSet("hidden", s.hidden).putBoolean("drawer", s.drawerMode)
            .putString("pack", s.iconPack).putString("shape", s.iconShape.name)
            .putBoolean("badges", s.badges).putBoolean("lockPrivate", s.lockPrivate)
            .putBoolean("hz", s.highRefresh)
        s.gestures.forEach { (g, a) -> e.putString("g_${g.name}", a.name) }
        if (sync) e.commit() else e.apply()
    }

    // ---------- on-device suggestions ----------
    fun recordLaunch(key: String) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        usagePrefs.edit()
            .putInt("t|$key", usagePrefs.getInt("t|$key", 0) + 1)
            .putInt("h$hour|$key", usagePrefs.getInt("h$hour|$key", 0) + 1)
            .apply()
    }

    fun suggestions(apps: List<AppInfo>, count: Int = 4): List<AppInfo> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return apps.map { a ->
            val nearNow = (-1..1).sumOf { usagePrefs.getInt("h${(hour + it + 24) % 24}|${a.key}", 0) }
            a to nearNow * 3 + usagePrefs.getInt("t|${a.key}", 0)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(count).map { it.first }
    }

    // ---------- shortcuts ----------
    fun shortcuts(app: AppInfo): List<ShortcutInfo> = runCatching {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        launcherApps.getShortcuts(q, app.user).orEmpty().sortedBy { it.rank }.take(4)
    }.getOrDefault(emptyList())

    // ---------- package changes ----------
    fun registerChanges(onChange: () -> Unit) {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange()
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChange()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChange()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
        }
        callback = cb
        launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
    }

    fun unregisterChanges() {
        callback?.let { launcherApps.unregisterCallback(it) }
        callback = null
    }
}