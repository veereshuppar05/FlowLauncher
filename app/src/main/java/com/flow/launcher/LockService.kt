package com.flow.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: LockService? = null

        fun lock(ctx: Context) {
            val s = instance
            if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                Toast.makeText(ctx, "Turn on Flow Launcher in Accessibility to use double-tap to lock", Toast.LENGTH_LONG).show()
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
