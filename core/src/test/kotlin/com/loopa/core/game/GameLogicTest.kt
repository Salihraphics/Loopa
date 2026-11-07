package com.loopa.core.game

import com.loopa.core.model.Answer
import com.loopa.core.model.Category
import com.loopa.core.model.Round
import com.loopa.core.model.RoundPhase
import com.loopa.core.model.RoundPlayer
import com.loopa.core.model.RoomState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLogicTest {
    @Test fun normalizeMatchesDatabaseRules() {
        assertEquals("istanbul boğazı", TurkishText.normalize("  İSTANBUL   Boğazı "))
        assertEquals("ışık", TurkishText.normalize("IŞIK"))
        assertEquals("çiğköfte", TurkishText.normalize("ÇİĞKÖFTE"))
        assertEquals("İSTANBUL", TurkishText.upper("istanbul"))
        assertEquals("IŞIK", TurkishText.upper("ışık"))
    }

    @Test fun levelCurveMatchesDatabase() {
        assertEquals(1, LevelCurve.levelForXp(0))
        assertEquals(1, LevelCurve.levelForXp(99))
        assertEquals(2, LevelCurve.levelForXp(100))
        assertEquals(3, LevelCurve.levelForXp(250))
        assertEquals(0, LevelCurve.xpForLevel(1))
        assertEquals(250, LevelCurve.xpForLevel(3))
        val p = LevelCurve.progress(175)
        assertEquals(2, p.level)
        assertEquals(75, p.intoLevel)
        assertEquals(150, p.levelSpan)
    }

    @Test fun serverClockUsesHalfRoundTrip() {
        var device = 1_000L
        val clock = ServerClock { device }
        clock.addSample(sentAt = 1_000, receivedAt = 1_200, serverMs = 6_000)
        // server time at receive = 6000 + 100 => offset 4900
        assertEquals(4_900, clock.offsetMs)
        device = 2_000
        assertEquals(6_900, clock.now())
    }

    @Test fun timestampsParsePostgresFormat() {
        assertEquals(0L, Timestamps.parse("1970-01-01T00:00:00+00:00"))
        assertEquals(1_500L, Timestamps.parse("1970-01-01T00:00:01.5+00:00"))
        assertNull(Timestamps.parse("garbage"))
    }

    @Test fun errorCodesAreParsed() {
        assertEquals(LoopaError.ROOM_FULL, LoopaError.fromMessage("ERROR: LOOPA:ROOM_FULL"))
        assertEquals(LoopaError.UNKNOWN, LoopaError.fromMessage("LOOPA:SOMETHING_NEW"))
        assertNull(LoopaError.fromMessage("other"))
    }

    private fun round(phase: RoundPhase) = Round(
        id = "r", number = 1, letter = "A",
        categories = listOf(Category("isim", "İsim"), Category("sehir", "Şehir")),
        phase = phase,
        startedAt = "1970-01-01T00:00:10+00:00",
        baseDeadline = "1970-01-01T00:01:10+00:00",
        revealStartedAt = "1970-01-01T00:02:00+00:00",
        revealDeadline = "1970-01-01T00:02:11.5+00:00",
        players = listOf(
            RoundPlayer("a", "1970-01-01T00:01:20+00:00", submitted = true),
            RoundPlayer("b", "1970-01-01T00:01:00+00:00"),
        ),
    )

    @Test fun individualDeadlines() {
        val r = round(RoundPhase.ANSWERING)
        assertEquals(20_000L, RoundTiming.remainingMs(r, "a", 60_000))
        assertEquals(0L, RoundTiming.remainingMs(r, "b", 61_000))
        // only unsubmitted players gate the transition (+ grace)
        assertEquals(61_600L, RoundTiming.nextTransitionAt(r))
    }

    @Test fun revealIsSynchronizedByServerTime() {
        val r = round(RoundPhase.REVEAL)
        assertEquals(0, RoundTiming.revealIndex(r, 120_000))
        assertEquals(1, RoundTiming.revealIndex(r, 125_100))
        assertEquals(2, RoundTiming.revealIndex(r, 140_000))
    }

    @Test fun groupingCombinesDuplicates() {
        val groups = AnswerGrouping.group(listOf(
            Answer("1", "a", "isim", "Ali", "ali", finalStatus = "accepted"),
            Answer("2", "b", "isim", "ALİ", "ali", finalStatus = "accepted"),
            Answer("3", "c", "isim", "", "", finalStatus = "empty"),
            Answer("4", "d", "isim", "", "", finalStatus = "empty"),
        ))
        assertEquals(3, groups.size)
        assertEquals(2, groups.first().answers.size)
    }

    @Test fun roomStateDecodes() {
        val json = """
        {"server_time":"2026-11-07T10:00:00+00:00",
         "me":{"user_id":"u1","role":"player","status":"active"},
         "room":{"id":"r1","code":"ABC234","host_id":"u1","status":"in_game","is_public":false,
                 "settings":{"round_seconds":60,"rounds":5,"max_players":10,"vote_seconds":20,"allow_spectators":true,
                             "excluded_letters":["J"],"categories":[{"key":"isim","name":"İsim","custom":false}]},
                 "current_match_id":"m1","last_match_id":null,"extra":1},
         "players":[{"user_id":"u1","username":"Ayşe","avatar":{"hair":"hair_long"},"level":2,"role":"player",
                     "is_ready":true,"is_host":true,"online":true,"joined_at":"2026-11-07T10:00:00+00:00"}],
         "match":{"id":"m1","status":"in_progress","total_rounds":5,"current_round":1,"settings":{"categories":[]},
                  "scoreboard":[],"my_jokers":[{"id":"j1","type":"time_thief","source":"match_start"}]},
         "round":{"id":"rd","number":1,"letter":"A","categories":[{"key":"isim","name":"İsim","custom":false}],
                  "phase":"answering","started_at":"2026-11-07T10:00:04+00:00","base_deadline":"2026-11-07T10:01:04+00:00",
                  "players":[],"joker_events":[],"answers":[]}}
        """.trimIndent()
        val state = Json { ignoreUnknownKeys = true }.decodeFromString(RoomState.serializer(), json)
        assertTrue(state.isHost)
        assertEquals("ABC234", state.room.code)
        assertEquals(1, state.match!!.myJokers.size)
        assertEquals(RoundPhase.ANSWERING, state.round!!.phase)
    }
}
