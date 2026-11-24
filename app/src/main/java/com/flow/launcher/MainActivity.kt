package com.flow.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.flow.launcher.ui.LauncherRoot
import com.flow.launcher.ui.PrivateAuth

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
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgets.host.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgets.host.stopListening() }
        vm.privateOpen = false   // re-lock private space when leaving home
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
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

    private fun setWindowBlur(radius: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(radius)
    }
}
