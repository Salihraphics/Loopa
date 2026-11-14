package com.loopa.core.network

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private const val POKE = "poke"
/** Data-free "new strokes" hint for Çiz ve Tahmin Et; receivers fetch strokes with get_strokes. */
private const val INK = "ink"

enum class LiveStatus { CONNECTING, LIVE, OFFLINE }

/**
 * Two independent "something changed" paths for one room:
 *  1. Postgres CDC on `public.room_signals` (bumped by every RPC on the server),
 *  2. a data-free Broadcast "poke" that clients send after their own actions.
 * Neither carries game data — they only trigger a re-fetch of the authoritative,
 * RLS-filtered snapshot — so duplicate, missed, out-of-order or spoofed signals
 * cannot corrupt state. The second path covers occasional CDC delivery gaps.
 */
class RoomSignalListener(
    private val backend: LoopaBackend,
    private val roomId: String,
    private val scope: CoroutineScope,
    private val onSignal: () -> Unit,
    private val onInk: () -> Unit = {},
) {
    private val _status = MutableStateFlow(LiveStatus.CONNECTING)
    val status: StateFlow<LiveStatus> = _status.asStateFlow()
    private var channel: RealtimeChannel? = null
    private val jobs = mutableListOf<Job>()

    fun start() {
        val client = backend.client ?: run { _status.value = LiveStatus.OFFLINE; return }
        val ch = client.channel("room-signals-$roomId")
        channel = ch
        jobs += ch.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "room_signals"
            filter("room_id", FilterOperator.EQ, roomId)
        }.onEach { onSignal() }.launchIn(scope)
        jobs += ch.broadcastFlow<JsonObject>(POKE).onEach { onSignal() }.launchIn(scope)
        jobs += ch.broadcastFlow<JsonObject>(INK).onEach { onInk() }.launchIn(scope)
        jobs += ch.status.onEach { s ->
            val before = _status.value
            _status.value = when (s) {
                RealtimeChannel.Status.SUBSCRIBED -> LiveStatus.LIVE
                RealtimeChannel.Status.SUBSCRIBING -> LiveStatus.CONNECTING
                else -> LiveStatus.OFFLINE
            }
            // (Re)subscribed: we may have missed signals in between, so refresh.
            if (_status.value == LiveStatus.LIVE && before != LiveStatus.LIVE) {
                onSignal()
                poke()   // let others know we (re)connected
            }
        }.launchIn(scope)
        jobs += scope.launch {
            runCatching { ch.subscribe(blockUntilSubscribed = false) }
                .onFailure { _status.value = LiveStatus.OFFLINE }
        }
    }

    /** Tell the other clients in this room to refresh. Best effort, fire-and-forget. */
    fun poke() {
        val ch = channel ?: return
        if (ch.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        scope.launch { runCatching { ch.broadcast(POKE, JsonObject(emptyMap())) } }
    }

    /** Tell the others that new strokes were saved. Best effort; they also poll while a drawing is open. */
    fun ink() {
        val ch = channel ?: return
        if (ch.status.value != RealtimeChannel.Status.SUBSCRIBED) return
        scope.launch { runCatching { ch.broadcast(INK, JsonObject(emptyMap())) } }
    }

    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val ch = channel ?: return
        channel = null
        runCatching { backend.client?.realtime?.removeChannel(ch) }
    }
}

/**
 * "Something changed for me" for the social features: CDC on the caller's own row of
 * `public.social_signals` (RLS only lets a user read their own row). Like the room signals it
 * carries no data; the listener re-fetches through the RPCs.
 */
class SocialSignalListener(
    private val backend: LoopaBackend,
    private val userId: String,
    private val scope: CoroutineScope,
    private val onSignal: () -> Unit,
) {
    private val _status = MutableStateFlow(LiveStatus.CONNECTING)
    val status: StateFlow<LiveStatus> = _status.asStateFlow()
    private var channel: RealtimeChannel? = null
    private val jobs = mutableListOf<Job>()

    fun start() {
        val client = backend.client ?: run { _status.value = LiveStatus.OFFLINE; return }
        val ch = client.channel("social-$userId")
        channel = ch
        jobs += ch.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "social_signals"
            filter("user_id", FilterOperator.EQ, userId)
        }.onEach { onSignal() }.launchIn(scope)
        jobs += ch.status.onEach { s ->
            val before = _status.value
            _status.value = when (s) {
                RealtimeChannel.Status.SUBSCRIBED -> LiveStatus.LIVE
                RealtimeChannel.Status.SUBSCRIBING -> LiveStatus.CONNECTING
                else -> LiveStatus.OFFLINE
            }
            if (_status.value == LiveStatus.LIVE && before != LiveStatus.LIVE) onSignal()
        }.launchIn(scope)
        jobs += scope.launch {
            runCatching { ch.subscribe(blockUntilSubscribed = false) }.onFailure { _status.value = LiveStatus.OFFLINE }
        }
    }

    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val ch = channel ?: return
        channel = null
        runCatching { backend.client?.realtime?.removeChannel(ch) }
    }
}
