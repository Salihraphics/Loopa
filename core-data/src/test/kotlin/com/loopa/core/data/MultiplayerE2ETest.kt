package com.loopa.core.data

import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.game.ServerClock
import com.loopa.core.game.Timestamps
import com.loopa.core.model.JokerType
import com.loopa.core.model.RoomState
import com.loopa.core.model.RoundPhase
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.SupabaseConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end multiplayer test: several fully independent clients (own auth
 * session, own server clock, own realtime connection) play a real match
 * against a running Supabase stack, using the same RoomSession and
 * repositories as the Android app.
 *
 * Run with:  LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=... ./gradlew :core-data:testDebugUnitTest
 * Skipped automatically when the variables are not set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerE2ETest {
    private val url = System.getProperty("loopa.e2e.url").orEmpty()
    private val key = System.getProperty("loopa.e2e.key").orEmpty()

    private class Client(val name: String, config: SupabaseConfig) {
        val backend = LoopaBackend(config, inMemorySession = true)
        val clock = ServerClock()
        val auth = AuthRepository(backend)
        val profiles = ProfileRepository(backend)
        val rooms = RoomRepository(backend)
        val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
        var session: RoomSession? = null
        val events = mutableListOf<SessionEvent>()

        fun open(roomId: String, pollMs: Pair<Long, Long> = 60_000L to 60_000L): RoomSession {
            session?.stop()
            return RoomSession(roomId, backend, clock, dispatcher, pollMs).also { s ->
                session = s
                s.start()
                kotlinx.coroutines.CoroutineScope(dispatcher).launch { s.events.collect { synchronized(events) { events += it } } }
            }
        }

        val s get() = session!!
        val state get() = s.state.value
        val id get() = backend.currentUserId!!
    }

    private fun log(msg: String) = println("[E2E ${System.currentTimeMillis() % 100_000}] $msg")

    private suspend fun Client.await(what: String, timeoutMs: Long = 8_000, cond: (RoomState) -> Boolean): RoomState =
        try {
            withTimeout(timeoutMs) { s.state.first { it != null && cond(it) }!! }
        } catch (e: Exception) {
            throw AssertionError("$name timed out waiting for: $what (last phase=${state?.round?.phase}, room=${state?.room?.status}, players=${state?.players?.map { it.username }}, conn=${s.connection.value})", e)
        }

    @Test
    fun fullMatchWithFourClients() = runBlocking {
        assumeTrue("LOOPA_E2E_URL not set — skipping live multiplayer test", url.isNotBlank() && key.isNotBlank())
        val cfg = SupabaseConfig(url, key)
        val a = Client("A", cfg); val b = Client("B", cfg); val c = Client("C", cfg); val d = Client("D", cfg)
        val all = listOf(a, b, c, d)

        // --- anonymous auth + profiles (concurrently)
        all.map { cl -> async { cl.auth.signInAnonymously(); cl.profiles.ensureProfile("E2E ${cl.name}", null) } }.awaitAll()
        assertEquals(4, all.map { it.id }.toSet().size)
        log("4 anonymous players signed in")

        // --- room + realtime join propagation
        val ref = a.rooms.createRoom(isPublic = true)
        assertTrue(ref.code.matches(Regex("[A-Z2-9]{6}")))
        a.open(ref.id)
        a.await("own room snapshot") { it.players.size == 1 }
        assertTrue("public room listed", b.rooms.publicRooms().any { it.code == ref.code })

        val t0 = System.currentTimeMillis()
        b.rooms.joinRoom(ref.code.lowercase()); b.open(ref.id)
        a.await("B visible via realtime", 3_000) { s -> s.players.any { it.userId == b.id } }
        log("realtime join propagated in ${System.currentTimeMillis() - t0} ms (polling disabled)")
        c.rooms.joinRoom(ref.code); c.open(ref.id)
        d.rooms.joinRoom(ref.code, asSpectator = true); d.open(ref.id)
        a.await("4 members") { it.players.size == 4 }

        // --- authorization: non-host cannot change settings or start
        val settings = a.state!!.room.settings.copy(rounds = 3, roundSeconds = 30, voteSeconds = 15)
        val notHost = runCatching { b.s.updateSettings(settings, false) }.exceptionOrNull() as? LoopaException
        assertEquals(LoopaError.NOT_HOST, notHost?.error)
        a.s.updateSettings(settings, isPublic = false)
        b.await("settings propagated", 3_000) { it.room.settings.rounds == 3 && !it.room.isPublic }

        val notReady = runCatching { a.s.startMatch() }.exceptionOrNull() as? LoopaException
        assertEquals(LoopaError.PLAYERS_NOT_READY, notReady?.error)
        b.s.setReady(true); c.s.setReady(true)
        a.await("everyone ready", 3_000) { s -> s.activePlayers.all { it.isReady || it.isHost } }

        // --- double start from two coroutines: exactly one succeeds
        val starts = listOf(async { runCatching { a.s.startMatch() } }, async { runCatching { a.s.startMatch() } }).awaitAll()
        assertEquals("exactly one start succeeds", 1, starts.count { it.isSuccess })
        all.forEach { cl -> cl.await("${cl.name} in game", 3_000) { it.room.inGame && it.round != null } }
        log("match started, all clients switched to game via realtime")
        assertTrue("spectator sees game", d.state!!.isSpectator)
        all.filter { it != d }.forEach { assertEquals("one starting joker", 1, it.state!!.match!!.myJokers.size) }

        for (roundNo in 1..3) {
            val r = a.await("round $roundNo") { it.round?.number == roundNo && it.round?.phase == RoundPhase.ANSWERING }.round!!
            val letter = r.letter
            // wait for the synchronized letter reveal to end
            val startAt = Timestamps.parse(r.startedAt)!!
            delay((startAt - a.clock.now() + 300).coerceAtLeast(0))
            log("round $roundNo letter=$letter clock offsets A=${a.clock.offsetMs} B=${b.clock.offsetMs} C=${c.clock.offsetMs}")

            if (roundNo == 1) {
                // Jokers: A uses whatever it has (targeting C when needed)
                val joker = a.state!!.match!!.myJokers.first()
                a.s.useJoker(r.id, joker.id, joker.type, target = c.id, category = r.categories.first().key)
                val again = runCatching { a.s.useJoker(r.id, joker.id, joker.type, c.id, r.categories.first().key) }.exceptionOrNull() as? LoopaException
                assertNotNull("second joker use rejected", again)
                val after = c.await("joker event visible to target", 3_000) { s -> s.round?.jokerEvents?.isNotEmpty() == true }
                log("joker ${joker.type} applied; C deadline=${after.round!!.player(c.id)!!.deadlineAt}")
                if (joker.type == JokerType.TIME_THIEF || joker.type == JokerType.SLOWDOWN) {
                    val cd = Timestamps.parse(after.round!!.player(c.id)!!.deadlineAt)!!
                    val base = Timestamps.parse(after.round!!.baseDeadline)!!
                    assertTrue("target lost time", cd < base)
                }
                // spectator cannot answer
                val spec = runCatching { d.s.submit(r.id, emptyMap()) }.exceptionOrNull() as? LoopaException
                assertEquals(LoopaError.NOT_A_PARTICIPANT, spec?.error)
            }

            val shared = r.categories.associate { it.key to "${letter}loopa ${it.key}" }
            val cAnswers = r.categories.take(3).associate { it.key to "${letter}cevap ${it.key}" }
            if (roundNo == 2) {
                // C "loses connection": saves a draft, then its app dies (no heartbeat, no submit).
                c.s.saveDraft(r.id, cAnswers)
                c.session!!.stop(); c.session = null
                log("C disconnected")
            }
            // A and B submit identical answers at the same moment: only one early bonus.
            val bonuses = listOf(async { a.s.submit(r.id, shared) }, async { b.s.submit(r.id, shared) }).awaitAll()
            assertEquals("exactly one early bonus", 1, bonuses.count { it == 5 })
            if (roundNo != 2) c.s.submit(r.id, cAnswers)

            if (roundNo == 2) {
                // The round must close by itself when C's server deadline passes (+grace).
                val voting = a.await("round 2 closes after C's deadline", 45_000) { it.round?.id == r.id && it.round?.phase != RoundPhase.ANSWERING }
                assertTrue("C's draft kept", voting.round!!.answers.any { it.userId == c.id && it.text.isNotBlank() })
                log("round 2 closed via server deadline with C offline")
            }

            // Voting: every connected participant accepts every pending answer that isn't theirs.
            val voters = if (roundNo == 2) listOf(a, b) else listOf(a, b, c)
            val vs = a.await("voting or reveal") { it.round?.id == r.id && it.round!!.phase in setOf(RoundPhase.VOTING, RoundPhase.REVEAL) }
            if (vs.round!!.phase == RoundPhase.VOTING) {
                voters.forEach { v ->
                    v.await("${v.name} sees voting") { it.round?.phase == RoundPhase.VOTING || it.round?.phase == RoundPhase.REVEAL }
                    v.state!!.round!!.answers.filter { it.isPendingVote && it.userId != v.id }.forEach { ans ->
                        runCatching { v.s.vote(ans.id, accept = true) }
                    }
                }
                val own = vs.round!!.answers.firstOrNull { it.isPendingVote && it.userId == a.id }
                if (own != null) assertNotNull(runCatching { a.s.vote(own.id, true) }.exceptionOrNull())
            }
            val reveal = a.await("reveal", 20_000) { it.round?.id == r.id && it.round!!.phase in setOf(RoundPhase.REVEAL, RoundPhase.RESULTS) }
            val ra = reveal.round!!.answers
            // duplicates of accepted answers score 10, unique accepted 20
            val dup = ra.filter { it.userId == a.id && it.accepted && !it.doubled }
            assertTrue("A's accepted answers are duplicates worth 10", dup.all { it.isDuplicate && it.points == 10 })
            assertTrue("same reveal timeline on B", b.await("B reveal", 3_000) { it.round?.revealStartedAt == reveal.round!!.revealStartedAt }.round != null)
            if (roundNo == 3) assertTrue("reconnected C scored", ra.any { it.userId == c.id && it.points > 0 })

            a.s.skipReveal()     // host fast-forwards reveal
            a.await("results", 5_000) { it.round?.id == r.id && it.round!!.phase in setOf(RoundPhase.RESULTS, RoundPhase.FINISHED) || it.round?.id != r.id || !it.room.inGame }
            val rr = a.state!!.round!!
            if (rr.id == r.id) {
                val winners = rr.players.filter { it.isRoundWinner }
                log("round $roundNo winners=${winners.map { w -> all.first { it.id == w.userId }.name }} points=${rr.players.map { it.points }}")
                a.s.skipReveal() // skip results countdown
            }

            if (roundNo == 2) {
                // C comes back: rejoin by code restores it as a participant.
                c.rooms.joinRoom(ref.code)
                c.open(ref.id)
                c.await("C restored into the running match") { it.room.inGame && it.me.role == "player" }
                log("C reconnected")
            }
        }

        // --- match end -> results for everyone
        all.forEach { cl -> cl.await("${cl.name} back in lobby after match", 15_000) { !it.room.inGame && it.room.lastMatchId != null } }
        val matchId = a.state!!.room.lastMatchId!!
        val results = a.rooms.matchResults(matchId)
        assertEquals(3, results.players.size)
        assertEquals(1, results.players.first().finalRank)
        assertTrue("XP granted", results.players.all { it.xpEarned >= 20 })
        delay(500)
        assertTrue("MatchFinished event delivered", synchronized(a.events) { a.events.any { it is SessionEvent.MatchFinished } })
        val pa = a.profiles.refresh()!!
        assertTrue("profile XP updated", pa.xp >= 20 && pa.stats!!.matchesPlayed == 1)
        assertEquals("history entry", 1, pa.history.size)
        log("results: ${results.players.map { "${it.username}#${it.finalRank}:${it.totalPoints}p/+${it.xpEarned}xp" }}")

        // --- host disconnect -> controlled host transfer
        a.session!!.stop()
        log("host A went silent; waiting for transfer")
        b.await("host transferred to B", 50_000) { it.room.hostId == b.id }
        log("host transferred to B")

        all.forEach { it.session?.stop() }
    }
}
