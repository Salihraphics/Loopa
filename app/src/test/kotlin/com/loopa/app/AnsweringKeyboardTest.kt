package com.loopa.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.loopa.core.model.RoundPhase
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.game.AnsweringView
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * İsim Şehir: with the keyboard open, the focused answer field and the submit button must sit
 * above the keyboard, inside the room screen's real insets handling (safeDrawingPadding).
 * Emulated insets, no real keyboard app.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class)
class AnsweringKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun check(name: String, imeFraction: Float, field: String) {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        val s = ScreenshotTest().state(RoundPhase.ANSWERING)
        compose.setContent {
            LoopaTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    AnsweringView(s, s.round!!, System.currentTimeMillis(), 42_000, emptyMap(), false, false, { _, _ -> }, {}, {}, { _, _, _ -> })
                }
            }
        }
        val decor = compose.activity.window.decorView
        val density = compose.activity.resources.displayMetrics.density
        val heightPx = decor.height
        val imePx = (heightPx * imeFraction).toInt()
        compose.onNodeWithTag("answering_root").assertExists()
        compose.onNodeWithTag("answer_list").performScrollToNode(hasTestTag("answer_$field"))
        compose.onNodeWithTag("answer_$field").performClick()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(decor, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (24 * density).toInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (48 * density).toInt()))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build())
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val keyboardTop = (heightPx - imePx) / density
        val f = compose.onNodeWithTag("answer_$field").getBoundsInRoot()
        val submit = compose.onNodeWithTag("submit_answers").getBoundsInRoot()
        println("[ANSWER-IME] $name: keyboardTop=${keyboardTop}dp field=${f.top.value}..${f.bottom.value} submit.bottom=${submit.bottom.value}")
        assertTrue("$name: answer field hidden by keyboard", f.bottom.value <= keyboardTop)
        assertTrue("$name: answer field above the list top", f.top.value >= 0f)
        assertTrue("$name: submit button hidden by keyboard", submit.bottom.value <= keyboardTop + 0.5f)
        assertTrue("$name: list area too small (${submit.top.value - f.top.value}dp)", f.bottom.value - f.top.value > 30f)
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhoneFirstField() = check("small_first", 0.45f, "isim")

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhoneLastField() = check("small_last", 0.45f, "ulke")

    @Test @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    fun landscape() = check("landscape", 0.5f, "isim")
}
