package com.flow.launcher.ui

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flow.launcher.LauncherViewModel
import com.flow.launcher.MenuTarget

object PrivateAuth {
    private const val REQ_CONFIRM = 4401
    private var pending: (() -> Unit)? = null

    @Suppress("DEPRECATION")
    fun unlock(activity: Activity, onSuccess: () -> Unit) {
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) {
            Toast.makeText(activity, "Set a screen lock in phone Settings to protect your private space", Toast.LENGTH_LONG).show()
            onSuccess()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle("Private space")
                .setSubtitle("Unlock to see your private apps")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }
                },
            )
        } else {
            val intent = km.createConfirmDeviceCredentialIntent("Private space", "Unlock to see your private apps")
            if (intent == null) {
                onSuccess()
            } else {
                pending = onSuccess
                activity.startActivityForResult(intent, REQ_CONFIRM)
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_CONFIRM) return false
        if (resultCode == Activity.RESULT_OK) pending?.invoke()
        pending = null
        return true
    }
}

fun openPrivateSpace(activity: Activity, vm: LauncherViewModel) {
    vm.closeAll()
    if (!vm.settings.lockPrivate) vm.privateOpen = true
    else PrivateAuth.unlock(activity) { vm.privateOpen = true }
}

@Composable
fun PrivateSpaceOverlay(vm: LauncherViewModel) {
    val apps = vm.privateApps
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = noRipple(), indication = null) { vm.privateOpen = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.systemBarsPadding().padding(20.dp).fillMaxWidth()
                .glass(RoundedCornerShape(32.dp), 0.18f)
                .clickable(interactionSource = noRipple(), indication = null) {}
                .padding(20.dp),
        ) {
            Text("Private space", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Hidden from home screen, search and suggestions",
                color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            if (apps.isEmpty()) {
                Text(
                    "No private apps yet. Long-press any app and choose \"Move to private space\".",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                )
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(4),
                    Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(apps, key = { it.key }) { app ->
                        AppIcon(app, 52.dp, true, onLongClick = { vm.menu = MenuTarget.PrivateApp(app) }) {
                            vm.launch(app)
                        }
                    }
                }
            }
        }
    }
}
