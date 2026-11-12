package com.nikoezveracamspiral

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/** Lets the viewfinder claim the volume keys as a shutter button while it is on screen. */
object KeyEvents {
    var onVolumeKey: (() -> Unit)? = null
}

class MainActivity : ComponentActivity() {
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event.repeatCount == 0
        ) {
            val handler = KeyEvents.onVolumeKey
            if (handler != null) {
                handler()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The launch splash (the glyph on white) zooms slightly and fades into the app.
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .scaleX(1.25f)
                .scaleY(1.25f)
                .setDuration(300)
                .withEndAction { provider.remove() }
                .start()
        }
        enableEdgeToEdge()
        setContent {
            NeverSpiralApp()
        }
    }
}

/** Only the camera is needed to take photos. The microphone (video sound) and notifications
 * (the "finishing your photos" notice) are asked for at the same time but never block the app. */
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
private val OPTIONAL_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
private val ALL_PERMISSIONS = REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS

/** Hosts either the permission gate or the live [CameraScreen] -- [CameraController] (and the
 * [CameraEngine] it owns) is only ever created once permissions are already granted, since
 * opening the camera without CAMERA permission would just throw a SecurityException. */
@Composable
private fun NeverSpiralApp() {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermissions = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    LaunchedEffect(Unit) {
        val missing = ALL_PERMISSIONS.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }

    // Opening: the camera fades and scales up into place. Closing (back from the viewfinder):
    // it fades and scales away before the activity actually finishes.
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(hasPermissions) { if (hasPermissions) shown = true }
    BackHandler(enabled = hasPermissions && shown) {
        scope.launch {
            shown = false
            delay(260)
            (context as? Activity)?.finish()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CameraTheme.BACKGROUND)) {
        if (hasPermissions) {
            val controller = remember { CameraController(context) }
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.94f),
                exit = fadeOut(tween(240)) + scaleOut(tween(240), targetScale = 0.94f),
            ) {
                CameraScreen(controller)
            }
        } else {
            PermissionRequestScreen(onRequest = { launcher.launch(ALL_PERMISSIONS) })
        }
    }
}

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "NEVER SPIRAL",
            color = CameraTheme.TEXT_PRIMARY,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Text(
            text = "Needs camera and microphone access to take photos and record video.",
            color = CameraTheme.TEXT_SECONDARY,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = CameraTheme.ACCENT, contentColor = Color.Black),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("GRANT ACCESS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}
