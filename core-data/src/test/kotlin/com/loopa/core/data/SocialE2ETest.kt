package com.loopa.core.data

import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.model.InviteOutcome
import com.loopa.core.model.NotificationKind
import com.loopa.core.model.Relation
import com.loopa.core.network.LiveStatus
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Friends, realtime notifications, messages with read receipts, room invites and RLS through the
 * real Supabase stack (GoTrue sessions, PostgREST, Realtime) with three independent clients.
 * Updates between clients must arrive through the social realtime signal (no polling here).
 *
 *   LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<local key> ./gradlew :core-data:testDebugUnitTest
 */
class SocialE2ETest {
    private val url = System.getProperty("loopa.e2e.url").orEmpty()
    private val key = System.getProperty("loopa.e2e.key").orEmpty()

    private class Client(config: SupabaseConfig) {
        val backend = LoopaBackend(config, inMemorySession = true)
        val auth = AuthRepository(backend)
        val profiles = ProfileRepository(backend)
        val rooms = RoomRepository(backend)
        val social = SocialRepository(backend)
        val id get() = backend.currentUserId!!
    }

    private suspend fun client(name: String): Client {
        val c = Client(SupabaseConfig(url, key))
        c.auth.signInAnonymously()
        c.profiles.ensureProfile(name, null)
        c.social.startLive(c.id)
        withTimeout(15_000) { c.social.live.first { it == LiveStatus.LIVE } }
        return c
    }

    private suspend fun expect(error: LoopaError, block: suspend () -> Unit) {
        try { block(); fail("expected $error") } catch (e: LoopaException) { assertEquals(error, e.error) }
    }

    private suspend fun <T> await(what: String, timeoutMs: Long = 15_000, read: suspend () -> T, cond: (T) -> Boolean): T = try {
        withTimeout(timeoutMs) { while (true) { val v = read(); if (cond(v)) return@withTimeout v; kotlinx.coroutines.delay(50) }; error("unreachable") }
    } catch (e: Exception) { throw AssertionError("timed out waiting for: $what", e) }

    @Test
    fun friendsMessagesInvitesAndRls() = runBlocking {
        assumeTrue("local Supabase not configured", url.isNotBlank() && key.isNotBlank())
        val tag = (System.currentTimeMillis() % 100_000).toString()
        val a = client("Ada$tag"); val b = client("Bora$tag"); val c = client("Cansu$tag")
        try {
            // search: by name, never yourself
            val found = a.social.search("Bora$tag")
            assertEquals(listOf(b.id), found.map { it.userId })
            assertTrue(a.social.search("$tag").none { it.userId == a.id })
            expect(LoopaError.SEARCH_TOO_SHORT) { a.social.search("x") }

            // request -> B is told in real time (badge updated by the signal, no manual refresh)
            assertFalse(a.social.sendFriendRequest(b.id))
            withTimeout(10_000) { b.social.badges.first { it.requests == 1 && it.notifications == 1 } }
            expect(LoopaError.REQUEST_ALREADY_SENT) { a.social.sendFriendRequest(b.id) }
            val incoming = b.social.refreshOverview().data!!.incoming.single()
            assertEquals(a.id, incoming.user.userId)
            b.social.respondFriendRequest(incoming.requestId, accept = true)
            withTimeout(10_000) { a.social.badges.first { it.notifications == 1 } }
            val notes = a.social.refreshNotifications().data!!
            assertEquals(NotificationKind.FRIEND_ACCEPTED, notes.first().kind)
            assertEquals(Relation.FRIEND, a.social.profile(b.id).relation)

            // mutual requests at the same time end as one friendship
            coroutineScope {
                val x = async { runCatching { b.social.sendFriendRequest(c.id) } }
                val y = async { runCatching { c.social.sendFriendRequest(b.id) } }
                x.await(); y.await()
            }
            assertEquals(Relation.FRIEND, b.social.profile(c.id).relation)
            assertTrue(b.social.refreshOverview().data!!.incoming.none { it.user.userId == c.id })

            // messages: non-friends cannot write; friends can, with read receipts through the signal
            expect(LoopaError.NOT_FRIENDS) { c.social.sendMessage(a.id, "selam", UUID.randomUUID().toString()) }
            val cid = UUID.randomUUID().toString()
            val m1 = a.social.sendMessage(b.id, "Selam Bora!", cid)
            assertEquals(m1.id, a.social.sendMessage(b.id, "Selam Bora!", cid).id)   // retry is idempotent
            withTimeout(10_000) { b.social.badges.first { it.messages == 1 } }
            val conv = b.social.refreshConversations().data!!.single()
            assertEquals(a.id, conv.userId); assertEquals(1, conv.unread); assertEquals("Selam Bora!", conv.lastMessage?.body)
            b.social.markConversationRead(a.id)
            await("read receipt", read = { a.social.messages(b.id).first() }) { it.readAt != null }
            assertTrue(c.social.messages(a.id).isEmpty())

            // RLS through PostgREST: C sees none of A-B's rows and cannot write directly
            val visible = c.backend.requireClient().postgrest.from("direct_messages").select().decodeList<JsonObject>()
            assertTrue("C must not see A-B messages", visible.isEmpty())
            val notesSeen = c.backend.requireClient().postgrest.from("notifications").select().decodeList<JsonObject>()
            assertTrue(notesSeen.all { (it["user_id"] as kotlinx.serialization.json.JsonPrimitive).content == c.id })
            val inserted = runCatching {
                LoopaBackend.translate {
                    c.backend.requireClient().postgrest.from("direct_messages").insert(buildJsonObject {
                        put("sender_id", a.id); put("recipient_id", b.id); put("body", "spoof")
                    })
                }
            }
            assertTrue("direct insert must be refused", inserted.isFailure)

            // room invite: A invites B, B accepts and lands in the same room
            val room = a.rooms.createRoom(false)
            a.social.sendRoomInvite(b.id, room.id)
            withTimeout(10_000) { b.social.badges.first { it.invites == 1 } }
            val invite = b.social.refreshOverview().data!!.invites.single()
            assertEquals(room.code, invite.roomCode)
            val outcome = b.social.respondRoomInvite(invite.inviteId, accept = true)
            assertEquals(InviteOutcome.Joined(room.id), outcome)
            assertEquals(room.id, b.rooms.activeRoom()?.id)
            expect(LoopaError.ALREADY_IN_ROOM) { a.social.sendRoomInvite(b.id, room.id) }
            expect(LoopaError.NOT_IN_ROOM) { c.social.sendRoomInvite(a.id, room.id) }   // only room members can invite

            // block: friendship gone, messaging closed, blocker invisible to the blocked user
            a.social.block(c.id)
            expect(LoopaError.USER_NOT_FOUND) { c.social.profile(a.id) }
            assertTrue(c.social.search("Ada$tag").isEmpty())
            a.social.unblock(c.id)
            assertNotNull(c.social.profile(a.id))

            // presence sharing is a real server setting
            a.social.setSharePresence(false)
            assertFalse(b.social.profile(a.id).presence.visible)
            a.social.setSharePresence(true)
        } finally {
            listOf(a, b, c).forEach { it.social.stopLive() }
        }
    }
}
