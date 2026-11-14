package com.loopa.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.loopa.app.navigation.MainTabs
import com.loopa.app.navigation.Tab
import com.loopa.core.ui.theme.LoopaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The room-code field must stay above the soft keyboard (edge-to-edge window, IME insets
 * dispatched like Android does). Runs the real Home tab (join card) inside the real MainTabs scaffold
 * on several screen sizes. This is an emulation: no real keyboard app is involved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class)
class KeyboardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun keyboardKeepsCodeFieldVisible(name: String, imeFraction: Float) {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            LoopaTheme(darkTheme = false) {
                MainTabs(onRoomReady = {}, onPublicRooms = {}, onShop = {}, initialTab = Tab.HOME)
            }
        }
        val decor = compose.activity.window.decorView
        val density = compose.activity.resources.displayMetrics.density
        val heightPx = decor.height
        val statusPx = (24 * density).toInt(); val navPx = (48 * density).toInt()
        val imePx = (heightPx * imeFraction).toInt()

        // like a user: scroll to the field (it starts off-screen in landscape), then tap it
        compose.onNodeWithTag("room_code_field").performScrollTo().performClick()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(decor, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build())
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val keyboardTopDp = (heightPx - imePx) / density
        val field = compose.onNodeWithTag("room_code_field").getBoundsInRoot()
        val join = compose.onNodeWithTag("join_button").getBoundsInRoot()
        saveScreenshot(name)
        println("[KEYBOARD] $name: screen=${heightPx / density}dp keyboardTop=${keyboardTopDp}dp field=${field.top.value}..${field.bottom.value}dp join.bottom=${join.bottom.value}dp")
        assertTrue("$name: code field is hidden by the keyboard (${field.bottom} > $keyboardTopDp dp)", field.bottom.value <= keyboardTopDp)
        assertTrue("$name: code field scrolled off the top", field.top.value >= statusPx / density - 1)
        assertTrue("$name: Join button is hidden by the keyboard", join.bottom.value <= keyboardTopDp)

        // Typing: upper-case, separators dropped; a complete code closes the keyboard (focus cleared)
        compose.onNodeWithTag("room_code_field").performTextInput(" kpx-7a 2 ")
        compose.waitForIdle()
        compose.onNodeWithTag("room_code_field").assertTextContains("KPX7A2")
        compose.onNodeWithTag("room_code_field").assertIsNotFocused()
    }

    private fun saveScreenshot(name: String) {
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        // draw the keyboard area so the screenshot shows what is covered
        File("build/screenshots").mkdirs()
        File("build/screenshots/keyboard_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhone() = keyboardKeepsCodeFieldVisible("small_360x640", 0.45f)

    @Test @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun typicalPhone() = keyboardKeepsCodeFieldVisible("phone_411x891", 0.40f)

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun tablet() = keyboardKeepsCodeFieldVisible("tablet_800x1280", 0.35f)

    @Test @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    fun phoneLandscape() = keyboardKeepsCodeFieldVisible("landscape_891x411", 0.50f)
}
