package com.loopa.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.loopa.core.game.modes.DrawingCodec
import com.loopa.core.game.modes.Stroke
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

/**
 * The 9 game screens, rendered from server snapshots (as get_mode_state returns them). Each test
 * checks what the player in that role sees, which actions are possible and what is sent to the
 * server, and writes a screenshot to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "w400dp-h900dp-xhdpi")
class ModeScreensTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }

    private val now = System.currentTimeMillis()
    private fun iso(off: Long) = Instant.ofEpochMilli(now + off).toString()
    private val names = listOf("Ayşe", "Barış", "Cem", "Deniz")
    private val ids = listOf("u0", "u1", "u2", "u3")

    private fun room(mode: String, me: Int, host: Int = 0) = RoomState(
        serverTime = iso(0), me = Me(ids[me], "player", "active"),
        room = Room("room1", "KPX7A2", ids[host], "in_game", false, RoomSettings(gameMode = mode)),
        players = names.mapIndexed { i, n -> RoomPlayer(ids[i], n, AvatarCatalog.random(Random(i * 5 + 1)).toJson(), isHost = i == host) },
        match = Match("m1", "in_progress", 5, 2, RoomSettings(gameMode = mode),
            scoreboard = names.mapIndexed { i, n -> ScoreEntry(ids[i], n, AvatarCatalog.random(Random(i * 5 + 1)).toJson(), listOf(40, 25, 10, 0)[i], 0) }),
    )

    private fun mode(json: String) = ModeJson.decodeFromString(ModeState.serializer(), json)
    private fun round(id: String, n: Int, phase: String, actor: String?, pub: String, results: String = "null", startOff: Long = -5_000, endOff: Long = 40_000) =
        """{"id":"$id","number":$n,"phase":"$phase","actor_id":${actor?.let { "\"$it\"" } ?: "null"},"public":$pub,"results":$results,
            "started_at":"${iso(startOff)}","deadline_at":"${iso(endOff)}"}"""

    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private val errors = mutableListOf<String>()
    private fun ui(local: List<Stroke> = emptyList(), remote: List<Stroke> = emptyList()) = ModeUi(
        act = { k, p, _ -> sent += k to p }, localStrokes = local, remoteStrokes = remote, showError = { errors += it },
    )

    private data class Shown(val room: RoomState, val mode: ModeState, val ui: ModeUi, val dark: Boolean)
    private val shown = androidx.compose.runtime.mutableStateOf<Shown?>(null)
    private var contentSet = false

    private fun show(name: String, room: RoomState, m: ModeState, ui: ModeUi = ui(), dark: Boolean = false) {
        compose.mainClock.autoAdvance = false
        shown.value = Shown(room, m, ui, dark)
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                val v = shown.value ?: return@setContent
                LoopaTheme(darkTheme = v.dark) {
                    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
                        // A new key per screen, so remembered input state does not leak between shots.
                        androidx.compose.runtime.key(v.mode.round?.id, v.room.me.userId, v.mode.round?.phase) { ModeGameContent(v.room, v.mode, now, v.ui) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun none(tag: String) = assertEquals("$tag should not exist", 0, compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size)
    private fun noText(t: String) = assertEquals("'$t' should not be shown", 0, compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)

    // ---------------------------------------------------------------- Gizli Kelime
    private fun gk(me: String) = mode("""{"server_time":"${iso(0)}","mode":"gizli_kelime","match_id":"m1","public":{"total":5},
        "round":${round("r1", 2, "play", "u0", """{"category":"Ulaşım","words":1}""")},
        "me":{"secret":${if (me == "u0") """{"word":"Teleferik"}""" else "null"},"solved":false,
              "feed":[{"user_id":"u2","correct":false,"text":"kayak"},{"user_id":"u3","correct":true,"text":null,"points":30}]}}""")

    @Test fun gizliKelimeDescriberSeesTheWord() {
        show("30_gizli_kelime_describer", room("gizli_kelime", 0), gk("u0"))
        compose.onNodeWithTag("gk_word_card").assertIsDisplayed()
        compose.onNodeWithText("Teleferik").assertIsDisplayed()
        none("guess_input")
    }

    @Test fun gizliKelimeGuesserSeesNoWordAndSendsGuesses() {
        show("31_gizli_kelime_guesser", room("gizli_kelime", 1), gk("u1"))
        compose.onNodeWithTag("gk_guess_card").assertIsDisplayed()
        noText("Teleferik")
        compose.onNodeWithText("Sıradaki doğru tahmin: 25 puan").assertIsDisplayed()
        compose.onNodeWithText("bildi! +30", substring = true).assertIsDisplayed()       // someone else's correct guess, text hidden
        compose.onNodeWithTag("guess_input").performTextInput("teleferik")
        compose.onNodeWithTag("guess_input_send").performClick()
        assertEquals("guess", sent.single().first); assertEquals("teleferik", sent.single().second["text"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- Sessiz Sinema
    private fun ss(me: String, phase: String = "play") = mode("""{"server_time":"${iso(0)}","mode":"sessiz_sinema","match_id":"m1","public":{"total":5},
        "round":${round("r1", 1, phase, "u0", """{"category":"Atasözü","words":3}""", if (phase == "reveal") """{"answer":"Damlaya damlaya göl olur","solved":[{"user_id":"u2","points":36}],"actor_points":20}""" else "null")},
        "me":{"secret":${if (me == "u0" || phase != "play") """{"word":"Damlaya damlaya göl olur"}""" else "null"},"feed":[]}}""")

    @Test fun sessizSinemaActorAndGuesser() {
        show("32_sessiz_sinema_actor", room("sessiz_sinema", 0), ss("u0"))
        compose.onNodeWithTag("ss_phrase").assertIsDisplayed()
        compose.onNodeWithText("Konuşmak, ses çıkarmak ve harf göstermek yok!").assertIsDisplayed()
        show("33_sessiz_sinema_guesser", room("sessiz_sinema", 2), ss("u2"))
        none("ss_phrase"); noText("Damlaya")
        compose.onNodeWithText("3 kelime").assertIsDisplayed()
        compose.onNodeWithTag("guess_input").assertIsDisplayed()
    }

    @Test fun sessizSinemaReveal() {
        show("34_sessiz_sinema_reveal", room("sessiz_sinema", 0), ss("u0", "reveal"))
        compose.onNodeWithTag("guess_reveal").assertIsDisplayed()
        compose.onNodeWithText("Damlaya damlaya göl olur").assertIsDisplayed()
        compose.onNodeWithTag("mode_skip").assertIsDisplayed()                          // host
    }

    // ---------------------------------------------------------------- Çiz ve Tahmin Et
    private val sampleStrokes = listOf(
        Stroke(1, 8, points = listOf(200, 600, 300, 400, 400, 600, 200, 600)),
        Stroke(6, 16, points = listOf(500, 300, 700, 300, 700, 500, 500, 500, 500, 300)),
        Stroke(4, 28, points = listOf(820, 160)),
    )
    private fun ct(me: String) = mode("""{"server_time":"${iso(0)}","mode":"ciz_tahmin","match_id":"m1","public":{"total":5},
        "round":${round("r1", 1, "play", "u0", """{"category":"Yerler","words":2,"hint":"_____ ______"}""")},
        "me":{"secret":${if (me == "u0") """{"word":"Deniz feneri"}""" else "null"},"solved":false,"feed":[{"user_id":"u3","correct":false,"text":"ev"}],"stroke_seq":3}}""")

    @Test fun cizTahminDrawerHasToolsGuesserSeesTheDrawing() {
        show("35_ciz_tahmin_drawer", room("ciz_tahmin", 0), ct("u0"), ui(local = sampleStrokes))
        compose.onNodeWithTag("drawing_canvas").assertIsDisplayed()
        compose.onNodeWithTag("drawing_tools").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ct_word").assertIsDisplayed()
        none("ct_hint")
        show("36_ciz_tahmin_guesser", room("ciz_tahmin", 1), ct("u1"), ui(remote = sampleStrokes))
        compose.onNodeWithTag("drawing_view").assertIsDisplayed()
        compose.onNodeWithTag("ct_hint").assertIsDisplayed()
        none("ct_word"); none("drawing_canvas"); noText("Deniz feneri")
    }

    // ---------------------------------------------------------------- Anlat Bakalım
    private fun ab(role: String, team: Int) = mode("""{"server_time":"${iso(0)}","mode":"anlat_bakalim","match_id":"m1",
        "public":{"total":5,"teams":[["u0","u2"],["u1","u3"]],"team_scores":[3,2]},
        "round":${round("r1", 3, "play", "u0", """{"team":0,"passes_left":2,"cards_done":4,"turn_score":2}""")},
        "me":{"my_team":$team,"role":"$role","secret":${if (role != "guesser") """{"word":"Deniz","taboo":["su","mavi","dalga","plaj","tuz"]}""" else "null"},
              "feed":[{"word":"Kahve","result":"correct"},{"word":"Okul","result":"pass"},{"word":"Kar","result":"taboo"}]}}""")

    @Test fun anlatBakalimRoles() {
        show("37_anlat_bakalim_narrator", room("anlat_bakalim", 0), ab("narrator", 0))
        compose.onNodeWithTag("ab_card").assertIsDisplayed()
        compose.onNodeWithText("Pas (2)").assertIsDisplayed()
        compose.onNodeWithTag("ab_correct").performClick()
        assertEquals("correct", sent.last().first)
        none("ab_taboo")
        show("38_anlat_bakalim_guesser", room("anlat_bakalim", 2), ab("guesser", 0))
        none("ab_card"); noText("YASAKLI")
        compose.onNodeWithTag("ab_guesser").assertIsDisplayed()
        show("39_anlat_bakalim_watcher", room("anlat_bakalim", 1), ab("watcher", 1))
        compose.onNodeWithTag("ab_card").assertIsDisplayed()
        compose.onNodeWithTag("ab_taboo").performScrollTo().performClick()
        assertEquals("taboo", sent.last().first)
        none("ab_correct")
    }

    // ---------------------------------------------------------------- Bilgi Arenası
    private fun qa(phase: String, my: Int? = null) = mode("""{"server_time":"${iso(0)}","mode":"bilgi_arenasi","match_id":"m1","public":{"total":5},
        "round":${round("r1", 2, phase, null, """{"question":"Türkiye'nin en yüksek dağı hangisidir?","options":["Erciyes Dağı","Ağrı Dağı","Uludağ","Palandöken"],"category":"Coğrafya","seconds":20}""",
            if (phase == "reveal") """{"answer":1,"explanation":"Ağrı Dağı yaklaşık 5.137 metredir.","answers":[{"user_id":"u0","choice":1,"correct":true,"points":132},{"user_id":"u1","choice":0,"correct":false,"points":0}]}""" else "null")},
        "me":{"my_choice":${my ?: "null"},"answered":["u2"]}}""")

    @Test fun bilgiArenasiAnswerAndReveal() {
        show("40_bilgi_arenasi_question", room("bilgi_arenasi", 0), qa("answer"))
        (0..3).forEach { compose.onNodeWithTag("qa_option_$it").assertIsDisplayed() }
        noText("5.137")
        compose.onNodeWithTag("qa_option_1").performClick()
        assertEquals("answer", sent.single().first); assertEquals("1", sent.single().second["choice"]!!.jsonPrimitive.content)
        show("41_bilgi_arenasi_reveal", room("bilgi_arenasi", 0), qa("reveal", 1))
        compose.onNodeWithTag("qa_reveal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Doğru! +132").assertIsDisplayed()
        sent.clear(); compose.onNodeWithTag("qa_option_2").performClick()
        assertTrue("no answer after the reveal", sent.isEmpty())
    }

    // ---------------------------------------------------------------- İki Doğru Bir Yalan
    @Test fun ikiDogruWriteValidatesBeforeSending() {
        val m = mode("""{"server_time":"${iso(0)}","mode":"iki_dogru_bir_yalan","match_id":"m1","public":{"total":4},
            "round":${round("r1", 1, "write", null, "{}")},"me":{"mine":null,"my_vote":null,"done":["u2"]}}""")
        show("42_iki_dogru_write", room("iki_dogru_bir_yalan", 0), m)
        compose.onNodeWithTag("tl_item_0").performTextInput("Hiç uçağa binmedim")
        compose.onNodeWithTag("tl_item_1").performTextInput("İki kedim var")
        compose.onNodeWithTag("tl_submit").performScrollTo().performClick()
        compose.onNodeWithText("3. ifade çok kısa.").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("tl_item_2").performTextInput("Ankara'da doğdum")
        compose.onNodeWithTag("tl_submit").performScrollTo().performClick()
        compose.onNodeWithText("Hangisinin yalan olduğunu seç.").assertIsDisplayed()
        compose.onNodeWithTag("tl_lie_1").performClick()
        compose.onNodeWithTag("tl_submit").performScrollTo().performClick()
        assertEquals("statements", sent.single().first)
        assertEquals("1", sent.single().second["lie"]!!.jsonPrimitive.content)
    }

    @Test fun ikiDogruVoteAndReveal() {
        val st = """{"statements":["Hiç uçağa binmedim","Üç kedim var","Ankara'da doğdum"]}"""
        val vote = mode("""{"server_time":"${iso(0)}","mode":"iki_dogru_bir_yalan","match_id":"m1","public":{"total":4},
            "round":${round("r2", 2, "vote", "u0", st)},"me":{"my_vote":null,"done":[]}}""")
        show("43_iki_dogru_vote", room("iki_dogru_bir_yalan", 1), vote)
        compose.onNodeWithTag("tl_statement_2").performClick()
        assertEquals("vote", sent.single().first)
        sent.clear()
        show("43b_iki_dogru_author", room("iki_dogru_bir_yalan", 0), vote)        // the author cannot vote
        compose.onNodeWithTag("tl_statement_1").performClick()
        assertTrue(sent.isEmpty())
        val reveal = mode("""{"server_time":"${iso(0)}","mode":"iki_dogru_bir_yalan","match_id":"m1","public":{"total":4},
            "round":${round("r2", 2, "reveal", "u0", st, """{"lie":1,"fooled":2,"author_points":10,"votes":[{"user_id":"u1","choice":1,"correct":true},{"user_id":"u2","choice":0,"correct":false},{"user_id":"u3","choice":2,"correct":false}]}""")},
            "me":{"my_vote":1,"done":[]}}""")
        show("44_iki_dogru_reveal", room("iki_dogru_bir_yalan", 1), reveal)
        compose.onNodeWithText("YALAN").assertIsDisplayed()
        compose.onNodeWithTag("tl_reveal").performScrollTo().assertIsDisplayed()
    }

    // ---------------------------------------------------------------- En Muhtemel Kim?
    @Test fun enMuhtemelKimVoteForOthersOnly() {
        val m = mode("""{"server_time":"${iso(0)}","mode":"en_muhtemel_kim","match_id":"m1","public":{"total":5},
            "round":${round("r1", 1, "vote", null, """{"prompt":"Kim alarmı en çok erteler?"}""")},"me":{"my_vote":null,"voted":["u3"]}}""")
        show("45_en_muhtemel_vote", room("en_muhtemel_kim", 0), m)
        none("ml_vote_u0")
        compose.onNodeWithTag("ml_vote_u2").performClick()
        assertEquals("u2", sent.single().second["target"]!!.jsonPrimitive.content)
        val r = mode("""{"server_time":"${iso(0)}","mode":"en_muhtemel_kim","match_id":"m1","public":{"total":5},
            "round":${round("r1", 1, "reveal", null, """{"prompt":"Kim alarmı en çok erteler?"}""", """{"tops":["u2"],"counts":[{"user_id":"u2","votes":3},{"user_id":"u0","votes":1}],"votes":[{"user_id":"u0","target":"u2"}]}""")},
            "me":{"my_vote":"u2","voted":[]}}""")
        show("46_en_muhtemel_reveal", room("en_muhtemel_kim", 0), r)
        compose.onNodeWithText("Cem seçildi!").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Odayla aynı fikirdesin: +10").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- Kelime Zinciri
    private fun wc(actor: String) = mode("""{"server_time":"${iso(0)}","mode":"kelime_zinciri","match_id":"m1",
        "public":{"total":12,"word":"dağ","letter":"g","lives":{"u0":3,"u1":1,"u2":0,"u3":2},"chain":[{"word":"kalem"},{"word":"mangal","user_id":"u1"},{"word":"dağ","user_id":"u0"}]},
        "round":${round("r1", 4, "turn", actor, """{"word":"dağ","letter":"g","seconds":20}""", endOff = 15_000)},"me":{"history":[]}}""")

    @Test fun kelimeZinciriTurnAndLocalCheck() {
        show("47_kelime_zinciri_my_turn", room("kelime_zinciri", 1), wc("u1"))
        compose.onNodeWithText("G / Ğ").assertIsDisplayed()
        compose.onNodeWithText("elendi").assertIsDisplayed()
        compose.onNodeWithTag("wc_input").performTextInput("elma")
        compose.onNodeWithTag("wc_input_send").performClick()
        assertTrue("wrong letter is caught before sending", sent.isEmpty())
        assertEquals("Kelime G / Ğ ile başlamalı.", errors.single())
        compose.onNodeWithTag("wc_input").performTextInput("gemi")
        compose.onNodeWithTag("wc_input_send").performClick()
        assertEquals("word", sent.single().first)
        show("48_kelime_zinciri_waiting", room("kelime_zinciri", 2), wc("u1"))
        none("wc_input")
        compose.onNodeWithText("Sıra: Barış").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- Çizim Telefonu
    @Test fun cizimTelefonuStepsAndShowcase() {
        val draw = mode("""{"server_time":"${iso(0)}","mode":"cizim_telefonu","match_id":"m1","public":{"total":5},
            "round":${round("r1", 1, "work", null, """{"step":1,"steps":4,"kind":"draw"}""")},
            "me":{"chain":0,"task":{"kind":"start","step":0,"payload":{"text":"Bisiklete binen bir kedi"}},"submitted":false,"done":[]}}""")
        show("49_cizim_telefonu_draw", room("cizim_telefonu", 0), draw)
        compose.onNodeWithText("Bisiklete binen bir kedi").assertIsDisplayed()
        compose.onNodeWithTag("drawing_canvas").assertIsDisplayed()
        compose.onNodeWithTag("tp_send_drawing").performScrollTo().performClick()
        assertEquals("step", sent.single().first); assertTrue(sent.single().second.containsKey("strokes"))
        sent.clear()
        val strokesJson = DrawingCodec.encodeAll(sampleStrokes).toString()
        val guess = mode("""{"server_time":"${iso(0)}","mode":"cizim_telefonu","match_id":"m1","public":{"total":5},
            "round":${round("r2", 2, "work", null, """{"step":2,"steps":4,"kind":"guess"}""")},
            "me":{"chain":3,"task":{"kind":"draw","step":1,"payload":{"strokes":$strokesJson}},"submitted":false,"done":["u1"]}}""")
        show("50_cizim_telefonu_guess", room("cizim_telefonu", 0), guess)
        compose.onNodeWithTag("drawing_view").assertIsDisplayed()
        noText("Bisiklete")
        compose.onNodeWithTag("tp_guess").performTextInput("kedi bisiklette")
        compose.onNodeWithTag("tp_guess_send").performClick()
        assertEquals("kedi bisiklette", sent.single().second["text"]!!.jsonPrimitive.content)
        val show = mode("""{"server_time":"${iso(0)}","mode":"cizim_telefonu","match_id":"m1","public":{"total":5},
            "round":${round("r5", 5, "showcase", null, """{"steps":4}""", """{"chains":[{"matched":true,"items":[{"kind":"start","payload":{"text":"Bisiklete binen bir kedi"}},
              {"kind":"draw","user_id":"u0","payload":{"strokes":$strokesJson}},{"kind":"guess","user_id":"u1","payload":{"text":"kedi bisiklette"}},
              {"kind":"draw","user_id":"u2","missing":true},{"kind":"guess","user_id":"u3","payload":{"text":"Bisiklete binen bir kedi"}}]}]}""")},"me":{}}""")
        show("51_cizim_telefonu_showcase", room("cizim_telefonu", 0), show)
        compose.onNodeWithTag("tp_showcase").assertIsDisplayed()
        compose.onNodeWithText("Zincir sağlam! Herkese +20").assertIsDisplayed()
        compose.onNodeWithTag("tp_item_3").performScrollTo()
        compose.onNodeWithText("(süre doldu)").assertIsDisplayed()
    }

    @Test fun darkThemeQuiz() {
        show("52_bilgi_arenasi_dark", room("bilgi_arenasi", 0), qa("answer"), dark = true)
        compose.onNodeWithTag("qa_question").assertIsDisplayed()
    }
}
