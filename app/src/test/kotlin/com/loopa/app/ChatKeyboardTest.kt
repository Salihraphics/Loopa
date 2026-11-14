package com.loopa.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.loopa.core.model.Presence
import com.loopa.core.model.UserProfileView
import com.loopa.core.ui.theme.LocalReducedMotion
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.social.ChatContent
import com.loopa.feature.social.ChatState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chat input and send button stay above the keyboard (emulated insets, same wrapper as ChatRoute). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class)
class ChatKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun check(name: String, imeFraction: Float) {
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        val st = ChatState(peer = UserProfileView("u1", "Ayşe", null, 3, relationRaw = "friend", presence = Presence()), loading = false)
        compose.setContent {
            LoopaTheme(darkTheme = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().systemBarsPadding()) { ChatContent(st, "me", {}, {}, {}, {}, {}, {}) }
                }
            }
        }
        val decor = compose.activity.window.decorView
        val density = compose.activity.resources.displayMetrics.density
        val h = decor.height; val ime = (h * imeFraction).toInt()
        compose.onNodeWithTag("chat_input").performClick()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(decor, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (24 * density).toInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (48 * density).toInt()))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val top = (h - ime) / density
        val input = compose.onNodeWithTag("chat_input").getBoundsInRoot()
        val send = compose.onNodeWithTag("chat_send").getBoundsInRoot()
        println("[CHAT-IME] $name keyboardTop=$top input.bottom=${input.bottom.value} send.bottom=${send.bottom.value}")
        assertTrue("$name: input hidden", input.bottom.value <= top + 0.5f)
        assertTrue("$name: send hidden", send.bottom.value <= top + 0.5f)
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi") fun smallPhone() = check("small", 0.45f)
    @Test @Config(qualifiers = "w891dp-h411dp-land-xhdpi") fun landscape() = check("landscape", 0.5f)
}
