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
 * 0.8.0 against a LOCAL Supabase stack: the room setup flow, 21 played to the end by two real
 * clients (no betting action exists), a Krallıklar turn with four clients (hidden orders, fog of
 * war), match coin rewards, and the platform reads (progression, wallet, quests, leaderboards,
 * tournaments) decoded with the app's own models.
 *
 *   LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<local demo key> \
 *   LOOPA_E2E_DB=postgresql://postgres:postgres@127.0.0.1:54322/postgres ./gradlew :core-data:testDebugUnitTest
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewGamesE2ETest {
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

    @Test
    fun setupFlowTwentyOneKralliklarAndPlatform() = runBlocking {
        assumeTrue("LOOPA_E2E_URL not set — skipping", url.isNotBlank() && key.isNotBlank())
        assumeTrue("only against a local stack", url.contains("127.0.0.1") || url.contains("localhost"))
        assumeTrue("LOOPA_E2E_DB needed to give the hosts Plus", db.isNotBlank())
        val cfg = SupabaseConfig(url, key)
        val c = listOf("Ece", "Fırat", "Gül", "Hakan").map { Client(it, cfg) }
        c.forEach { it.auth.signInAnonymously(); it.profiles.ensureProfile("${it.name}${(100..999).random()}", null) }
        val (a, b, cc, d) = c
        psql("insert into public.entitlements(user_id, source, starts_at, ends_at) select u::uuid, 'admin', now() - interval '1 hour', now() + interval '1 day' from unnest(array['${c.joinToString("','") { it.id }}']) u")
        // the anti-farm "too short" rule would hide the reward of a fast test match
        val oldRules = psql("select value::text from public.economy_config where key = 'match_rewards'")
        psql("update public.economy_config set value = jsonb_set(value, '{duel,min_seconds}', '0') where key = 'match_rewards'")
        try {
            // ================================================== 21: two clients, stand until the match ends
            val room21 = start("yirmi_bir", a, listOf(b), buildJsonObject { put("hands", 3); put("turn_seconds", 30) }, 2)
            var m = a.awaitMode("21 hand") { it.round?.public?.get("players") is JsonArray }
            assertTrue((m.me["hand"] as JsonArray).size == 2)
            val shown = (m.round!!.public["shown"] as JsonObject)[b.id] as JsonArray
            assertEquals("only the opponent's first card is visible", 1, shown.size)
            var guard = 0
            while (guard++ < 40) {
                val st = a.s.state.value
                if (st != null && !st.room.inGame && st.room.lastMatchId != null) break
                for (p in listOf(a, b)) {
                    val mm = p.s.mode.value ?: continue
                    if (mm.round?.phase != "done" && mm.round?.actorId == p.id) {
                        try { p.s.modeAct(mm.round!!.id, "stand") } catch (_: LoopaException) {}
                        try { p.s.modeAct(mm.round!!.id, "bet", buildJsonObject { put("coins", 100) }); fail("no betting action") }
                        catch (e: LoopaException) { assertTrue(e.error == LoopaError.INVALID_ACTION || e.error == LoopaError.NOT_YOUR_TURN || e.error == LoopaError.PHASE_CLOSED) }
                    }
                }
                psql("update public.mode_rounds set deadline_at = least(deadline_at, now() + interval '1 second') where room_id = '$room21' and phase = 'done'")
                runCatching { a.backend.rpc("game_tick") { put("p_room_id", room21) } }
                a.s.requestRefresh(); b.s.requestRefresh()
                delay(600)
            }
            val lobby = withTimeout(30_000) { a.s.state.first { it != null && !it.room.inGame && it.room.lastMatchId != null } }!!
            val matchId = lobby.room.lastMatchId!!
            val reward = withTimeout(10_000) { var r = a.platform.matchRewards(matchId); while (r == null) { delay(300); r = a.platform.matchRewards(matchId) }; r }
            assertEquals("yirmi_bir", reward.gameMode)
            assertTrue("a finished 2-player match is rewarded", reward.valid)
            assertTrue(reward.coins >= 0)
            runCatching { a.s.closeRoom() }; a.session?.stop(); b.session?.stop()

            // ================================================== Krallıklar: hidden orders of four kingdoms, one turn
            val roomK = start("kralliklar", a, listOf(b, cc, d), buildJsonObject { put("max_turns", 60) }, 4)
            m = a.awaitMode("kingdom view") { it.me["tiles"] is JsonArray }
            val pub = m.round!!.public
            assertEquals(200, (pub["map"] as JsonArray).size)
            assertTrue("tile contents are not public", pub["tiles"] == null || pub["tiles"] is kotlinx.serialization.json.JsonNull)
            val myTiles = (m.me["tiles"] as JsonArray).map { it as JsonObject }
            assertTrue("fog: far less than the whole map is visible", myTiles.size in 7..40)
            val cap = (m.me["capital"] as JsonPrimitive).content.toInt()
            val ring = myTiles.first { it.str("i")!!.toInt() != cap && it.str("o") == m.me.str("k") }.str("i")!!.toInt()
            a.s.modeAct(m.round!!.id, "orders", buildJsonObject {
                put("moves", JsonArray(listOf(buildJsonObject { put("from", cap); put("to", ring); put("n", 5) })))
                put("builds", JsonArray(emptyList()))
            })
            // the other kingdoms cannot see A's orders
            val bv = b.awaitMode("b view") { it.me["orders"] != null }
            assertFalse(bv.toString().contains("\"to\":$ring,\"n\":5"))
            for (p in c) { val mm = p.s.mode.value!!; p.s.modeAct(mm.round!!.id, "ready") }
            // everybody ready: the planning ends early; the server resolves the turn on the next tick
            var turns = 0
            while (turns++ < 30 && (a.s.mode.value?.round?.public?.get("turn_no") as? JsonPrimitive)?.content != "2") {
                delay(700)
                runCatching { a.backend.rpc("game_tick") { put("p_room_id", roomK) } }
                a.s.requestRefresh()
            }
            val after = a.awaitMode("turn 2", 20_000) { (it.round?.public?.get("turn_no") as? JsonPrimitive)?.content == "2" }
            val ringTile = (after.me["tiles"] as JsonArray).map { it as JsonObject }.first { it.str("i")!!.toInt() == ring }
            assertTrue("reinforcement arrived", ringTile.str("x")!!.toInt() >= 5)
            runCatching { a.s.closeRoom() }; c.forEach { it.session?.stop() }

            // ================================================== platform reads with the app's models
            val prog = a.platform.refreshProgression()
            assertEquals(a.id, prog.userId)
            assertNotNull(prog.coins)
            val wallet = a.platform.wallet()
            assertEquals(prog.coins, wallet.coins)
            val quests = a.platform.quests()
            assertTrue(quests.quests.isNotEmpty())
            val weekly = a.platform.leaderboard("weekly")
            assertEquals("weekly", weekly.kind)
            assertNotNull(weekly.me)
            val total = a.platform.leaderboard("total", limit = 10)
            assertTrue(total.rows.size <= 10)
            val prefs = a.platform.gamePrefs()
            a.platform.setGamePrefs(listOf(prefs.tags.first()), emptyList())
            assertEquals(listOf(prefs.tags.first()), a.platform.gamePrefs().likes)
            val t = a.platform.createTournament("E2E Kupası", "tavla", "duel", 4, ModeOptions.normalize("tavla", JsonObject(emptyMap())))
            val tid = (t as JsonObject).str("id")!!
            b.platform.joinTournament(tid)
            val detail = a.platform.tournament(tid)
            assertEquals("registration", detail.status)
            assertTrue(detail.entries.any { it.players.any { p -> p.userId == b.id } })
            assertTrue(a.platform.tournaments().any { it.id == tid })
            a.platform.cancelTournament(tid)
            // a coin purchase can never make the balance negative
            val priced = psql("select id from public.cosmetic_items where price_coins > 0 order by price_coins desc limit 1")
            psql("update public.wallets set coins = 0 where user_id = '${a.id}'")
            try { a.platform.purchaseCosmetic(priced); fail("purchase without coins") } catch (e: LoopaException) { assertEquals(LoopaError.INSUFFICIENT_COINS, e.error) }
            assertEquals(0, a.platform.wallet().coins)
        } finally {
            psql("update public.economy_config set value = '$oldRules'::jsonb where key = 'match_rewards'")
            c.forEach { it.session?.stop() }
        }
    }
}
