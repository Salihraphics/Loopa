package com.loopa.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.loopa.core.model.ModeJson
import com.loopa.core.model.ModeState
import com.loopa.core.model.RoomState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.game.modes.ModeGameContent
import com.loopa.feature.game.modes.ModeUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Krallıklar with four kingdoms, rendered from a REAL server snapshot: a 4-player game played for six
 * turns on the local database (fixtures kralliklar_4p_*.json are get_mode_state / get_room_state of Ayşe).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class KralliklarScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private val mode = ModeJson.decodeFromString(ModeState.serializer(), res("kralliklar_4p_state.json"))
    private val room = ModeJson.decodeFromString(RoomState.serializer(), res("kralliklar_4p_room.json"))

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun fourKingdomsMapDiplomacyReports() {
        compose.setContent {
            LoopaTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { ModeGameContent(room, mode, System.currentTimeMillis(), ModeUi()) }
            }
        }
        compose.onNodeWithTag("kr_map").assertIsDisplayed()
        shot("90_kralliklar_4p_map")
        compose.onNodeWithTag("kr_tab_1").performClick()
        compose.onNodeWithText("Ege Beyliği").assertExists()
        shot("91_kralliklar_4p_diplomacy")
        compose.onNodeWithTag("kr_tab_2").performClick()
        shot("92_kralliklar_4p_reports")
    }
}
