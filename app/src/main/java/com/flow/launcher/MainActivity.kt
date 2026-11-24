package com.flow.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import com.flow.launcher.ui.LauncherRoot
import com.flow.launcher.ui.PrivateAuth
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private lateinit var widgets: WidgetController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        widgets = WidgetController(this, vm)
        onBackPressedDispatcher.addCallback(this) { vm.onBack() }
        setContent { LauncherRoot(vm, widgets) { radius -> setWindowBlur(radius) } }
        lifecycleScope.launch {
            snapshotFlow { vm.settings.highRefresh }.collect { applyRefreshRate(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgets.host.startListening() }
    }

    override fun onStop() {
        super.onStop()
        vm.flush()
        runCatching { widgets.host.stopListening() }
        vm.privateOpen = false   // re-lock private space when leaving home
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        super.onPause()
        vm.dismissTransient()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val alreadyHome = hasWindowFocus() &&
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        if (alreadyHome) vm.onHomePressed() else vm.closeAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (widgets.onActivityResult(requestCode, resultCode)) return
        PrivateAuth.onActivityResult(requestCode, resultCode)
    }

    /** Ask for the display's highest refresh rate at the current resolution. */
    @Suppress("DEPRECATION")
    private fun applyRefreshRate(high: Boolean) {
        val d: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay)
            ?: return
        val modeId = if (high) {
            val cur = d.mode
            d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }?.modeId ?: 0
        } else 0
        window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
    }

    private fun setWindowBlur(radius: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(radius)
    }
}
