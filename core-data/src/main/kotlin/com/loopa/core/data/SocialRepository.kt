package com.loopa.core.data

import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.model.AppNotification
import com.loopa.core.model.Conversation
import com.loopa.core.model.DirectMessage
import com.loopa.core.model.InviteOutcome
import com.loopa.core.model.SocialBadges
import com.loopa.core.model.SocialOverview
import com.loopa.core.model.UserProfileView
import com.loopa.core.model.UserSearchResult
import com.loopa.core.network.LiveStatus
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.SocialSignalListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Friends, invites, messages and notifications. Every call is an RPC; the server takes the caller
 * from the session and checks the relationship, so nothing here is trusted for security.
 *
 * Live updates: one [SocialSignalListener] per signed-in user. A signal re-fetches the badges and
 * whatever lists were loaded, and is re-emitted on [changes] for open chats.
 */
class SocialRepository(private val backend: LoopaBackend) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _overview = MutableStateFlow(Remote<SocialOverview>())
    val overview: StateFlow<Remote<SocialOverview>> = _overview.asStateFlow()
    private val _badges = MutableStateFlow(SocialBadges())
    val badges: StateFlow<SocialBadges> = _badges.asStateFlow()
    private val _conversations = MutableStateFlow(Remote<List<Conversation>>())
    val conversations: StateFlow<Remote<List<Conversation>>> = _conversations.asStateFlow()
    private val _notifications = MutableStateFlow(Remote<List<AppNotification>>())
    val notifications: StateFlow<Remote<List<AppNotification>>> = _notifications.asStateFlow()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires after any social change for the signed-in user (new message, read receipt, request ...). */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()
    private val _live = MutableStateFlow(LiveStatus.OFFLINE)
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    private var listener: SocialSignalListener? = null
    private var listenerUser: String? = null
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        // Bursts of signals (e.g. several messages) collapse into one refresh.
        scope.launch {
            for (s in signals) {
                refreshAfterSignal()
                delay(250)
            }
        }
    }

    // ------------------------------------------------------------------ live
    /** Starts listening for [userId]'s signal row. Idempotent; switches user when the account changes. */
    fun startLive(userId: String) {
        if (listenerUser == userId && listener != null) return
        scope.launch {
            listener?.stop()
            val l = SocialSignalListener(backend, userId, scope) { signals.trySend(Unit) }
            listener = l; listenerUser = userId
            l.start()
            l.status.collect { _live.value = it }
        }
    }

    fun stopLive() {
        val l = listener ?: return
        listener = null; listenerUser = null
        scope.launch { l.stop(); _live.value = LiveStatus.OFFLINE }
    }

    /** Re-fetch as if a signal arrived (pull-to-refresh, returning to the app). */
    fun poke() { signals.trySend(Unit) }

    private suspend fun refreshAfterSignal() {
        runCatching { refreshBadges() }
        if (_overview.value.data != null) runCatching { refreshOverview() }
        if (_conversations.value.data != null) runCatching { refreshConversations() }
        if (_notifications.value.data != null) runCatching { refreshNotifications() }
        _changes.tryEmit(Unit)
    }

    // ------------------------------------------------------------------ presence
    /**
     * Keeps "online" fresh while the app is in the foreground. Cancel the calling coroutine when the
     * app goes to the background: the server is then told the user left (best effort).
     */
    suspend fun runPresence() {
        try {
            while (true) {
                runCatching { backend.rpc("touch_presence") { put("p_active", true) } }
                delay(PRESENCE_INTERVAL_MS)
            }
        } finally {
            withContext(NonCancellable) {
                scope.launch { runCatching { backend.rpc("touch_presence") { put("p_active", false) } } }
            }
        }
    }

    suspend fun setSharePresence(share: Boolean) {
        backend.rpc("set_presence_sharing") { put("p_share", share) }
        _overview.update { r -> r.copy(data = r.data?.copy(sharePresence = share)) }
    }

    // ------------------------------------------------------------------ loads
    suspend fun refreshOverview(): Remote<SocialOverview> = load(_overview) {
        backend.rpc("get_social_overview", SocialOverview.serializer())
    }

    suspend fun refreshBadges(): SocialBadges {
        val b = backend.rpc("get_social_badges", SocialBadges.serializer())
        _badges.value = b
        return b
    }

    suspend fun refreshConversations(): Remote<List<Conversation>> = load(_conversations) {
        backend.rpc("list_conversations", ListSerializer(Conversation.serializer()))
    }

    suspend fun refreshNotifications(): Remote<List<AppNotification>> = load(_notifications) {
        backend.rpc("list_notifications", ListSerializer(AppNotification.serializer())) { put("p_limit", 50) }
    }

    private suspend fun <T> load(flow: MutableStateFlow<Remote<T>>, fetch: suspend () -> T): Remote<T> = lock.withLock {
        flow.update { it.copy(loading = true) }
        val next = try {
            Remote(data = fetch(), loading = false, error = null)
        } catch (e: CancellationException) {
            flow.update { it.copy(loading = false) }; throw e
        } catch (e: LoopaException) {
            flow.value.copy(loading = false, error = e.error)
        }
        flow.value = next
        next
    }

    // ------------------------------------------------------------------ friends
    suspend fun search(query: String): List<UserSearchResult> =
        backend.rpc("search_users", ListSerializer(UserSearchResult.serializer())) { put("p_query", query.trim()) }

    suspend fun profile(userId: String): UserProfileView =
        backend.rpc("get_user_profile", UserProfileView.serializer()) { put("p_user", userId) }

    /** @return true when this completed a friendship (they had already asked you). */
    suspend fun sendFriendRequest(userId: String): Boolean {
        val r = backend.rpc("send_friend_request") { put("p_user", userId) }
        afterChange()
        return status(r) == "accepted"
    }

    suspend fun respondFriendRequest(requestId: String, accept: Boolean) {
        backend.rpc("respond_friend_request") { put("p_request_id", requestId); put("p_accept", accept) }
        afterChange()
    }

    suspend fun cancelFriendRequest(requestId: String) {
        backend.rpc("cancel_friend_request") { put("p_request_id", requestId) }
        afterChange()
    }

    suspend fun removeFriend(userId: String) { backend.rpc("remove_friend") { put("p_user", userId) }; afterChange() }
    suspend fun block(userId: String) { backend.rpc("block_user") { put("p_user", userId) }; afterChange() }
    suspend fun unblock(userId: String) { backend.rpc("unblock_user") { put("p_user", userId) }; afterChange() }

    // ------------------------------------------------------------------ invites
    suspend fun sendRoomInvite(userId: String, roomId: String) {
        backend.rpc("send_room_invite") { put("p_user", userId); put("p_room_id", roomId) }
    }

    suspend fun respondRoomInvite(inviteId: String, accept: Boolean): InviteOutcome {
        val r = backend.rpc("respond_room_invite") { put("p_invite_id", inviteId); put("p_accept", accept) }
        afterChange()
        return when (status(r)) {
            "joined" -> InviteOutcome.Joined(((r as JsonObject)["room_id"] as JsonPrimitive).content)
            "declined" -> InviteOutcome.Declined
            "expired" -> InviteOutcome.Expired
            "room_gone" -> InviteOutcome.RoomGone
            else -> InviteOutcome.AlreadyAnswered
        }
    }

    // ------------------------------------------------------------------ messages
    /** Newest first; [before] pages back from a message id. */
    suspend fun messages(userId: String, before: Long? = null, limit: Int = 50): List<DirectMessage> =
        backend.rpc("get_messages", ListSerializer(DirectMessage.serializer())) {
            put("p_user", userId)
            if (before != null) put("p_before", before)
            put("p_limit", limit)
        }

    /** Idempotent per [clientId]: retrying after a timeout never sends the message twice. */
    suspend fun sendMessage(userId: String, body: String, clientId: String): DirectMessage =
        backend.rpc("send_message", DirectMessage.serializer()) {
            put("p_user", userId); put("p_body", body); put("p_client_id", clientId)
        }

    suspend fun markConversationRead(userId: String) {
        val n = backend.rpc("mark_conversation_read") { put("p_user", userId) }
        if ((n as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 > 0) runCatching { refreshBadges() }
    }

    // ------------------------------------------------------------------ notifications
    suspend fun markNotificationsRead(ids: List<Long>? = null) {
        backend.rpc("mark_notifications_read") {
            if (ids != null) putJsonArray("p_ids") { ids.forEach { add(JsonPrimitive(it)) } }
        }
        _notifications.update { r ->
            val now = java.time.Instant.now().toString()
            r.copy(data = r.data?.map { n -> if (ids == null || n.id in ids) n.copy(readAt = n.readAt ?: now) else n })
        }
        runCatching { refreshBadges() }
    }

    /** Clears everything (sign-out / account switch). */
    fun clear() {
        stopLive()
        _overview.value = Remote(); _badges.value = SocialBadges()
        _conversations.value = Remote(); _notifications.value = Remote()
    }

    private suspend fun afterChange() {
        runCatching { refreshBadges() }
        if (_overview.value.data != null) runCatching { refreshOverview() }
    }

    private fun status(e: kotlinx.serialization.json.JsonElement): String? =
        ((e as? JsonObject)?.get("status"))?.jsonPrimitive?.contentOrNull

    companion object {
        const val PRESENCE_INTERVAL_MS = 45_000L

        /** True when the server has the 0.7 social migration (used to hide social UI honestly). */
        fun isUnsupported(e: LoopaError?) = e == LoopaError.SERVER_OUTDATED

    }
}
