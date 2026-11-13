package com.loopa.core.data

import com.loopa.core.game.GameCardKind
import com.loopa.core.game.GameCards
import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.model.PlanTier
import com.loopa.core.model.RoomState
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.LoopaJson
import com.loopa.core.network.SupabaseConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.json.put

/**
 * Membership, promo and catalogue repositories against a running LOCAL Supabase stack with the
 * stage-2 migrations, using the same classes as the app.
 *
 *   LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<local demo anon key> \
 *   LOOPA_E2E_DB=postgresql://postgres:postgres@127.0.0.1:54322/postgres ./gradlew :core-data:testDebugUnitTest
 *
 * LOOPA_E2E_DB (optional, local only) lets the test do what the dashboard SQL editor and GoTrue
 * would do: switch the promo code on and attach a Google identity (a real Google sign-in cannot run
 * here). Everything it creates is removed afterwards.
 */
class PlusRepositoriesE2ETest {
    private val url = System.getProperty("loopa.e2e.url").orEmpty()
    private val key = System.getProperty("loopa.e2e.key").orEmpty()
    private val db = System.getProperty("loopa.e2e.db").orEmpty()

    private fun psql(sql: String): String {
        val p = ProcessBuilder("psql", db, "-v", "ON_ERROR_STOP=1", "-qtA", "-c", sql).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor() == 0) { "psql failed: $out" }
        return out
    }

    private suspend fun expectError(expected: LoopaError, block: suspend () -> Unit) {
        try { block(); fail("expected $expected") } catch (e: LoopaException) { assertEquals(expected, e.error) }
    }

    @Test
    fun membershipPromoAndCatalogue() = runBlocking {
        assumeTrue("LOOPA_E2E_URL not set — skipping", url.isNotBlank() && key.isNotBlank())
        assumeTrue("only against a local stack", url.contains("127.0.0.1") || url.contains("localhost"))
        val backend = LoopaBackend(SupabaseConfig(url, key), inMemorySession = true)
        val auth = AuthRepository(backend)
        val profiles = ProfileRepository(backend)
        val membership = MembershipRepository(backend)
        val catalog = GameCatalogRepository(backend)
        val rooms = RoomRepository(backend)

        auth.signInAnonymously()
        profiles.ensureProfile("Plus${(1000..9999).random()}", null)
        val uid = backend.currentUserId!!
        try {
            // ---- membership: a guest is Free, not Google-linked ----
            val m = membership.refresh()
            assertNull(m.error); assertFalse(m.loading)
            assertEquals(PlanTier.FREE, m.data!!.tier)
            assertFalse(m.data!!.googleLinked)

            // ---- catalogue: 10 games, only İsim Şehir startable, rotation not started ----
            val c = catalog.refresh().data!!
            assertEquals(10, c.modes.size)
            assertEquals(GameModes.all.map { it.id }, c.modes.map { it.id })
            assertFalse(c.rotation.active)
            GameModes.all.forEach { info ->
                val s = GameCards.status(info, c)
                if (info.id == GameModes.ISIM_SEHIR) assertTrue(s.canStart) else {
                    // 0.5.0: every game exists; a Free host without an active rotation week needs Plus.
                    assertEquals(info.id, GameCardKind.LOCKED_PLUS, s.kind); assertFalse(s.canStart)
                }
            }

            // ---- promo: wrong input is rejected by the server, nothing changes ----
            expectError(LoopaError.PROMO_INVALID) { membership.redeem("   ") }        // never sent
            expectError(LoopaError.PROMO_INVALID) { membership.redeem("LOPAWEEK") }
            expectError(LoopaError.PROMO_INVALID) { membership.redeem("LOPPA WEEK") }
            expectError(LoopaError.PROMO_INVALID) { membership.redeem("BEDAVAPLUS") }
            assertEquals(PlanTier.FREE, membership.refresh().data!!.tier)

            // ---- rooms keep working and carry the mode ----
            val room = rooms.createRoom(false)
            val state = LoopaJson.decodeFromJsonElement(RoomState.serializer(), backend.rpc("get_room_state") { put("p_room_id", room.id) })
            assertEquals("isim_sehir", state.room.settings.gameMode)
            // The app sends the full settings object (encodeDefaults) including game_mode: accepted.
            backend.rpc("update_room_settings") {
                put("p_room_id", room.id)
                put("p_settings", LoopaJson.encodeToJsonElement(com.loopa.core.model.RoomSettings.serializer(), state.room.settings.copy(rounds = 3)))
                put("p_is_public", false)
            }
            // A Free host cannot switch to a locked game: the server refuses.
            expectError(LoopaError.PLUS_REQUIRED) {
                backend.rpc("update_room_settings") {
                    put("p_room_id", room.id)
                    put("p_settings", LoopaJson.encodeToJsonElement(com.loopa.core.model.RoomSettings.serializer(), state.room.settings.copy(gameMode = "gizli_kelime")))
                    put("p_is_public", false)
                }
            }
            backend.rpc("close_room") { put("p_room_id", room.id) }

            // ---- an RPC the server does not have => SERVER_OUTDATED, not "network error" ----
            expectError(LoopaError.SERVER_OUTDATED) { backend.rpc("get_feature_from_the_future") }

            if (db.isBlank()) { println("LOOPA_E2E_DB not set: skipped the Google-linked promo part"); return@runBlocking }

            // ---- console switches LOPPAWEEK on; a guest still cannot use it ----
            psql("update public.promo_codes set active = true where code = 'LOPPAWEEK'")
            expectError(LoopaError.GOOGLE_LINK_REQUIRED) { membership.redeem("LOPPAWEEK") }

            // ---- what GoTrue stores after linking Google ----
            psql("""insert into auth.identities(provider_id, user_id, identity_data, provider, created_at, updated_at)
                    values ('g-$uid', '$uid', jsonb_build_object('sub','g-$uid','email','e2e-$uid@loopa-test.invalid','email_verified',true), 'google', now(), now());
                    update auth.users set is_anonymous = false where id = '$uid';""")
            assertTrue(membership.refresh().data!!.googleLinked)

            val r = membership.redeem(" loppaweek ")
            assertTrue(r.isNew); assertEquals(7, r.days); assertNotNull(r.plusUntil)
            // The repository re-read the plan: every screen now sees Plus with the server's end date.
            val after = membership.state.value.data!!
            assertEquals(PlanTier.PLUS, after.tier)
            assertEquals(r.plusUntil, after.plusUntil)
            val again = membership.redeem("LOPPAWEEK")
            assertFalse(again.isNew)
            assertEquals(r.plusUntil, again.plusUntil)
            assertEquals(1, membership.state.value.data!!.entitlements.size)

            // Plus host: the catalogue now reports Plus access and every game can be started.
            val pc = catalog.refresh().data!!
            assertEquals("plus", pc.plan)
            GameModes.all.forEach { assertTrue(it.id, GameCards.status(it, pc).canStart) }
            assertTrue(GameCards.status(GameModes.info(GameModes.ISIM_SEHIR), pc).canStart)
        } finally {
            if (db.isNotBlank()) psql("""
                update public.promo_codes set active = false,
                    redemptions = greatest(redemptions - (select count(*) from public.promo_redemptions where user_id = '$uid'), 0)
                 where code = 'LOPPAWEEK';
                delete from public.promo_redemptions where user_id = '$uid';
                delete from public.rooms where host_id = '$uid';
                delete from auth.users where id = '$uid';""")
            runCatching { auth.signOut() }
        }
    }
}
