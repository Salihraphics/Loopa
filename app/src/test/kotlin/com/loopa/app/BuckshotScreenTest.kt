package com.loopa.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.loopa.core.model.Match
import com.loopa.core.model.Me
import com.loopa.core.model.ModeJson
import com.loopa.core.model.ModeState
import com.loopa.core.model.Room
import com.loopa.core.model.RoomPlayer
import com.loopa.core.model.RoomSettings
import com.loopa.core.model.RoomState
import com.loopa.core.model.ScoreEntry
import com.loopa.core.ui.avatar.AvatarCatalog
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaTheme
import com.loopa.feature.game.modes.ModeGameContent
import com.loopa.feature.game.modes.ModeUi
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
import java.time.Instant
import kotlin.random.Random

/** Buckshot (portrait, around one table) rendered from server snapshots: what a player sees and may do, and what is sent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h860dp-xhdpi")
class BuckshotScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val now = System.currentTimeMillis()
    private fun iso(off: Long) = Instant.ofEpochMilli(now + off).toString()
    private val names = listOf("Ayşe", "Barış", "Cem")
    private val ids = listOf("u0", "u1", "u2")

    private fun room(me: Int) = RoomState(
        serverTime = iso(0), me = Me(ids[me], "player", "active"),
        room = Room("room1", "KPX7A2", ids[0], "in_game", false, RoomSettings(gameMode = "buckshot")),
        players = names.mapIndexed { i, n -> RoomPlayer(ids[i], n, AvatarCatalog.random(Random(i * 5 + 1)).toJson(), isHost = i == 0) },
        match = Match("m1", "in_progress", 3, 1, RoomSettings(gameMode = "buckshot"),
            scoreboard = names.mapIndexed { i, n -> ScoreEntry(ids[i], n, AvatarCatalog.random(Random(i * 5 + 1)).toJson(), 0, 0) }),
    )

    /** What get_mode_state returns: public part for all, "me" only for that player. */
    private fun state(turn: String, me: String, phase: String = "play", extra: String = "") = ModeJson.decodeFromString(ModeState.serializer(), """
        {"server_time":"${iso(0)}","mode":"buckshot","match_id":"m1","public":{},
         "round":{"id":"r1","number":1,"phase":"$phase","actor_id":"$turn","started_at":"${iso(-5000)}","deadline_at":"${iso(25000)}","results":null,
           "public":{"round":1,"max_hp":3,"order":["u0","u1","u2"],"dir":1,"turn":"$turn","wins":{"u1":0},$extra
             "players":{"u0":{"hp":3,"alive":true,"items":3,"cuffed":false,"saw":false},
                        "u1":{"hp":2,"alive":true,"items":5,"cuffed":true,"saw":false},
                        "u2":{"hp":0,"alive":false,"items":1,"cuffed":false,"saw":false}},
             "mag":{"no":1,"remaining":6,"loaded_live":5,"loaded_blank":3,"live_seen":1,"blank_seen":1,"inverted":0},
             "log":[{"e":"reload","live":5,"blank":3},{"e":"shot","u":"u0","t":"u2","live":true,"dmg":1,"shell":1},
                    {"e":"item","u":"u1","item":"beer","live":false,"shell":2},{"e":"out","u":"u2"}]}},
         "me":${if (me == "u0") """{"inv":["saw","handcuffs","magnifier"],"known":[{"ahead":0,"shell":3,"live":true}]}""" else """{"inv":["beer","beer","phone","inverter","remote"],"known":[]}"""}}
    """.trimIndent())

    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private fun ui() = ModeUi(act = { k, p, _ -> sent += k to p })

    private fun show(name: String, room: RoomState, m: ModeState) {
        compose.setContent {
            LoopaTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Loopa.colors.background)) { ModeGameContent(room, m, now, ui()) }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun myTurnShowsTheTableOwnItemsAndPrivateKnowledgeButNoOdds() {
        show("80_buckshot_my_turn", room(0), state("u0", "u0"))
        compose.onNodeWithTag("bs_table").assertIsDisplayed()
        compose.onNodeWithTag("bs_seat_u1").assertExists()              // rivals around the table
        compose.onNodeWithTag("bs_seat_u2").assertExists()
        compose.onNodeWithText("ELENDİ", substring = true).assertExists()  // the eliminated one stays at the table
        // own items lie in front of the player as objects; rivals' items are crates (never named)
        compose.onNodeWithTag("bs_item_saw").assertExists()
        compose.onNodeWithTag("bs_item_handcuffs").assertExists()
        listOf("Telsiz", "Çevirici", "Kumanda").forEach { assertEquals(it, 0, compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size) }
        // facts only: loaded counts, shells left, what this player learnt — and no computed odds
        compose.onNodeWithText("Yüklenen: 5 dolu · 3 boş").assertExists()
        compose.onNodeWithText("Kalan mermi: 6").assertExists()
        compose.onNodeWithText("Bildiğin: 3. mermi DOLU").assertExists()
        assertEquals("no percentages anywhere", 0, compose.onAllNodesWithText("%", substring = true).fetchSemanticsNodes()
            .count { n -> n.config.toString().let { !it.contains("can") } })
        assertEquals(0, compose.onAllNodesWithText("tahmin", substring = true).fetchSemanticsNodes().size)
        // aim at a rival by tapping the seat, then fire
        compose.onNodeWithTag("bs_seat_u1").performClick()
        compose.onNodeWithTag("bs_fire").assertIsDisplayed().performClick()
        val (kind, p) = sent.last()
        assertEquals("fire", kind)
        assertEquals("u1", p["target"]!!.jsonPrimitive.content)
        assertTrue("every action carries a nonce", p["nonce"]!!.jsonPrimitive.content.length >= 8)
        // the eliminated rival cannot be aimed at
        compose.onNodeWithTag("bs_seat_u2").performClick()
        assertEquals(0, compose.onAllNodesWithTag("bs_fire").fetchSemanticsNodes().size)
        // aiming at yourself
        compose.onNodeWithTag("bs_seat_u0").performClick()
        compose.onNodeWithText("KENDİNE ATEŞ ET").assertExists()
        compose.onNodeWithTag("bs_fire").performClick()
        assertEquals("u0", sent.last().second["target"]!!.jsonPrimitive.content)
    }

    @Test fun itemsAreLiftedThenUsedWithTargetsAndFreshNonces() {
        show("81_buckshot_item_target", room(0), state("u0", "u0"))
        compose.onNodeWithTag("bs_item_saw").performClick()
        compose.onNodeWithText("Testere KULLAN").assertExists()
        compose.onNodeWithTag("bs_use").performClick()
        assertEquals("item" to "saw", sent.last().first to sent.last().second["item"]!!.jsonPrimitive.content)
        val n1 = sent.last().second["nonce"]!!.jsonPrimitive.content
        // handcuffs need a rival chosen at the table
        compose.onNodeWithTag("bs_item_handcuffs").performClick()
        compose.onNodeWithText("Masadan bir rakip seç.", substring = true).assertExists()
        compose.onNodeWithTag("bs_seat_u1").performClick()
        compose.onNodeWithTag("bs_use").performClick()
        val last = sent.last().second
        assertEquals("handcuffs", last["item"]!!.jsonPrimitive.content)
        assertEquals("u1", last["target"]!!.jsonPrimitive.content)
        assertTrue(last["nonce"]!!.jsonPrimitive.content != n1)
    }

    @Test fun waitingPlayerCannotActAndSeesNoForeignKnowledge() {
        show("82_buckshot_waiting", room(1), state("u0", "u1"))
        assertEquals(0, compose.onAllNodesWithTag("bs_known").fetchSemanticsNodes().size)   // Ayşe's magnifier result is not here
        compose.onNodeWithTag("bs_seat_u0").performClick()
        compose.onAllNodesWithTag("bs_item_beer")[0].performClick()      // two cans lie on the table: two objects
        assertEquals(0, compose.onAllNodesWithTag("bs_fire").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("bs_use").fetchSemanticsNodes().size)
        assertTrue("no action when it is not your turn", sent.isEmpty())
    }

    @Test fun roundOverShowsTheMoneyBag() {
        show("83_buckshot_round_over", room(0), state("u0", "u0", phase = "done", extra = """"winner":"u0","bag":100,"""))
        compose.onNodeWithTag("bs_round_over").assertIsDisplayed()
        compose.onNodeWithText("Para çantası: +100 coin").assertExists()
        compose.onNodeWithText("Raundu kazandın!").assertExists()
    }

    @Test fun fourPlayersSitAroundTheTable() {
        val four = RoomState(serverTime = iso(0), me = Me("u0", "player", "active"),
            room = Room("room1", "KPX7A2", "u0", "in_game", false, RoomSettings(gameMode = "buckshot")),
            players = listOf("Ayşe", "Barış", "Cem", "Deniz").mapIndexed { i, n -> RoomPlayer("u$i", n, AvatarCatalog.random(Random(i * 5 + 1)).toJson(), isHost = i == 0) },
            match = Match("m1", "in_progress", 3, 1, RoomSettings(gameMode = "buckshot"),
                scoreboard = listOf("Ayşe", "Barış", "Cem", "Deniz").mapIndexed { i, n -> ScoreEntry("u$i", n, AvatarCatalog.random(Random(i * 5 + 1)).toJson(), 0, 0) }))
        val m = ModeJson.decodeFromString(ModeState.serializer(), """
            {"server_time":"${iso(0)}","mode":"buckshot","match_id":"m1","public":{},
             "round":{"id":"r2","number":2,"phase":"play","actor_id":"u2","started_at":"${iso(-5000)}","deadline_at":"${iso(25000)}","results":null,
               "public":{"round":2,"max_hp":4,"order":["u0","u1","u2","u3"],"dir":-1,"turn":"u2","wins":{"u3":1},
                 "players":{"u0":{"hp":4,"alive":true,"items":6,"cuffed":false,"saw":false},"u1":{"hp":1,"alive":true,"items":2,"cuffed":false,"saw":true},
                            "u2":{"hp":3,"alive":true,"items":8,"cuffed":false,"saw":false},"u3":{"hp":0,"alive":false,"items":4,"cuffed":false,"saw":false}},
                 "mag":{"no":2,"remaining":3,"loaded_live":4,"loaded_blank":4,"live_seen":3,"blank_seen":2,"inverted":1},
                 "log":[{"e":"reload","live":4,"blank":4},{"e":"out","u":"u3"},{"e":"item","u":"u1","item":"saw"}]}},
             "me":{"inv":["beer","cigarettes","magnifier","inverter","phone","medicine"],"known":[]}}""".trimIndent())
        show("84_buckshot_four_players", four, m)
        listOf("u1", "u2", "u3").forEach { compose.onNodeWithTag("bs_seat_$it").assertExists() }
        compose.onNodeWithText("Sıra: Cem").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("bs_fire").fetchSemanticsNodes().size)
    }
}
