package com.flow.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser

data class IconPackInfo(val packageName: String, val label: String, val icon: ImageBitmap)

object IconPacks {
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme",
    )

    fun installed(ctx: Context): List<IconPackInfo> {
        val pm = ctx.packageManager
        return ACTIONS.flatMap { pm.queryIntentActivities(Intent(it), PackageManager.GET_META_DATA) }
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg ->
                runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    IconPackInfo(
                        pkg,
                        pm.getApplicationLabel(ai).toString(),
                        pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap(),
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    class Pack(private val res: Resources, private val pkg: String, private val map: Map<String, String>) {
        @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
        fun iconFor(cn: ComponentName): Drawable? {
            val name = map[cn.flattenToString()] ?: return null
            val id = res.getIdentifier(name, "drawable", pkg)
            if (id == 0) return null
            return runCatching { res.getDrawable(id, null) }.getOrNull()
        }
    }

    @SuppressLint("DiscouragedApi")
    fun load(ctx: Context, pkg: String): Pack? = runCatching {
        val res = ctx.packageManager.getResourcesForApplication(pkg)
        val map = HashMap<String, String>()
        val xmlId = res.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser = if (xmlId != 0) {
            res.getXml(xmlId)
        } else {
            Xml.newPullParser().apply { setInput(res.assets.open("appfilter.xml"), "UTF-8") }
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val comp = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (comp != null && drawable != null && comp.startsWith("ComponentInfo{")) {
                    val flat = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                    val slash = flat.indexOf('/')
                    if (slash > 0) {
                        val p = flat.substring(0, slash)
                        var c = flat.substring(slash + 1)
                        if (c.startsWith(".")) c = p + c
                        map.putIfAbsent("$p/$c", drawable)
                    }
                }
            }
            event = parser.next()
        }
        Pack(res, pkg, map)
    }.getOrNull()
}
