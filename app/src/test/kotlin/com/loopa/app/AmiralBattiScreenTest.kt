package com.loopa.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.loopa.core.model.ModeJson
import com.loopa.core.model.ModeState
import com.loopa.core.model.RoomState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.game.modes.ModeGameContent
import com.loopa.feature.game.modes.ModeUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Amiral Battı on real server snapshots (local Supabase, migration 20261123000001):
 * placement, the turn-following chart, firing, jokers, 2v2 and the end of the game.
 * The screen only sends the existing requests ("place", "fire", "joker"); the server decides.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class AmiralBattiScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private val errors = mutableListOf<String>()
    private val flashes = mutableListOf<String>()

    private fun raw(name: String) = javaClass.getResource("/amiral_ab_${name}.json")!!.readText()
    private fun state(name: String, edit: (JsonObject) -> JsonObject = { it }) =
        ModeJson.decodeFromString(ModeState.serializer(), edit(Json.parseToJsonElement(raw("${name}_state")) as JsonObject).toString())
    private fun room(name: String) = ModeJson.decodeFromString(RoomState.serializer(), raw("${name}_room"))
    private var current by mutableStateOf<ModeState?>(null)

    private fun show(name: String, edit: (JsonObject) -> JsonObject = { it }) {
        val r = room(name)
        current = state(name, edit)
        compose.setContent {
            LoopaTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
                    current?.let { m ->
                        ModeGameContent(r, m, System.currentTimeMillis(), ModeUi(act = { k, p, _ -> sent += k to p }, showError = { errors += it }, flash = { flashes += it }))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.let { File(it, "$name.png").outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
    }

    /** Centre of chart cell (x, y) in the node's own coordinates (same geometry as the screen). */
    private fun cell(tag: String, n: Int, x: Int, y: Int): Offset {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().size
        val s = minOf(b.width, b.height).toFloat(); val m = s * 0.075f; val side = s - m - s * 0.02f; val c = side / n
        return Offset(m + (x + 0.5f) * c, m + (y + 0.5f) * c)
    }
    private fun tapCell(tag: String, n: Int, x: Int, y: Int) { compose.onNodeWithTag(tag).performTouchInput { click(cell(tag, n, x, y)) }; compose.waitForIdle() }
    private fun desc(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun JsonObject.with(path: List<String>, v: JsonElement): JsonObject {
        val k = path.first()
        return JsonObject(this + (k to if (path.size == 1) v else (this[k] as JsonObject).with(path.drop(1), v)))
    }
    private fun jsonOf(s: String) = Json.parseToJsonElement(s)

    @Test fun placementTapDragRotateAndReady() {
        show("place")
        compose.onNodeWithText("FİLO KONUŞLANMASI").assertIsDisplayed()
        compose.onNodeWithTag("ab_confirm").assertIsNotEnabled()
        // out of the map: a carrier from column H does not fit
        tapCell("ab_place", 10, 7, 0)
        assertTrue("an invalid spot is refused", errors.isNotEmpty())
        // carrier, battleship, cruiser, submarine on rows 0, 2, 4, 6 (tap the ship, then a cell)
        for (i in 0..3) { compose.onNodeWithTag("ab_tray_$i").performClick(); tapCell("ab_place", 10, 0, i * 2) }
        compose.onNodeWithTag("ab_confirm").assertIsNotEnabled()             // not all ships yet
        // rotating the battleship would cross the cruiser below it
        tapCell("ab_place", 10, 1, 2)
        val before = errors.size
        compose.onNodeWithTag("ab_rotate").performClick()
        assertTrue("rotation into another ship is refused", errors.size > before)
        // the destroyer is dragged from the tray onto the chart
        val area = compose.onNodeWithTag("ab_place_area").fetchSemanticsNode().boundsInRoot
        val tray = compose.onNodeWithTag("ab_tray_4").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("ab_place").fetchSemanticsNode().boundsInRoot
        val target = cell("ab_place", 10, 7, 9) + board.topLeft - area.topLeft
        compose.onNodeWithTag("ab_place_area").performTouchInput {
            down(tray.center - area.topLeft); moveTo(tray.center - area.topLeft + Offset(0f, -60f)); moveTo(target); up()
        }
        compose.waitForIdle()
        shot("100_amiral_placement")
        compose.onNodeWithTag("ab_confirm").assertIsEnabled().performClick()
        val (kind, p) = sent.last()
        assertEquals("place", kind)
        val fleet = (p["fleet"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf(5, 4, 3, 3, 2), fleet.map { it["len"]!!.jsonPrimitive.content.toInt() })
        val cells = fleet.flatMap { s ->
            val x = s["x"]!!.jsonPrimitive.content.toInt(); val y = s["y"]!!.jsonPrimitive.content.toInt(); val h = s["dir"]!!.jsonPrimitive.content == "h"
            (0 until s["len"]!!.jsonPrimitive.content.toInt()).map { if (h) (x + it) to y else x to (y + it) }
        }
        assertEquals("no overlaps", cells.size, cells.toSet().size)
        assertTrue("all inside the map", cells.all { (x, y) -> x in 0..9 && y in 0..9 })
        assertTrue("the dragged destroyer landed where it was dropped", fleet[4]["y"]!!.jsonPrimitive.content == "9")
    }

    @Test fun myTurnShowsTheEnemyChartAndFires() {
        val st = Json.parseToJsonElement(raw("myturn_state")) as JsonObject
        val pub = (st["round"] as JsonObject)["public"] as JsonObject
        assertTrue("enemy fleets are not in the snapshot", pub["fleets"] is JsonNull)
        show("myturn")
        compose.onNodeWithTag("ab_enemy").assertIsDisplayed()
        compose.onNodeWithTag("ab_mine").assertDoesNotExist()                 // one chart at a time
        compose.onNodeWithText("SIRA SENDE", substring = true).assertExists()
        assertTrue("earlier results are on the chart: ${desc("ab_enemy")}", desc("ab_enemy").contains("3 isabet"))
        compose.onNodeWithTag("ab_fire").assertIsNotEnabled()
        tapCell("ab_enemy", 10, 4, 2)                                         // already hit: cannot be targeted
        compose.onNodeWithTag("ab_fire").assertIsNotEnabled()
        tapCell("ab_enemy", 10, 6, 3)
        compose.onNodeWithText("G4").assertExists()
        shot("101_amiral_my_turn")
        compose.onNodeWithTag("ab_fire").assertIsEnabled().performClick()
        assertEquals("fire", sent.last().first)
        assertEquals("6", sent.last().second["x"]!!.jsonPrimitive.content)
        assertEquals("3", sent.last().second["y"]!!.jsonPrimitive.content)
        // a joker was already used this turn (sonar): the tray is locked
        compose.onNodeWithText("bu tur kullanıldı", substring = true).assertExists()
        compose.onNodeWithTag("ab_jk_torpedo").assertIsNotEnabled()
    }

    @Test fun enemyTurnShowsOwnFleetAndTheSwitchKeepsResults() {
        show("theirturn")
        compose.onNodeWithTag("ab_mine").assertIsDisplayed()
        compose.onNodeWithTag("ab_enemy").assertDoesNotExist()
        compose.onNodeWithText("RAKİP ATEŞ EDİYOR", substring = true).assertExists()
        compose.onNodeWithTag("ab_fire").assertIsNotEnabled()
        assertTrue("enemy hits on my fleet: ${desc("ab_mine")}", desc("ab_mine").contains("3 isabet, 1 ıska"))
        shot("102_amiral_enemy_turn")
        // the turn comes back to me: the chart switches to the enemy waters with my earlier shots
        current = state("myturn")
        compose.waitForIdle()
        compose.onNodeWithTag("ab_enemy").assertIsDisplayed()
        assertTrue(desc("ab_enemy").contains("3 isabet"))
        // and back again
        current = state("theirturn")
        compose.waitForIdle()
        compose.onNodeWithTag("ab_mine").assertIsDisplayed()
        assertTrue(desc("ab_mine").contains("3 isabet, 1 ıska"))
    }

    @Test fun jokersSendTheirTargets() {
        val jokers = jsonOf("""[{"id":72,"t":"torpedo"},{"id":3,"t":"recon"},{"id":81,"t":"double"}]""")
        show("myturn") { it.with(listOf("me", "jokers"), jokers).with(listOf("round", "public", "jk_turn"), JsonNull) }
        // torpedo down column D, from the top
        compose.onNodeWithTag("ab_jk_torpedo").assertIsEnabled().performClick()
        compose.onNodeWithTag("ab_jk_panel").assertIsDisplayed()
        compose.onNodeWithTag("ab_jk_axis_col").performClick()
        tapCell("ab_enemy", 10, 3, 6)
        shot("103_amiral_torpedo_aim")
        compose.onNodeWithTag("ab_jk_use").assertIsEnabled().performClick()
        var (k, p) = sent.last()
        assertEquals("joker", k)
        assertEquals(listOf("72", "col", "3", "false"), listOf(p["id"], p["axis"], p["i"], p["rev"]).map { it!!.jsonPrimitive.content })
        // recon along row 6
        compose.onNodeWithTag("ab_jk_recon").performClick()
        compose.onNodeWithTag("ab_jk_axis_row").performClick()
        tapCell("ab_enemy", 10, 2, 5)
        compose.onNodeWithTag("ab_jk_use").performClick()
        p = sent.last().second
        assertEquals(listOf("3", "row", "5"), listOf(p["id"], p["axis"], p["i"]).map { it!!.jsonPrimitive.content })
        // double: two different free cells
        compose.onNodeWithTag("ab_jk_double").performClick()
        tapCell("ab_enemy", 10, 0, 9)
        compose.onNodeWithTag("ab_jk_use").assertIsNotEnabled()
        tapCell("ab_enemy", 10, 1, 9)
        compose.onNodeWithTag("ab_jk_use").assertIsEnabled().performClick()
        p = sent.last().second
        assertEquals("[[0,9],[1,9]]", p["cells"].toString())
    }

    @Test fun salvoAndSonarTargets() {
        val jokers = jsonOf("""[{"id":91,"t":"salvo"},{"id":92,"t":"sonar"}]""")
        show("myturn") { it.with(listOf("me", "jokers"), jokers).with(listOf("round", "public", "jk_turn"), JsonNull) }
        compose.onNodeWithTag("ab_jk_salvo").performClick()
        compose.onNodeWithTag("ab_jk_dir_h").performClick()
        tapCell("ab_enemy", 10, 8, 0)                                         // would run off the map
        compose.onNodeWithTag("ab_jk_use").assertIsNotEnabled()
        compose.onNodeWithTag("ab_jk_dir_v").performClick()
        compose.onNodeWithTag("ab_jk_use").assertIsEnabled().performClick()
        val p = sent.last().second
        assertEquals(listOf("91", "8", "0", "v"), listOf(p["id"], p["x"], p["y"], p["dir"]).map { it!!.jsonPrimitive.content })
        compose.onNodeWithTag("ab_jk_sonar").performClick()
        tapCell("ab_enemy", 10, 5, 5)
        compose.onNodeWithTag("ab_jk_use").performClick()
        assertEquals(listOf("92", "5", "5"), listOf("id", "x", "y").map { sent.last().second[it]!!.jsonPrimitive.content })
    }

    @Test fun aSinkAwardIsAnnounced() {
        // the moment before A sank the destroyer, then the server's answer
        show("myturn") { s ->
            val log = ((s["round"] as JsonObject)["public"] as JsonObject)["log"] as JsonArray
            s.with(listOf("round", "public", "log"), JsonArray(log.dropLast(4)))
             .with(listOf("me", "jokers"), jsonOf("""[{"id":72,"t":"torpedo"}]"""))
        }
        current = state("myturn")
        compose.waitForIdle()
        assertTrue("my award names the joker: $flashes", flashes.any { it.contains("Joker kazandın: Keşif Uçağı") })
        compose.onNodeWithTag("ab_ticker").assertExists()
    }

    @Test fun twoVersusTwoShowsTheTeamTurn() {
        show("2v2")
        compose.onNodeWithText("TAKIM ARKADAŞIN ATEŞTE", substring = true).assertExists()
        compose.onNodeWithTag("ab_enemy").assertIsDisplayed()                 // our team is firing: enemy waters
        compose.onNodeWithTag("ab_team_0").assertExists(); compose.onNodeWithTag("ab_team_1").assertExists()
        compose.onNodeWithTag("ab_fire").assertIsNotEnabled()
        compose.onNodeWithText("2v2").assertExists()
        shot("104_amiral_2v2")
    }

    @Test fun theEndRevealsBothFleetsOneAtATime() {
        show("over")
        compose.onNodeWithText("ZAFER", substring = true).assertExists()
        compose.onNodeWithTag("ab_enemy").assertIsDisplayed()
        shot("105_amiral_victory")
        compose.onNodeWithTag("ab_view_mine").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("ab_mine").assertIsDisplayed()
        compose.onNodeWithTag("ab_enemy").assertDoesNotExist()
    }
}

/** Tablet: the chart takes the height and a command column sits beside it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w1280dp-h800dp-mdpi")
class AmiralBattiTabletTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun raw(name: String) = javaClass.getResource("/amiral_ab_${name}.json")!!.readText()

    @Test fun wideLayout() {
        val m = ModeJson.decodeFromString(ModeState.serializer(), raw("myturn_state"))
        val r = ModeJson.decodeFromString(RoomState.serializer(), raw("myturn_room"))
        compose.setContent { LoopaTheme(darkTheme = false) { Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { ModeGameContent(r, m, System.currentTimeMillis(), ModeUi()) } } }
        compose.waitForIdle()
        val board = compose.onNodeWithTag("ab_enemy").fetchSemanticsNode().boundsInRoot
        val hud = compose.onNodeWithTag("ab_hud").fetchSemanticsNode().boundsInRoot
        assertTrue("the chart uses the height ($board)", board.height > 500f)
        assertTrue("the command column is beside the chart, not under it", hud.left >= board.right)
        compose.onNodeWithText("SEYİR DEFTERİ").assertExists()
        compose.onNodeWithText("İSTİHBARAT").assertExists()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.let { File(it, "106_amiral_tablet.png").outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
    }
}

/** A small phone: the chart stays the focus and the controls stay on screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w320dp-h640dp-mdpi")
class AmiralBattiSmallScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun raw(name: String) = javaClass.getResource("/amiral_ab_${name}.json")!!.readText()

    @Test fun controlsFit() {
        val m = ModeJson.decodeFromString(ModeState.serializer(), raw("myturn_state"))
        val r = ModeJson.decodeFromString(RoomState.serializer(), raw("myturn_room"))
        compose.setContent { LoopaTheme(darkTheme = false) { Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { ModeGameContent(r, m, System.currentTimeMillis(), ModeUi()) } } }
        compose.waitForIdle()
        val root = compose.onNodeWithTag("ab_root").fetchSemanticsNode().boundsInRoot
        listOf("ab_fire", "ab_jokers", "ab_hud", "ab_enemy").forEach { t ->
            val b = compose.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot
            assertTrue("$t inside the screen ($b in $root)", b.left >= root.left - 0.5f && b.right <= root.right + 0.5f && b.bottom <= root.bottom + 0.5f)
        }
        assertTrue("the chart keeps most of the width", compose.onNodeWithTag("ab_enemy").fetchSemanticsNode().boundsInRoot.width > 200f)
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("build/screenshots").apply { mkdirs() }.let { File(it, "107_amiral_small_phone.png").outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) } }
    }
}
