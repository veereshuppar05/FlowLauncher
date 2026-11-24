package com.flow.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil

/** Widget view that reports a long-press so we can show the widget menu. */
class LauncherWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private var lastW = -1
    private var lastH = -1
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressRunnable = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPressRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN ->
                removeCallbacks(longPressRunnable)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return fired
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return true
    }

    @Suppress("DEPRECATION")
    fun updateSizeDp(w: Int, h: Int) {
        if (w == lastW && h == lastH) return
        lastW = w
        lastH = h
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateAppWidgetSize(Bundle(), listOf(SizeF(w.toFloat(), h.toFloat())))
        } else {
            updateAppWidgetSize(Bundle(), w, h, w, h)
        }
    }
}

class WidgetController(private val activity: Activity, private val vm: LauncherViewModel) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    val host = object : AppWidgetHost(activity.applicationContext, HOST_ID) {
        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?,
        ): AppWidgetHostView = LauncherWidgetHostView(context)
    }
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null

    fun providers(): List<AppWidgetProviderInfo> =
        activity.getSystemService(UserManager::class.java).userProfiles
            .flatMap { manager.getInstalledProvidersForProfile(it) }

    fun createView(ctx: Context, id: Int): LauncherWidgetHostView? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        return host.createView(ctx, id, info) as? LauncherWidgetHostView
    }

    @Suppress("DEPRECATION")
    fun pick(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        pendingInfo = info
        if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrAdd()
        } else {
            activity.startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
                REQ_BIND,
            )
        }
    }

    private fun configureOrAdd() {
        val info = pendingInfo ?: return
        if (info.configure != null) {
            runCatching {
                host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, REQ_CONFIGURE, null)
            }.onFailure { finishAdd() }
        } else {
            finishAdd()
        }
    }

    private fun finishAdd() {
        val info = pendingInfo ?: return
        val (sx, sy) = spans(info)
        vm.addWidget(pendingId, sx, sy)
        pendingId = -1
        pendingInfo = null
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_BIND && requestCode != REQ_CONFIGURE) return false
        if (pendingId == -1) return true
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQ_BIND) configureOrAdd() else finishAdd()
        } else {
            host.deleteAppWidgetId(pendingId)
            pendingId = -1
            pendingInfo = null
        }
        return true
    }

    fun delete(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
    }

    fun spans(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val s = vm.settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceIn(1, s.cols) to info.targetCellHeight.coerceIn(1, s.rows)
        }
        val dm = activity.resources.displayMetrics
        val cellW = dm.widthPixels / s.cols.toFloat()
        val cellH = dm.heightPixels * 0.72f / s.rows
        return ceil(info.minWidth / cellW).toInt().coerceIn(1, s.cols) to
            ceil(info.minHeight / cellH).toInt().coerceIn(1, s.rows)
    }

    companion object {
        const val HOST_ID = 2048
        const val REQ_BIND = 5101
        const val REQ_CONFIGURE = 5102
    }
}
