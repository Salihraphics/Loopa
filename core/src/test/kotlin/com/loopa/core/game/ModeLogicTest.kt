package com.loopa.core.game

import com.loopa.core.game.modes.AnlatBakalimLogic
import com.loopa.core.game.modes.AnlatView
import com.loopa.core.game.modes.BilgiArenasiLogic
import com.loopa.core.game.modes.ChainPublic
import com.loopa.core.game.modes.CizTahminLogic
import com.loopa.core.game.modes.CizimTelefonuLogic
import com.loopa.core.game.modes.DrawingCodec
import com.loopa.core.game.modes.EnMuhtemelKimLogic
import com.loopa.core.game.modes.GizliKelimeLogic
import com.loopa.core.game.modes.GuessView
import com.loopa.core.game.modes.IkiDogruBirYalanLogic
import com.loopa.core.game.modes.KelimeZinciriLogic
import com.loopa.core.game.modes.KelimeZinciriLogic.Check
import com.loopa.core.game.modes.ModePhaseKind
import com.loopa.core.game.modes.ModePhases
import com.loopa.core.game.modes.ModeTiming
import com.loopa.core.game.modes.QuizResults
import com.loopa.core.game.modes.SessizSinemaLogic
import com.loopa.core.game.modes.Stroke
import com.loopa.core.game.modes.TelephoneResults
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.progressLabel
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModeLogicTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun state(s: String) = json.decodeFromString(ModeState.serializer(), s)
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    // ---------------------------------------------------------------- timing / phases
    @Test fun countdownActiveRevealAndTimer() {
        val t0 = 1_000_000_000_000L
        val s = state("""{"server_time":"${iso(t0)}","mode":"gizli_kelime","match_id":"m","round":{"id":"r","number":2,"phase":"play",
            "started_at":"${iso(t0 + 3_000)}","deadline_at":"${iso(t0 + 63_000)}"},"public":{"total":5}}""")
        assertEquals(ModePhaseKind.COUNTDOWN, ModePhases.kind(s, t0))
        assertEquals(ModePhaseKind.ACTIVE, ModePhases.kind(s, t0 + 3_001))
        assertEquals(60, ModeTiming.seconds(ModeTiming.remainingMs(s.round!!, t0 + 3_000)))
        assertEquals(0.5f, ModeTiming.fractionLeft(s.round!!, t0 + 33_000), 0.001f)
        assertEquals(0L, ModeTiming.remainingMs(s.round!!, t0 + 99_000))
        assertEquals("Tur 2 / 5", s.progressLabel())
        assertEquals(t0 + 63_000, ModeTiming.nextTransitionAt(s))
        val reveal = s.copy(round = s.round!!.copy(phase = "reveal"))
        assertEquals(ModePhaseKind.REVEAL, ModePhases.kind(reveal, t0))
        assertNull(ModeTiming.nextTransitionAt(s.copy(matchStatus = "finished")))
    }

    // ---------------------------------------------------------------- scoring formulas (mirror the server)
    @Test fun guessGameScoring() {
        assertEquals(listOf(30, 25, 20, 15, 10, 10), (0..5).map(GizliKelimeLogic::pointsForNext))
        assertTrue(GizliKelimeLogic.everyoneSolved(2, 2)); assertFalse(GizliKelimeLogic.everyoneSolved(2, 1)); assertFalse(GizliKelimeLogic.everyoneSolved(0, 0))
        assertEquals(40, SessizSinemaLogic.pointsAt(1f)); assertEquals(20, SessizSinemaLogic.pointsAt(0f)); assertEquals(30, SessizSinemaLogic.pointsAt(0.5f))
        assertEquals(50, CizTahminLogic.pointsAt(1f)); assertEquals(10, CizTahminLogic.pointsAt(0f)); assertEquals(10, CizTahminLogic.pointsAt(-3f))
        assertEquals("_ _ _   _ _", CizTahminLogic.hintLabel("___ __"))
        assertEquals("Tek kelime", SessizSinemaLogic.wordCountLabel(1)); assertEquals("3 kelime", SessizSinemaLogic.wordCountLabel(3))
    }

    @Test fun quizScoring() {
        assertEquals(150, BilgiArenasiLogic.pointsAt(1f))
        assertEquals(125, BilgiArenasiLogic.pointsAt(0.5f))
        assertEquals(100, BilgiArenasiLogic.pointsAt(0f))
        assertEquals(100, BilgiArenasiLogic.pointsAt(-1f))
        val r = state("""{"server_time":"x","mode":"bilgi_arenasi","match_id":"m","round":{"id":"r","number":1,"phase":"reveal","started_at":"x",
            "results":{"answer":2,"explanation":"Çünkü…","answers":[{"user_id":"a","choice":2,"correct":true,"points":140}]}}}""").round!!
        val res = r.resultsAs(QuizResults())
        assertEquals(2, res.answer); assertEquals(140, res.answers.single().points)
    }

    // ---------------------------------------------------------------- Kelime Zinciri
    @Test fun chainLettersFollowTurkishFolding() {
        assertEquals("g", KelimeZinciriLogic.lastLetter("dağ"))
        assertEquals("c", KelimeZinciriLogic.lastLetter("Ağaç"))
        assertEquals("i", KelimeZinciriLogic.lastLetter("Kedİ"))
        assertEquals("G / Ğ", KelimeZinciriLogic.letterLabel("g"))
        assertEquals("K", KelimeZinciriLogic.letterLabel("k"))
        assertEquals(Check.OK, KelimeZinciriLogic.precheck("gemi", "g"))
        assertEquals(Check.OK, KelimeZinciriLogic.precheck("Şeker", "s"))                 // ş counts as s
        assertEquals(Check.OK, KelimeZinciriLogic.precheck("çanta", "c"))
    }

    @Test fun chainInvalidInput() {
        assertEquals(Check.EMPTY, KelimeZinciriLogic.precheck("   ", "a"))
        assertEquals(Check.MULTIPLE_WORDS, KelimeZinciriLogic.precheck("iki kelime", "i"))
        assertEquals(Check.NOT_LETTERS, KelimeZinciriLogic.precheck("a1b", "a"))
        assertEquals(Check.TOO_SHORT, KelimeZinciriLogic.precheck("a", "a"))
        assertEquals(Check.WRONG_LETTER, KelimeZinciriLogic.precheck("elma", "k"))
        assertEquals(Check.USED, KelimeZinciriLogic.precheck("KALEM", "k", listOf("kalem")))
        assertEquals("Kelime S / Ş ile başlamalı.", KelimeZinciriLogic.message(Check.WRONG_LETTER, "s"))
        assertNull(KelimeZinciriLogic.message(Check.OK, "s"))
    }

    @Test fun chainPoints() {
        assertEquals(10, KelimeZinciriLogic.points("ev"))
        assertEquals(10, KelimeZinciriLogic.points("elma"))
        assertEquals(11, KelimeZinciriLogic.points("kalem"))
        assertEquals(15, KelimeZinciriLogic.points("buzdolabı"))
        val pub = state("""{"server_time":"x","mode":"kelime_zinciri","match_id":"m","public":{"word":"kalem","letter":"m","lives":{"a":3,"b":1},
            "chain":[{"word":"kalem"}],"total":6}}""").publicAs(ChainPublic())
        assertEquals(1, pub.lives["b"]); assertEquals("m", pub.letter); assertEquals(6, pub.total)
    }

    // ---------------------------------------------------------------- İki Doğru Bir Yalan
    @Test fun statementsValidation() {
        val ok = listOf("Hiç uçağa binmedim", "İki kedim var", "Ankara'da doğdum")
        assertNull(IkiDogruBirYalanLogic.validate(ok, 1))
        assertEquals("Hangisinin yalan olduğunu seç.", IkiDogruBirYalanLogic.validate(ok, null))
        assertEquals("Hangisinin yalan olduğunu seç.", IkiDogruBirYalanLogic.validate(ok, 3))
        assertEquals("Üç ifade yazmalısın.", IkiDogruBirYalanLogic.validate(ok.take(2), 0))
        assertEquals("2. ifade çok kısa.", IkiDogruBirYalanLogic.validate(listOf("abcd", " a ", "efgh"), 0))
        assertEquals("3. ifade en fazla 90 karakter olabilir.", IkiDogruBirYalanLogic.validate(listOf("abcd", "efgh", "x".repeat(91)), 0))
    }

    // ---------------------------------------------------------------- votes / teams
    @Test fun likelyTargetsAndShares() {
        assertEquals(listOf("b", "c"), EnMuhtemelKimLogic.targets(listOf("a", "b", "c"), "a"))
        assertEquals(0.5f, EnMuhtemelKimLogic.share(2, 4)); assertEquals(0f, EnMuhtemelKimLogic.share(1, 0))
    }

    @Test fun teamsAndRoles() {
        val teams = listOf(listOf("a", "c"), listOf("b", "d"))
        assertEquals(0, AnlatBakalimLogic.teamOf("c", teams)); assertEquals(1, AnlatBakalimLogic.teamOf("d", teams)); assertNull(AnlatBakalimLogic.teamOf("x", teams))
        assertEquals(0, AnlatBakalimLogic.leader(listOf(3, 1))); assertEquals(1, AnlatBakalimLogic.leader(listOf(0, 2))); assertNull(AnlatBakalimLogic.leader(listOf(2, 2)))
        assertEquals("Mavi Takım", AnlatBakalimLogic.teamName(0)); assertEquals("İzleyici", AnlatBakalimLogic.teamName(null))
        val guesser = state("""{"server_time":"x","mode":"anlat_bakalim","match_id":"m","me":{"my_team":0,"role":"guesser","secret":null,"feed":[]}}""").meAs(AnlatView())
        assertEquals("guesser", guesser.role); assertNull(guesser.secret)
        val narrator = state("""{"server_time":"x","mode":"anlat_bakalim","match_id":"m","me":{"my_team":0,"role":"narrator","secret":{"word":"Deniz","taboo":["su","mavi","dalga","plaj","tuz"]}}}""").meAs(AnlatView())
        assertEquals(5, narrator.secret!!.taboo.size)
    }

    @Test fun guesserViewHasNoSecret() {
        val v = state("""{"server_time":"x","mode":"gizli_kelime","match_id":"m","me":{"secret":null,"solved":false,
            "feed":[{"user_id":"b","correct":true,"text":null,"points":30},{"user_id":"c","correct":false,"text":"elma"}],"stroke_seq":0}}""").meAs(GuessView())
        assertNull(v.secret); assertNull(v.feed[0].text); assertEquals("elma", v.feed[1].text)
        // Broken / unexpected JSON falls back to defaults instead of crashing the screen.
        val broken = state("""{"server_time":"x","mode":"gizli_kelime","match_id":"m","me":{"feed":"oops"}}""").meAs(GuessView())
        assertEquals(GuessView(), broken)
    }

    // ---------------------------------------------------------------- drawing
    @Test fun drawingQuantizeSimplifyAndRoundTrip() {
        assertEquals(500, DrawingCodec.quantize(150f, 300f)); assertEquals(1000, DrawingCodec.quantize(400f, 300f)); assertEquals(0, DrawingCodec.quantize(-5f, 300f))
        assertEquals(0, DrawingCodec.quantize(10f, 0f))
        val pts = (0 until 100).flatMap { listOf(it, it) }               // 100 points, 1 unit apart
        val simple = DrawingCodec.simplify(pts)
        assertTrue(simple.size < pts.size)
        assertEquals(listOf(0, 0), simple.take(2)); assertEquals(listOf(99, 99), simple.takeLast(2))
        val s = Stroke(color = 5, width = 8, eraser = false, points = listOf(10, 20, 30, 40))
        val back = DrawingCodec.decode(DrawingCodec.encode(s))
        assertEquals(s, back)
        val eraser = DrawingCodec.decode(DrawingCodec.encode(s.copy(eraser = true, color = 0)))!!
        assertTrue(eraser.eraser)
        assertTrue(DrawingCodec.decode(DrawingCodec.encode(Stroke(0, 1, clear = true)))!!.clear)
        // Out-of-range values are clamped to what the server accepts.
        val wild = DrawingCodec.encode(Stroke(color = 99, width = 500, points = listOf(-5, 2000)))
        assertEquals(Stroke(11, 40, false, listOf(0, 1000)), DrawingCodec.decode(wild))
        assertNull(DrawingCodec.decode(json.parseToJsonElement("\"nope\"")))
    }

    @Test fun longStrokesAreSplitAndClearHidesEarlierStrokes() {
        val long = Stroke(1, 6, points = (0 until 1000).flatMap { listOf(it % 1000, 500) })
        val parts = DrawingCodec.split(long)
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.pointCount <= DrawingCodec.MAX_POINTS_PER_STROKE })
        assertEquals(parts[0].points.takeLast(2), parts[1].points.take(2))       // continuous
        assertEquals(DrawingCodec.encodeAll(listOf(long)).size, parts.size)
        val a = Stroke(1, 4, points = listOf(1, 1, 2, 2)); val b = Stroke(2, 4, points = listOf(3, 3, 4, 4))
        assertEquals(listOf(b), DrawingCodec.visible(listOf(a, Stroke(0, 1, clear = true), b)))
        assertEquals(listOf(a, b), DrawingCodec.visible(listOf(a, b)))
    }

    @Test fun telephoneShowcaseParses() {
        val r = state("""{"server_time":"x","mode":"cizim_telefonu","match_id":"m","round":{"id":"r","number":4,"phase":"showcase","started_at":"x",
          "results":{"chains":[{"items":[{"kind":"start","payload":{"text":"Uçan fil"}},{"kind":"draw","user_id":"a","payload":{"strokes":[{"c":1,"w":4,"p":[1,1,2,2]}]}},
          {"kind":"guess","user_id":"b","missing":true}],"matched":false}]}}}""").round!!.resultsAs(TelephoneResults())
        val chain = r.chains.single()
        assertEquals("Uçan fil", CizimTelefonuLogic.text(chain.items[0].payload))
        assertEquals(1, CizimTelefonuLogic.strokes(chain.items[1].payload).size)
        assertTrue(chain.items[2].missing)
        assertEquals("Bu ifadeyi çiz:", CizimTelefonuLogic.instruction("draw"))
    }
}
