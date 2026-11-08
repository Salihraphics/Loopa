package com.loopa.core.data

import com.loopa.core.game.LoopaError
import com.loopa.core.model.RoomState
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.SupabaseConfig
import io.github.jan.supabase.auth.providers.Keycloak
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Guest → Google account linking against a real Supabase Auth server.
 *
 * Google itself cannot be scripted, so the local stack enables Supabase's
 * Keycloak (OIDC) provider pointed at tools/auth-test/fake_oidc.py. The app code
 * under test is identical — AuthRepository.linkGoogle / signInWithGoogle /
 * handleCallback with `linkIdentity`, PKCE and the loopa://auth-callback redirect —
 * only the provider object differs (Keycloak instead of Google).
 *
 * Run: LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<publishable> LOOPA_E2E_OAUTH=keycloak \
 *      ./gradlew :core-data:testDebugUnitTest --tests '*AccountLinking*'
 */
class AccountLinkingE2ETest {
    private val url = System.getProperty("loopa.e2e.url").orEmpty()
    private val key = System.getProperty("loopa.e2e.key").orEmpty()
    private val oauth = System.getProperty("loopa.e2e.oauth").orEmpty()

    /** One "device": its own SDK instance and in-memory session. */
    private inner class Device(val name: String) {
        var lastAuthUrl: String? = null
        val backend = LoopaBackend(SupabaseConfig(url, key), inMemorySession = true, oauthProvider = Keycloak, urlOpener = { lastAuthUrl = it })
        val auth = AuthRepository(backend)
        val profiles = ProfileRepository(backend)
        val rooms = RoomRepository(backend)

        /** Plays the browser: follows redirects through Supabase and the identity provider until loopa://. */
        fun completeConsent(asUser: String): String {
            var next = lastAuthUrl ?: error("$name: no auth URL was opened")
            lastAuthUrl = null
            repeat(10) {
                var target = next.replace("host.docker.internal", "127.0.0.1")
                if (target.contains("/protocol/openid-connect/auth")) target += "&fake_user=$asUser"
                val conn = URL(target).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code !in 300..399 || location == null) error("$name: unexpected $code at $target: ${conn.errorStream?.readBytes()?.decodeToString()}")
                if (location.startsWith(LoopaBackend.OAUTH_REDIRECT)) return location
                next = if (location.startsWith("http")) location else URL(URL(target), location).toString()
            }
            error("$name: too many redirects")
        }
    }

    private fun log(m: String) = println("[LINK] $m")

    @Test
    fun guestLinksGoogleAndKeepsEverything() = runBlocking {
        assumeTrue("set LOOPA_E2E_URL, LOOPA_E2E_ANON_KEY and LOOPA_E2E_OAUTH=keycloak", url.isNotBlank() && key.isNotBlank() && oauth == "keycloak")
        val run = System.currentTimeMillis().toString().takeLast(6)
        val googleUser = "alice$run"

        // ---------------------------------------------------------------- 1) guest account with real progress
        val phone = Device("phone")
        phone.auth.signInAnonymously()
        val guestId = phone.auth.userId!!
        val avatar = JsonObject(mapOf("base" to JsonPrimitive("base_oval"), "hair" to JsonPrimitive("hair_bob"), "hat" to JsonPrimitive("hat_cap"), "background" to JsonPrimitive("bg_mint")))
        phone.profiles.ensureProfile("Misafir $run", avatar)
        val friend = Device("friend")
        friend.auth.signInAnonymously(); friend.profiles.ensureProfile("Arkadas $run", null)
        playQuickMatch(phone, friend)
        val before = phone.profiles.refresh()!!
        val statusBefore = phone.auth.accountStatus()!!
        assertTrue("guest is anonymous", statusBefore.isAnonymous)
        assertFalse(statusBefore.hasGoogle)
        assertTrue("guest earned XP", before.xp > 0)
        assertEquals("guest has match history", 1, before.history.size)
        log("guest $guestId: level=${before.level} xp=${before.xp} history=${before.history.size} cosmetics=${before.ownedCosmetics.size}")

        // ---------------------------------------------------------------- 2) link the Google identity (manual linking)
        phone.auth.linkGoogle()
        assertNotNull("link opened the consent page", phone.lastAuthUrl)
        val linked = phone.auth.handleCallback(phone.completeConsent(googleUser))
        assertEquals("linked, not a new user", AuthCallbackResult.Linked(guestId), linked)
        assertEquals("same user id after linking", guestId, phone.auth.userId)
        val statusAfter = phone.auth.accountStatus()!!
        assertFalse("no longer anonymous", statusAfter.isAnonymous)
        assertTrue("identity attached", "keycloak" in statusAfter.providers)
        assertEquals("$googleUser@loopa.test", statusAfter.email)
        val after = phone.profiles.refresh()!!
        assertProfileKept(before, after, "after linking")
        log("linked: same id, providers=${statusAfter.providers}, email=${statusAfter.email}")

        // ---------------------------------------------------------------- 3) a second device signs in with the same identity
        val tablet = Device("tablet")
        tablet.auth.signInWithGoogle()
        val signedIn = tablet.auth.handleCallback(tablet.completeConsent(googleUser))
        assertEquals(AuthCallbackResult.SignedIn(guestId, switchedFrom = null), signedIn)
        val onTablet = tablet.profiles.refresh()!!
        assertProfileKept(before, onTablet, "on another device")
        log("tablet signed in as $guestId and sees level=${onTablet.level} xp=${onTablet.xp} history=${onTablet.history.size}")

        // ---------------------------------------------------------------- 4) the same identity cannot be linked to another guest
        val other = Device("other")
        other.auth.signInAnonymously()
        val otherId = other.auth.userId!!
        other.profiles.ensureProfile("Baska $run", null)
        other.auth.linkGoogle()
        val conflict = other.auth.handleCallback(other.completeConsent(googleUser))
        assertTrue("conflict reported: $conflict", conflict is AuthCallbackResult.Failed && conflict.error == LoopaError.IDENTITY_ALREADY_LINKED)
        assertEquals("other guest kept its own session", otherId, other.auth.userId)
        assertTrue("other guest still anonymous", other.auth.accountStatus()!!.isAnonymous)
        assertEquals("other guest profile untouched", "Baska $run", other.profiles.refresh()!!.username)
        assertProfileKept(before, phone.profiles.refresh()!!, "after the refused link")
        log("conflict -> ${(conflict as AuthCallbackResult.Failed).error}; guest $otherId untouched")

        // ---------------------------------------------------------------- 5) cancelled consent changes nothing
        val cancel = Device("cancel")
        cancel.auth.signInAnonymously(); val cancelId = cancel.auth.userId!!
        cancel.auth.linkGoogle()
        val cancelled = cancel.auth.handleCallback(cancel.completeConsent("cancel"))
        assertTrue("cancel reported: $cancelled", cancelled is AuthCallbackResult.Failed && cancelled.error == LoopaError.AUTH_CANCELLED)
        assertEquals(cancelId, cancel.auth.userId)

        // ---------------------------------------------------------------- 6) safe sign-out on one device, other device unaffected, sign back in
        tablet.auth.signOut()
        assertNull("tablet signed out", tablet.auth.userId)
        assertNotNull("phone still works", phone.profiles.refresh())
        tablet.auth.signInWithGoogle()
        assertEquals(AuthCallbackResult.SignedIn(guestId, null), tablet.auth.handleCallback(tablet.completeConsent(googleUser)))
        assertProfileKept(before, tablet.profiles.refresh()!!, "after signing in again")
        log("sign-out / sign-in again OK")
    }

    private fun assertProfileKept(before: com.loopa.core.model.ProfileOverview, now: com.loopa.core.model.ProfileOverview, where: String) {
        assertEquals("id $where", before.id, now.id)
        assertEquals("username $where", before.username, now.username)
        assertEquals("xp $where", before.xp, now.xp)
        assertEquals("level $where", before.level, now.level)
        assertEquals("avatar $where", before.avatar, now.avatar)
        assertEquals("cosmetics $where", before.ownedCosmetics.toSet(), now.ownedCosmetics.toSet())
        assertEquals("match history $where", before.history, now.history)
        assertEquals("statistics $where", before.stats, now.stats)
    }

    /** Smallest real match (3 rounds) so the guest has XP, statistics and history. */
    private suspend fun playQuickMatch(host: Device, guest: Device) {
        val ref = host.rooms.createRoom(false)
        guest.rooms.joinRoom(ref.code)
        val settings = state(host, ref.id).room.settings.copy(rounds = 3, roundSeconds = 30)
        host.backend.rpc("update_room_settings") {
            put("p_room_id", ref.id); put("p_settings", com.loopa.core.network.LoopaJson.encodeToJsonElement(com.loopa.core.model.RoomSettings.serializer(), settings)); put("p_is_public", false)
        }
        guest.backend.rpc("set_ready") { put("p_room_id", ref.id); put("p_ready", true) }
        host.backend.rpc("start_match") { put("p_room_id", ref.id) }
        repeat(3) { i ->
            var s = state(host, ref.id)
            while (s.round?.number != i + 1 || s.round?.phase != com.loopa.core.model.RoundPhase.ANSWERING) { delay(300); s = state(host, ref.id) }
            delay(4_300) // letter reveal
            val r = s.round!!
            for ((d, word) in listOf(host to "Kalem", guest to "Kitap")) {
                val answers = r.categories.associate { it.key to (r.letter + word.drop(1)) }
                d.backend.rpc("submit_answers") { put("p_round_id", r.id); put("p_answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) })) }
            }
            // vote to accept everything pending, then skip reveal/results
            for (d in listOf(host, guest)) {
                val st = state(d, ref.id)
                st.round?.answers?.filter { it.isPendingVote && it.userId != d.backend.currentUserId }?.forEach { a ->
                    runCatching { d.backend.rpc("cast_vote") { put("p_answer_id", a.id); put("p_accept", true) } }
                }
            }
            repeat(3) { runCatching { host.backend.rpc("skip_reveal") { put("p_room_id", ref.id) } }; delay(200) }
        }
        var s = state(host, ref.id)
        while (s.room.inGame) { delay(300); runCatching { host.backend.rpc("game_tick") { put("p_room_id", ref.id) } }; s = state(host, ref.id) }
        host.backend.rpc("leave_room") { put("p_room_id", ref.id) }
        guest.backend.rpc("leave_room") { put("p_room_id", ref.id) }
    }

    private suspend fun state(d: Device, roomId: String): RoomState =
        d.backend.rpc("get_room_state", RoomState.serializer()) { put("p_room_id", roomId) }
}
