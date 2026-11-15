package com.loopa.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipe
import com.loopa.core.model.ModeJson
import com.loopa.core.model.ModeState
import com.loopa.core.model.RoomState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.game.modes.ModeGameContent
import com.loopa.feature.game.modes.ModeUi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Krallıklar rendered from REAL server snapshots (fixtures kralliklar_*_state/room.json are what
 * get_mode_state / get_room_state returned on the local database: a 4-kingdom game after six
 * turns, and a fresh 20-kingdom game on a 600-tile map). Checks the redesigned screen and that it
 * sends exactly the existing requests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class KralliklarScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun mode(n: Int) = ModeJson.decodeFromString(ModeState.serializer(), res("kralliklar_${n}p_state.json"))
    private fun room(n: Int) = ModeJson.decodeFromString(RoomState.serializer(), res("kralliklar_${n}p_room.json"))
    private val sent = mutableListOf<Pair<String, JsonObject>>()

    private fun show(n: Int = 4) {
        val r = room(n); val m = mode(n)
        compose.setContent {
            LoopaTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
                    ModeGameContent(r, m, System.currentTimeMillis(), ModeUi(act = { k, p, _ -> sent += k to p }))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun mapDescription(): String = compose.onNodeWithTag("kr_map").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    @Test fun mapTabsAndScreenshots() {
        show()
        compose.onNodeWithTag("kr_map").assertIsDisplayed()
        compose.onNodeWithText("Anadolu").assertExists()
        listOf("wood", "iron", "gold", "food").forEach { compose.onNodeWithTag("kr_res_$it").assertIsDisplayed() }
        compose.onNodeWithText("108").assertExists()           // the server's stock, unchanged
        shot("90_kralliklar_4p_map")
        compose.onNodeWithTag("kr_tab_1").performClick()
        compose.onNodeWithText("Ege Beyliği").assertExists()
        shot("91_kralliklar_4p_diplomacy")
        compose.onNodeWithTag("kr_tab_2").performClick()
        compose.onNodeWithTag("kr_journal_turn_6").assertExists()
        shot("92_kralliklar_4p_reports")
    }

    @Test fun zoomPanAndTapSelection() {
        show()
        val before = mapDescription()
        compose.onNodeWithTag("kr_zoom_in").performClick()
        val zoomed = mapDescription()
        assertNotEquals("zoom button changes the zoom", before, zoomed)
        compose.onNodeWithTag("kr_map").performTouchInput { pinch(center - Offset(40f, 0f), center - Offset(200f, 0f), center + Offset(40f, 0f), center + Offset(200f, 0f)) }
        compose.waitForIdle()
        assertNotEquals("pinch zooms", zoomed, mapDescription())
        val p0 = mapDescription()
        compose.onNodeWithTag("kr_map").performTouchInput { swipe(center, center + Offset(150f, 90f), 300) }
        compose.waitForIdle()
        assertNotEquals("drag pans", p0, mapDescription())
        // back to the capital: the panel shows it
        compose.onNodeWithTag("kr_to_capital").performClick()
        compose.onNodeWithTag("kr_tile_info").assertExists()
        compose.onNodeWithText("Başkent · Ova").assertExists()
        // a tap in the middle of the map selects the tile under the finger (the capital is centred)
        compose.onNodeWithTag("kr_dock_close").performClick()
        compose.onNodeWithTag("kr_map").performTouchInput { click(center) }
        compose.onNodeWithText("Başkent · Ova").assertExists()
        shot("93_kralliklar_selected_capital")
    }

    @Test fun createDeleteAndSubmitOrders() {
        show()
        compose.onNodeWithTag("kr_to_capital").performClick()
        // capital: no build options are offered (the server would refuse them), but troops can move
        assertEquals(0, compose.onAllNodesWithTag("kr_build_barracks").fetchSemanticsNodes().size)
        compose.onNodeWithTag("kr_send").performClick()
        compose.onNodeWithTag("kr_hint").assertExists()
        val neighbour = compose.onAllNodes(tagPrefix("kr_neighbour_")).onFirst()
        neighbour.performScrollTo().performClick()
        compose.onNodeWithTag("kr_move_summary").assertExists()
        compose.onNodeWithTag("kr_add_move").performScrollTo().performClick()
        compose.onNodeWithText("Emirler (2)").assertExists()            // the server's order + the new one
        shot("94_kralliklar_new_order")
        compose.onNodeWithTag("kr_orders_toggle").performClick()
        compose.onNodeWithTag("kr_order_move_1").assertExists()
        compose.onNodeWithTag("kr_order_move_1_delete").performClick()
        compose.onNodeWithText("Emirler (1)").assertExists()
        compose.onNodeWithTag("kr_order_move_0_delete").performClick()
        compose.onNodeWithText("Emirler (0)").assertExists()
        // a changed order list must be sent again; the request has the same shape as before
        compose.onNodeWithTag("kr_submit").performClick()
        val (kind, p) = sent.last()
        assertEquals("orders", kind)
        assertEquals(0, (p["moves"] as JsonArray).size)
        assertEquals(0, (p["builds"] as JsonArray).size)
        compose.onNodeWithTag("kr_ready").performClick()
        assertEquals("ready", sent.last().first)
        assertEquals("true", sent.last().second["ready"]!!.jsonPrimitive.content)
    }

    @Test fun buildFromAReportJump() {
        show()
        compose.onNodeWithTag("kr_tab_2").performClick()
        // "Haritada ›" on a won battle jumps to that tile (#48, an own farm without a building)
        compose.onNodeWithTag("kr_journal_turn_2").performScrollTo()
        compose.onAllNodesWithTag("kr_journal_entry").fetchSemanticsNodes().size.let { assertTrue(it >= 5) }
        compose.onAllNodesWithText("4 asker kaldı", substring = true).onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("kr_tile_info").assertExists()
        compose.onNodeWithTag("kr_build_barracks").performScrollTo().performClick()
        compose.onNodeWithText("Bu bölge için inşa emri verdin.").assertExists()
        compose.onNodeWithTag("kr_submit").performClick()
        val p = sent.last().second
        val b = (p["builds"] as JsonArray).first() as JsonObject
        assertEquals("barracks", b["type"]!!.jsonPrimitive.content)
    }

    @Test fun diplomacyAcceptAndTradeOffer() {
        show()
        compose.onNodeWithTag("kr_tab_1").performClick()
        compose.onNodeWithText("Sen alırsın").assertExists()
        compose.onNodeWithText("10 Demir").assertExists()
        compose.onNodeWithText("15 Odun").assertExists()
        compose.onNodeWithTag("kr_accept").performClick()
        assertEquals("respond", sent.last().first)
        assertEquals("true", sent.last().second["accept"]!!.jsonPrimitive.content)
        // a trade offer with the two columns
        compose.onNodeWithTag("kr_offer_2").performScrollTo().performClick()
        compose.onNodeWithTag("kr_offer_type_trade").performClick()
        compose.onNodeWithTag("kr_give_wood_plus").performClick()
        compose.onNodeWithTag("kr_get_iron_plus").performClick(); compose.onNodeWithTag("kr_get_iron_plus").performClick()
        shot("95_kralliklar_trade_offer")
        compose.onNodeWithTag("kr_offer_send").performClick()
        val (kind, p) = sent.last()
        assertEquals("offer", kind)
        assertEquals("trade", p["type"]!!.jsonPrimitive.content)
        assertEquals("5", (p["give"] as JsonObject)["wood"]!!.jsonPrimitive.content)
        assertEquals("10", (p["get"] as JsonObject)["iron"]!!.jsonPrimitive.content)
        // a letter
        compose.onNodeWithTag("kr_letter_2").performScrollTo().performClick()
        compose.onNodeWithTag("kr_letter_body").performTextInput("Selam komşu")
        compose.onNodeWithTag("kr_letter_send").performScrollTo().performClick()
        assertEquals("letter", sent.last().first)
        assertEquals("Selam komşu", sent.last().second["body"]!!.jsonPrimitive.content)
        compose.onNodeWithText("Batıdaki ormana girmezsen ben de doğuya geçmem.").performScrollTo().assertExists()
    }

    /** 600 tiles, 20 kingdoms: how long a full redraw of the map takes on this machine (indicative only). */
    @Test fun bigMapRenders() {
        show(20)
        compose.onNodeWithTag("kr_map").assertIsDisplayed()
        shot("96_kralliklar_20p_map")
        val times = mutableListOf<Long>()
        repeat(12) { k ->
            val t0 = System.nanoTime()
            compose.onNodeWithTag(if (k % 2 == 0) "kr_zoom_out" else "kr_zoom_in").performClick()
            compose.waitForIdle()
            times += (System.nanoTime() - t0) / 1_000_000
        }
        // zoomed out to the whole map: the cheapest level of detail
        repeat(6) { compose.onNodeWithTag("kr_zoom_out").performClick() }
        compose.waitForIdle()
        shot("97_kralliklar_20p_whole_map")
        val avg = times.drop(2).average()
        println("KR_PERF 600 tiles: zoom step + redraw avg ${"%.1f".format(avg)} ms (host JVM, Robolectric)")
        File(out, "kralliklar_perf.txt").writeText("600 tiles, 20 kingdoms: avg ${"%.1f".format(avg)} ms per zoom step + redraw (host JVM/Robolectric, not a device)\n")
        assertTrue("redraw too slow: $avg ms", avg < 1500)
    }
}

private fun tagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { n ->
    n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}
