package com.loopa.core.data

import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.game.ModeOptions
import com.loopa.core.game.ServerClock
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Buckshot against a LOCAL Supabase stack with real clients: room setup, hidden magazine, a replayed
 * action refused, a client that disconnects and comes back (new session, same private view), the
 * match played through three rounds, one money bag per round, nothing for losers.
 *
 *   LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<local demo key> \
 *   LOOPA_E2E_DB=postgresql://postgres:postgres@127.0.0.1:54322/postgres ./gradlew :core-data:testDebugUnitTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuckshotE2ETest {
    private val url = System.getProperty("loopa.e2e.url").orEmpty()
    private val key = System.getProperty("loopa.e2e.key").orEmpty()
    private val db = System.getProperty("loopa.e2e.db").orEmpty()

    private class Client(val name: String, config: SupabaseConfig) {
        val backend = LoopaBackend(config, inMemorySession = true)
        val clock = ServerClock()
        val auth = AuthRepository(backend)
        val profiles = ProfileRepository(backend)
        val rooms = RoomRepository(backend)
        val platform = PlatformRepository(backend)
        val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
        var session: RoomSession? = null
        val id get() = backend.currentUserId!!
        val s get() = session!!
        fun open(roomId: String): RoomSession {
            session?.stop()
            return RoomSession(roomId, backend, clock, dispatcher, 3_000L to 6_000L).also { session = it; it.start() }
        }
    }

    private fun psql(sql: String): String {
        val p = ProcessBuilder("psql", db, "-v", "ON_ERROR_STOP=1", "-qtA", "-c", sql).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0) { "psql failed: $out" }
        return out
    }

    private suspend fun Client.awaitMode(what: String, timeoutMs: Long = 20_000, cond: (ModeState) -> Boolean): ModeState = try {
        withTimeout(timeoutMs) { s.mode.first { it != null && cond(it) }!! }
    } catch (e: Exception) { throw AssertionError("$name timed out waiting for: $what (last: ${s.mode.value?.round?.phase})", e) }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content

    /** 0.8.0 flow: room opens in setup, players cannot be ready until the host saved the options. */
    private suspend fun start(mode: String, host: Client, others: List<Client>, options: JsonObject, maxPlayers: Int): String = coroutineScope {
        val ref = host.rooms.createRoom(false, mode)
        host.open(ref.id)
        val st0 = withTimeout(10_000) { host.s.state.first { it != null } }!!
        assertFalse("a new game's room opens in setup", st0.room.settings.configured)
        host.s.updateSettings(st0.room.settings.copy(maxPlayers = maxPlayers, configured = false), false)
        others.map { c -> async { c.rooms.joinRoom(ref.code); c.open(ref.id) } }.awaitAll()
        try { others.first().s.setReady(true); fail("ready before setup") } catch (e: LoopaException) { assertEquals(LoopaError.SETUP_NOT_DONE, e.error) }
        val st = withTimeout(10_000) { host.s.state.first { s -> s != null && s.activePlayers.size == others.size + 1 } }!!
        host.s.updateSettings(st.room.settings.copy(configured = true, modeOptions = ModeOptions.normalize(mode, options)), false)
        others.map { c -> async { c.s.setReady(true) } }.awaitAll()
        withTimeout(10_000) { host.s.state.first { s -> s != null && s.activePlayers.all { it.isReady || it.isHost } } }
        host.s.startMatch()
        (listOf(host) + others).map { c -> async { c.awaitMode("$mode started") { it.mode == mode && it.round != null } } }.awaitAll()
        ref.id
    }

    private fun nonce() = java.util.UUID.randomUUID().toString()

    @Test
    fun twoPlayersThreeRoundsWithReconnect() = runBlocking {
        assumeTrue("LOOPA_E2E_URL not set — skipping", url.isNotBlank() && key.isNotBlank())
        assumeTrue("only against a local stack", url.contains("127.0.0.1") || url.contains("localhost"))
        assumeTrue("LOOPA_E2E_DB needed to give the host Plus", db.isNotBlank())
        val cfg = SupabaseConfig(url, key)
        val c = listOf("Ilgaz", "Jale").map { Client(it, cfg) }
        c.forEach { it.auth.signInAnonymously(); it.profiles.ensureProfile("${it.name}${(100..999).random()}", null) }
        val (a, b) = c
        psql("insert into public.entitlements(user_id, source, starts_at, ends_at) select u::uuid, 'admin', now() - interval '1 hour', now() + interval '1 day' from unnest(array['${c.joinToString("','") { it.id }}']) u")
        try {
            val room = start("buckshot", a, listOf(b), buildJsonObject { put("turn_seconds", 30) }, 2)
            var m = a.awaitMode("round 1") { it.round?.public?.get("mag") is JsonObject }
            val pub = m.round!!.public
            assertEquals("3", (pub["max_hp"] as JsonPrimitive).content)
            val mag = pub["mag"] as JsonObject
            assertEquals("8", (mag["remaining"] as JsonPrimitive).content)
            assertTrue("the shell order is not public", mag["shells"] == null && !m.toString().contains("\"shells\""))
            assertEquals(4, (m.me["inv"] as JsonArray).size)
            // the player on turn: a replayed nonce is refused (cannot fire twice)
            val first = if ((pub["turn"] as JsonPrimitive).content == a.id) a else b
            val other = if (first === a) b else a
            val n = nonce()
            first.s.modeAct(m.round!!.id, "fire", buildJsonObject { put("target", other.id); put("nonce", n) })
            try { first.s.modeAct(m.round!!.id, "fire", buildJsonObject { put("target", other.id); put("nonce", n) }); fail("replay accepted") }
            catch (e: LoopaException) { assertTrue(e.error == LoopaError.DUPLICATE_ACTION || e.error == LoopaError.NOT_YOUR_TURN) }
            val afterOne = first.awaitMode("one shell fired") { (((it.round?.public?.get("mag") as? JsonObject)?.get("remaining")) as? JsonPrimitive)?.content == "7" }
            assertEquals("7", ((afterOne.round!!.public["mag"] as JsonObject)["remaining"] as JsonPrimitive).content)
            // B disconnects (session gone) and comes back with a fresh session: same room, same private items
            val invBefore = b.s.mode.value!!.me["inv"]
            b.session?.stop(); b.session = null
            delay(500)
            b.open(room)
            val back = b.awaitMode("B reconnected") { it.round?.public?.get("mag") is JsonObject }
            assertEquals(invBefore, back.me["inv"])
            assertEquals("buckshot", back.mode)
            // play on: whoever is on turn fires at the rival until the match is over (server timeouts also fire)
            var guard = 0
            while (guard++ < 200) {
                val st = a.s.state.value
                if (st != null && !st.room.inGame && st.room.lastMatchId != null) break
                for (p in c) {
                    val mm = p.s.mode.value ?: continue
                    val rival = c.first { it !== p }
                    if (mm.round?.phase != "done" && mm.round?.actorId == p.id)
                        runCatching { p.s.modeAct(mm.round!!.id, "fire", buildJsonObject { put("target", rival.id); put("nonce", nonce()) }) }
                }
                runCatching { a.backend.rpc("game_tick") { put("p_room_id", room) } }
                a.s.requestRefresh(); b.s.requestRefresh()
                delay(250)
            }
            val lobby = withTimeout(30_000) { a.s.state.first { it != null && !it.room.inGame && it.room.lastMatchId != null } }!!
            val matchId = lobby.room.lastMatchId!!
            assertEquals("three rounds", "3", psql("select count(*) from public.mode_rounds where match_id = '$matchId'"))
            val pts = psql("select string_agg(total_points::text, ',' order by total_points desc) from public.match_players where match_id = '$matchId'")
            assertTrue("champion has 2 or 3 rounds: $pts", pts == "20,10" || pts == "30,0")
            assertEquals("one bag per round", "3", psql("select count(*) from public.coin_transactions where source_key like 'buckshot_bag:$matchId:%'"))
            assertEquals("no coin ever taken from anybody", "0", psql("select count(*) from public.coin_transactions where amount < 0 and user_id in ('${a.id}','${b.id}')"))
        } finally {
            c.forEach { it.session?.stop() }
        }
    }
}
