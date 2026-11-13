package com.loopa.core.data

import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.game.ServerClock
import com.loopa.core.game.modes.AnlatPublic
import com.loopa.core.game.modes.AnlatView
import com.loopa.core.game.modes.ChainPublic
import com.loopa.core.game.modes.GuessView
import com.loopa.core.game.modes.QuizResults
import com.loopa.core.game.modes.QuizRoundPublic
import com.loopa.core.game.modes.Stroke
import com.loopa.core.game.modes.TelephoneResults
import com.loopa.core.game.modes.TelephoneView
import com.loopa.core.game.modes.TruthsResults
import com.loopa.core.game.modes.TruthsRoundPublic
import com.loopa.core.game.modes.LikelyResults
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.SupabaseConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The 9 games of the mode engine, played by several real clients at the same time against a LOCAL
 * Supabase stack (real GoTrue, PostgREST, Realtime). Every client has its own session, server clock
 * and realtime connection and uses the same RoomSession as the app. Players act concurrently; state
 * reaches the others through realtime signals. Disconnects and a host change are exercised.
 *
 *   LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<local demo key> \
 *   LOOPA_E2E_DB=postgresql://postgres:postgres@127.0.0.1:54322/postgres ./gradlew :core-data:testDebugUnitTest
 *
 * LOOPA_E2E_DB (local only) gives the test hosts Plus, as an admin would, so they may host every game.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModesE2ETest {
    private val url = System.getProperty("loopa.e2e.url").orEmpty()
    private val key = System.getProperty("loopa.e2e.key").orEmpty()
    private val db = System.getProperty("loopa.e2e.db").orEmpty()

    private class Client(val name: String, config: SupabaseConfig) {
        val backend = LoopaBackend(config, inMemorySession = true)
        val clock = ServerClock()
        val auth = AuthRepository(backend)
        val profiles = ProfileRepository(backend)
        val rooms = RoomRepository(backend)
        val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
        var session: RoomSession? = null
        val id get() = backend.currentUserId!!
        val s get() = session!!
        val mode get() = s.mode.value

        fun open(roomId: String): RoomSession {
            session?.stop()
            // Long safety poll: updates must arrive through realtime.
            return RoomSession(roomId, backend, clock, dispatcher, 6_000L to 10_000L).also { session = it; it.start() }
        }
    }

    private fun log(msg: String) = println("[MODES ${System.currentTimeMillis() % 100_000}] $msg")

    private fun psql(sql: String): String {
        val p = ProcessBuilder("psql", db, "-v", "ON_ERROR_STOP=1", "-qtA", "-c", sql).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0) { "psql failed: $out" }
        return out
    }

    private suspend fun Client.awaitMode(what: String, timeoutMs: Long = 15_000, cond: (ModeState) -> Boolean): ModeState = try {
        withTimeout(timeoutMs) { s.mode.first { it != null && cond(it) }!! }
    } catch (e: Exception) {
        throw AssertionError("$name timed out waiting for: $what (last: mode=${mode?.mode} round=${mode?.round?.number} phase=${mode?.round?.phase} status=${mode?.matchStatus})", e)
    }

    private suspend fun Client.awaitLobby(what: String, timeoutMs: Long = 20_000) = try {
        withTimeout(timeoutMs) { s.state.first { it != null && !it.room.inGame && it.room.lastMatchId != null } }
    } catch (e: Exception) { throw AssertionError("$name timed out waiting for: $what", e) }

    /** Lets every deadline of the room pass (local DB only) until the match is over. */
    private suspend fun Client.fastForwardToLobby(what: String, timeoutMs: Long = 60_000) = try {
        withTimeout(timeoutMs) {
            while (true) {
                val st = s.state.value
                if (st != null && !st.room.inGame && st.room.lastMatchId != null) break
                psql("update public.mode_rounds set started_at = least(started_at, now()), deadline_at = now() - interval '2 seconds' where room_id = '${s.roomId}' and phase <> 'done'")
                runCatching { backend.rpc("game_tick") { put("p_room_id", s.roomId) } }
                s.requestRefresh()
                delay(700)
            }
        }
    } catch (e: Exception) { throw AssertionError("$name timed out waiting for: $what", e) }

    private suspend fun expect(error: LoopaError, block: suspend () -> Unit) {
        try { block(); fail("expected $error") } catch (e: LoopaException) { assertEquals(error, e.error) }
    }

    private fun text(t: String) = buildJsonObject { put("text", t) }
    private fun choice(i: Int) = buildJsonObject { put("choice", i) }

    /** Host creates a room for [mode], the others join (concurrently) and get ready, host starts. */
    private suspend fun start(mode: String, host: Client, others: List<Client>, rounds: Int = 3): String = coroutineScope {
        val ref = host.rooms.createRoom(false, mode)
        host.open(ref.id)
        others.map { c -> async { c.rooms.joinRoom(ref.code); c.open(ref.id); c.s.setReady(true) } }.awaitAll()
        val st = withTimeout(10_000) { host.s.state.first { s -> s != null && s.activePlayers.size == others.size + 1 && s.activePlayers.all { it.isReady || it.isHost } } }!!
        host.s.updateSettings(st.room.settings.copy(rounds = rounds, roundSeconds = 30), false)
        others.map { c -> async { c.s.setReady(true) } }.awaitAll()
        withTimeout(10_000) { host.s.state.first { s -> s != null && s.activePlayers.all { it.isReady || it.isHost } } }
        host.s.startMatch()
        (listOf(host) + others).map { c -> async { c.awaitMode("$mode started") { it.mode == mode && it.round != null } } }.awaitAll()
        ref.id
    }

    private suspend fun close(host: Client, others: List<Client>) {
        runCatching { host.s.closeRoom() }
        (listOf(host) + others).forEach { it.session?.stop(); it.session = null }
        delay(300)
    }

    @Test
    fun allNineGamesWithSeveralClients() = runBlocking {
        assumeTrue("LOOPA_E2E_URL not set — skipping", url.isNotBlank() && key.isNotBlank())
        assumeTrue("only against a local stack", url.contains("127.0.0.1") || url.contains("localhost"))
        assumeTrue("LOOPA_E2E_DB needed to give the hosts Plus", db.isNotBlank())
        val cfg = SupabaseConfig(url, key)
        val c = listOf("Ada", "Bora", "Cansu", "Deniz").map { Client(it, cfg) }
        c.forEach { it.auth.signInAnonymously(); it.profiles.ensureProfile("${it.name}${(100..999).random()}", null) }
        val (a, b, cc, d) = c
        psql("insert into public.entitlements(user_id, source, starts_at, ends_at) select u::uuid, 'admin', now() - interval '1 hour', now() + interval '1 day' from unnest(array['${c.joinToString("','") { it.id }}']) u")
        log("4 players signed in, hosts have Plus")
        try {
            // ================================================== Gizli Kelime (3 clients guess at the same time)
            start("gizli_kelime", a, listOf(b, cc))
            var m = a.awaitMode("A describes, sees the word") { it.round?.actorId == a.id && it.meAs(GuessView()).secret != null }
            val word = m.meAs(GuessView()).secret!!.word
            assertNull("guesser must not receive the word", b.mode!!.meAs(GuessView()).secret)
            assertFalse(b.mode.toString().contains(word))
            b.awaitMode("round open") { it.round?.startedAt?.let { s -> com.loopa.core.game.Timestamps.parse(s)!! <= b.clock.now() } == true }
            val rid = m.round!!.id
            coroutineScope {
                listOf(
                    async { b.s.modeAct(rid, "guess", text("yanlışbirtahmin")); b.s.modeAct(rid, "guess", text(word)) },
                    async { cc.s.modeAct(rid, "guess", text(word.uppercase())) },
                ).awaitAll()
            }
            m = a.awaitMode("everybody guessed -> reveal") { it.round?.id == rid && it.round?.phase == "reveal" }
            val scores = a.s.state.value!!.match!!.scoreboard.associate { it.userId to it.totalPoints }
            log("gizli_kelime round 1 scores $scores")
            assertEquals(20, scores[a.id]); assertEquals(55, (scores[b.id] ?: 0) + (scores[cc.id] ?: 0))
            expect(LoopaError.PHASE_CLOSED) { b.s.modeAct(rid, "guess", text(word)) }
            a.s.modeSkip()                                                   // host skips the reveal
            // B and C joined at the same time, so either may describe round 2: ask the server.
            m = a.awaitMode("round 2") { it.round?.number == 2 && it.round?.phase == "play" }
            val describer = listOf(b, cc).first { it.id == m.round!!.actorId }
            val stays = if (describer === b) cc else b
            assertNotNull(describer.awaitMode("describer sees the word") { it.round?.number == 2 }.meAs(GuessView()).secret)
            assertNull(stays.awaitMode("guesser view") { it.round?.number == 2 }.meAs(GuessView()).secret)
            // The describer disconnects (app killed): the turn is closed, the match goes on without them.
            val room1 = a.s.roomId
            describer.session!!.stop(); describer.session = null
            describer.backend.rpc("leave_room") { put("p_room_id", room1) }
            a.awaitMode("describer left -> round closed", 60_000) { it.round?.number == 3 || (it.round?.number == 2 && it.round?.phase == "reveal") }
            a.fastForwardToLobby("2 players left -> match ends after round 3")
            log("gizli_kelime finished with a disconnect")
            close(a, listOf(stays))

            // ================================================== Bilgi Arenası (concurrent answers, speed bonus)
            start("bilgi_arenasi", a, listOf(b, cc, d))
            repeat(3) { q ->
                val st0 = a.awaitMode("question ${q + 1}") { it.round?.number == q + 1 && it.round?.phase == "answer" }
                val round = st0.round!!
                val st = object { val round = round }
                val pub = round.publicAs(QuizRoundPublic())
                assertEquals(4, pub.options.size)
                assertFalse("answer must not be in the question snapshot", st.round.toString().contains("explanation"))
                a.awaitMode("open") { com.loopa.core.game.Timestamps.parse(it.round!!.startedAt)!! <= a.clock.now() }
                coroutineScope { c.map { p -> async { p.s.modeAct(st.round.id, "answer", choice((c.indexOf(p) + q) % 4)) } }.awaitAll() }
                val rev = d.awaitMode("reveal ${q + 1}") { it.round?.id == st.round.id && it.round?.phase == "reveal" }
                val res = rev.round!!.resultsAs(QuizResults())
                assertEquals(4, res.answers.size)
                assertTrue(res.explanation.isNotBlank())
                res.answers.forEach { ans -> assertEquals(ans.choice == res.answer, ans.correct); if (ans.correct) assertTrue(ans.points in 100..150) }
                expect(LoopaError.PHASE_CLOSED) { b.s.modeAct(st.round.id, "answer", choice(0)) }
                if (q < 2) a.s.modeSkip()
            }
            a.awaitLobby("quiz done", 30_000)
            log("bilgi_arenasi finished")
            close(a, listOf(b, cc, d))

            // ================================================== Çiz ve Tahmin Et (live strokes over realtime)
            start("ciz_tahmin", a, listOf(b, cc))
            m = a.awaitMode("A draws") { it.round?.actorId == a.id && it.meAs(GuessView()).secret != null }
            val drawWord = m.meAs(GuessView()).secret!!.word
            a.awaitMode("open") { com.loopa.core.game.Timestamps.parse(it.round!!.startedAt)!! <= a.clock.now() }
            val t0 = System.currentTimeMillis()
            a.s.draw(m.round!!.id, listOf(Stroke(1, 8, points = listOf(100, 100, 200, 200, 300, 150)), Stroke(5, 16, points = listOf(500, 500, 520, 540))))
            val seen = withTimeout(5_000) { b.s.strokes.first { it.roundId == m.round!!.id && it.strokes.size >= 2 } }
            log("strokes reached B in ${System.currentTimeMillis() - t0} ms")
            assertEquals(listOf(100, 100, 200, 200, 300, 150), seen.strokes[0].points)
            expect(LoopaError.NOT_ACTOR) { b.s.draw(m.round!!.id, listOf(Stroke(1, 4, points = listOf(1, 1, 2, 2)))) }
            val hit = b.s.modeAct(m.round!!.id, "guess", text(drawWord))
            assertEquals("true", (hit["correct"] as JsonPrimitive).content)
            cc.s.modeAct(m.round!!.id, "guess", text(drawWord))
            a.awaitMode("reveal") { it.round?.phase == "reveal" }
            // Host change: A (host) leaves; B becomes host and can skip; the match goes on.
            a.session!!.stop(); a.session = null
            a.backend.rpc("leave_room") { put("p_room_id", b.s.roomId) }
            withTimeout(45_000) { b.s.state.first { it?.room?.hostId == b.id || it?.room?.hostId == cc.id } }
            log("host moved to ${if (b.s.state.value!!.room.hostId == b.id) "B" else "C"}")
            val newHost = if (b.s.state.value!!.room.hostId == b.id) b else cc
            runCatching { newHost.s.modeSkip() }
            newHost.fastForwardToLobby("match goes on with the new host and ends")
            log("ciz_tahmin finished after a host change")
            close(newHost, listOf(b, cc).filter { it != newHost })

            // ================================================== Anlat Bakalım (4 players, 2 teams)
            start("anlat_bakalim", a, listOf(b, cc, d))
            m = a.awaitMode("team 0, A narrates") { it.round?.actorId == a.id }
            val teams = m.publicAs(AnlatPublic()).teams
            // Teams alternate by join order; A (host) joined first, the others joined at the same time.
            assertEquals(2, teams.size); assertEquals(a.id, teams[0][0]); assertEquals(setOf(a.id, b.id, cc.id, d.id), teams.flatten().toSet())
            val mate = c.first { it.id == teams[0][1] }
            val rival = c.first { it.id == teams[1][0] }
            assertEquals("narrator", m.meAs(AnlatView()).role)
            val card = m.meAs(AnlatView()).secret!!.word
            assertNull("teammate must not see the card", mate.awaitMode("mate view") { it.round?.id == m.round!!.id }.meAs(AnlatView()).secret)
            assertNotNull("opponent sees the card", rival.awaitMode("rival view") { it.round?.id == m.round!!.id }.meAs(AnlatView()).secret)
            a.awaitMode("open") { com.loopa.core.game.Timestamps.parse(it.round!!.startedAt)!! <= a.clock.now() }
            mate.s.modeAct(m.round!!.id, "guess", text(card))
            a.s.modeAct(m.round!!.id, "pass")
            rival.s.modeAct(m.round!!.id, "taboo")
            a.s.modeAct(m.round!!.id, "correct")
            val pub = d.awaitMode("scores") { it.publicAs(AnlatPublic()).teamScores == listOf(1, 0) }
            log("anlat_bakalim team scores ${pub.publicAs(AnlatPublic()).teamScores}")
            psql("update public.mode_rounds set deadline_at = now() where room_id = '${a.s.roomId}' and phase in ('play','reveal')")
            c.forEach { it.s.requestRefresh() }
            rival.awaitMode("team 1 narrates") { it.round?.number == 2 && it.round?.actorId == teams[1][0] }
            psql("update public.mode_rounds set deadline_at = now() where room_id = '${a.s.roomId}'")
            close(a, listOf(b, cc, d))

            // ================================================== İki Doğru Bir Yalan
            start("iki_dogru_bir_yalan", a, listOf(b, cc))
            val writeRound = a.mode!!.round!!.id
            coroutineScope {
                listOf(a, b, cc).mapIndexed { i, p ->
                    async {
                        p.s.modeAct(writeRound, "statements", buildJsonObject {
                            put("items", buildJsonArray { listOf("Benim adım ${p.name}", "Hiç kar görmedim $i", "Üç dil bilirim $i").forEach { add(JsonPrimitive(it)) } })
                            put("lie", i % 3)
                        })
                    }
                }.awaitAll()
            }
            m = b.awaitMode("A's statements up for vote") { it.round?.number == 2 && it.round?.phase == "vote" }
            assertEquals(3, m.round!!.publicAs(TruthsRoundPublic()).statements.size)
            assertFalse(m.toString().contains("\"lie\""))
            b.awaitMode("open") { com.loopa.core.game.Timestamps.parse(it.round!!.startedAt)!! <= b.clock.now() }
            expect(LoopaError.CANNOT_VOTE_OWN) { a.s.modeAct(m.round!!.id, "vote", choice(0)) }
            coroutineScope { listOf(async { b.s.modeAct(m.round!!.id, "vote", choice(0)) }, async { cc.s.modeAct(m.round!!.id, "vote", choice(1)) }).awaitAll() }
            val tr = a.awaitMode("reveal") { it.round?.id == m.round!!.id && it.round?.phase == "reveal" }.round!!.resultsAs(TruthsResults())
            assertEquals(0, tr.lie); assertEquals(1, tr.fooled)
            close(a, listOf(b, cc))

            // ================================================== En Muhtemel Kim?
            start("en_muhtemel_kim", a, listOf(b, cc))
            m = a.awaitMode("vote") { it.round?.phase == "vote" }
            a.awaitMode("open") { com.loopa.core.game.Timestamps.parse(it.round!!.startedAt)!! <= a.clock.now() }
            expect(LoopaError.CANNOT_VOTE_SELF) { a.s.modeAct(m.round!!.id, "vote", buildJsonObject { put("target", a.id) }) }
            coroutineScope {
                listOf(async { a.s.modeAct(m.round!!.id, "vote", buildJsonObject { put("target", cc.id) }) },
                       async { b.s.modeAct(m.round!!.id, "vote", buildJsonObject { put("target", cc.id) }) },
                       async { cc.s.modeAct(m.round!!.id, "vote", buildJsonObject { put("target", a.id) }) }).awaitAll()
            }
            val lr = b.awaitMode("reveal") { it.round?.phase == "reveal" }.round!!.resultsAs(LikelyResults())
            assertEquals(listOf(cc.id), lr.tops)
            close(a, listOf(b, cc))

            // ================================================== Kelime Zinciri (turns)
            start("kelime_zinciri", a, listOf(b))
            m = a.awaitMode("A's turn") { it.round?.actorId == a.id && it.round?.phase == "turn" }
            val letter = m.publicAs(ChainPublic()).letter
            expect(LoopaError.NOT_YOUR_TURN) { b.s.modeAct(m.round!!.id, "word", text("elma")) }
            expect(LoopaError.CHAIN_UNKNOWN_WORD) { a.s.modeAct(m.round!!.id, "word", text(letter + "qxqxq")) }
            val candidate = psql("select word from public.chain_lexicon where left(match_key,1) = '$letter' and match_key <> public.dict_key('${m.publicAs(ChainPublic()).word}') order by match_key limit 1")
            val ok = a.s.modeAct(m.round!!.id, "word", text(candidate))
            assertEquals("true", (ok["valid"] as JsonPrimitive).content)
            val next = b.awaitMode("B continues from A's word") { it.round?.actorId == b.id && it.publicAs(ChainPublic()).word == candidate }
            assertEquals(com.loopa.core.game.modes.KelimeZinciriLogic.lastLetter(candidate), next.publicAs(ChainPublic()).letter)
            close(a, listOf(b))

            // ================================================== Sessiz Sinema
            start("sessiz_sinema", a, listOf(b, cc))
            m = a.awaitMode("A acts") { it.round?.actorId == a.id && it.meAs(GuessView()).secret != null }
            a.awaitMode("open") { com.loopa.core.game.Timestamps.parse(it.round!!.startedAt)!! <= a.clock.now() }
            val phrase = m.meAs(GuessView()).secret!!.word
            val r1 = coroutineScope {
                listOf(async { runCatching { b.s.modeAct(m.round!!.id, "guess", text(phrase)) } },
                       async { runCatching { cc.s.modeAct(m.round!!.id, "guess", text(phrase)) } }).awaitAll()
            }
            // Two players shout at once: exactly one of them wins the round.
            assertEquals(1, r1.count { it.getOrNull()?.get("correct")?.let { v -> (v as JsonPrimitive).content == "true" } == true })
            a.awaitMode("first correct ends the round") { it.round?.phase == "reveal" }
            close(a, listOf(b, cc))

            // ================================================== Çizim Telefonu (3 players: draw, guess, draw, showcase)
            start("cizim_telefonu", a, listOf(b, cc))
            for (step in 1..3) {
                val st = a.awaitMode("step $step") { it.round?.number == step && it.round?.phase == "work" }
                a.awaitMode("open") { com.loopa.core.game.Timestamps.parse(it.round!!.startedAt)!! <= a.clock.now() }
                coroutineScope {
                    listOf(a, b, cc).map { p ->
                        async {
                            val v = p.awaitMode("${p.name} task $step") { it.round?.id == st.round!!.id }.meAs(TelephoneView())
                            assertNotNull(v.task)
                            val payload = if (step % 2 == 1) buildJsonObject { put("strokes", com.loopa.core.game.modes.DrawingCodec.encodeAll(listOf(Stroke(1, 6, points = listOf(1, 2, 3, 4))))) }
                                          else text("tahmin ${p.name}")
                            p.s.modeAct(st.round!!.id, "step", payload)
                        }
                    }.awaitAll()
                }
            }
            val show = b.awaitMode("showcase") { it.round?.phase == "showcase" }.round!!.resultsAs(TelephoneResults())
            assertEquals(3, show.chains.size); assertTrue(show.chains.all { it.items.size == 4 && it.items.none { i -> i.missing } })
            a.s.modeSkip()
            a.awaitLobby("telephone done")
            close(a, listOf(b, cc))

            // Results and per-game statistics.
            val stats = a.profiles.modeStats().map { it.gameMode }.toSet()
            log("A's per-game stats: $stats")
            assertTrue(stats.containsAll(listOf("gizli_kelime", "bilgi_arenasi", "cizim_telefonu")))
            assertTrue(GameModes.implemented.containsAll(stats))
        } finally {
            c.forEach { it.session?.stop() }
            val ids = c.mapNotNull { runCatching { it.id }.getOrNull() }.joinToString("','")
            runCatching { psql("delete from public.rooms where host_id in ('$ids'); delete from auth.users where id in ('$ids');") }
        }
    }
}
