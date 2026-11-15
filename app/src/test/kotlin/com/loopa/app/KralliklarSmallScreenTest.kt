package com.loopa.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.loopa.core.model.ModeJson
import com.loopa.core.model.ModeState
import com.loopa.core.model.RoomState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.game.modes.ModeGameContent
import com.loopa.feature.game.modes.ModeUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A small phone (320 x 600 dp): nothing in the header, tabs or bars may run past the screen edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w320dp-h600dp-mdpi")
class KralliklarSmallScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun noOverflowOnASmallPhone() {
        val m = ModeJson.decodeFromString(ModeState.serializer(), javaClass.getResource("/kralliklar_4p_v2_state.json")!!.readText())
        val r = ModeJson.decodeFromString(RoomState.serializer(), javaClass.getResource("/kralliklar_4p_v2_room.json")!!.readText())
        compose.setContent {
            LoopaTheme(darkTheme = false) { Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { ModeGameContent(r, m, System.currentTimeMillis(), ModeUi()) } }
        }
        compose.waitForIdle()
        val width = compose.onNodeWithTag("kr_map").fetchSemanticsNode().boundsInRoot.right
        val tags = listOf("kr_res_wood", "kr_res_iron", "kr_res_gold", "kr_res_food", "kr_tab_0", "kr_tab_1", "kr_tab_2", "kr_submit", "kr_ready", "kr_ready_strip", "kr_kingdom_name")
        tags.forEach { t ->
            val b = compose.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot
            assertTrue("$t runs past the screen ($b, width $width)", b.left >= 0f && b.right <= width + 0.5f)
        }
        compose.onNodeWithTag("kr_map").assertIsDisplayed()
        assertTrue("the map keeps most of the height", compose.onNodeWithTag("kr_map").fetchSemanticsNode().boundsInRoot.height > 220f)
        compose.onNodeWithTag("kr_to_capital").performClick()
        compose.onNodeWithTag("kr_tile_info").assertIsDisplayed()
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.let { File(it, "98_kralliklar_small_phone.png").outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        assertTrue("the map still shows with the tile panel open", compose.onNodeWithTag("kr_map").fetchSemanticsNode().boundsInRoot.height > 120f)
    }
}
