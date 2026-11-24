package com.flow.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/** Performs system actions: lock, recents, screenshot, power menu. */
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: LockService? = null

        fun perform(ctx: Context, action: Int) {
            val s = instance
            if (s != null) {
                if (!s.performGlobalAction(action)) {
                    Toast.makeText(ctx, "Not supported on this Android version", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(ctx, "Turn on Flow Launcher in Accessibility to use this gesture", Toast.LENGTH_LONG).show()
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
