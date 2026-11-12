package com.nikoezveracamspiral

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeverSpiralApp()
        }
    }
}

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

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
        hasPermissions = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) launcher.launch(REQUIRED_PERMISSIONS)
    }

    Box(modifier = Modifier.fillMaxSize().background(CameraTheme.BACKGROUND)) {
        if (hasPermissions) {
            val controller = remember { CameraController(context) }
            CameraScreen(controller)
        } else {
            PermissionRequestScreen(onRequest = { launcher.launch(REQUIRED_PERMISSIONS) })
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
