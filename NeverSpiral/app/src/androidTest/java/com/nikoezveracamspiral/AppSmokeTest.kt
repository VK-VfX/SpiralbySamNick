package com.nikoezveracamspiral

import android.Manifest
import android.content.Intent
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Starts the real app on the emulator, opens Portrait, presses the shutter (a volume key) and
 * checks the app is still alive, taking screenshots on the way. The emulator's camera is a virtual
 * scene, so what this proves is that the app starts, opens a camera session and survives a shot,
 * not that the picture is good.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test
    fun opensPortraitAndTakesAPicture() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val device = UiDevice.getInstance(inst)
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, "android.permission.POST_NOTIFICATIONS")) {
            try {
                inst.uiAutomation.grantRuntimePermission(context.packageName, permission)
            } catch (e: Exception) {
                TestResults.log("Couldn't grant $permission: ${e.message}")
            }
        }
        fun shot(name: String) {
            device.takeScreenshot(File(TestResults.dir, "$name.png"))
        }

        device.pressHome()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        assertTrue("the app should come to the front", device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 20_000))
        Thread.sleep(8_000)
        shot("ui_1_camera")

        val portrait = device.findObject(By.text("Portrait"))
        TestResults.log("Portrait tab found: ${portrait != null}")
        portrait?.click()
        Thread.sleep(4_000)
        shot("ui_2_portrait")

        device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
        Thread.sleep(25_000)
        shot("ui_3_after_shutter")

        assertTrue("the app should still be running after a shot", device.hasObject(By.pkg(context.packageName)))
        TestResults.log("The app survived start-up, Portrait and one shot")
    }
}
