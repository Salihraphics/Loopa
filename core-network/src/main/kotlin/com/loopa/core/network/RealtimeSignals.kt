package com.loopa.core.network

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
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

enum class LiveStatus { CONNECTING, LIVE, OFFLINE }

/**
 * Listens to `public.room_signals` for one room. A signal carries no game
 * data — it only tells the client to re-fetch the authoritative snapshot —
 * so duplicate, missed or out-of-order events cannot corrupt state.
 */
class RoomSignalListener(
    private val backend: LoopaBackend,
    private val roomId: String,
    private val scope: CoroutineScope,
    private val onSignal: () -> Unit,
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
        jobs += ch.status.onEach { s ->
            val before = _status.value
            _status.value = when (s) {
                RealtimeChannel.Status.SUBSCRIBED -> LiveStatus.LIVE
                RealtimeChannel.Status.SUBSCRIBING -> LiveStatus.CONNECTING
                else -> LiveStatus.OFFLINE
            }
            // (Re)subscribed: we may have missed signals in between, so refresh.
            if (_status.value == LiveStatus.LIVE && before != LiveStatus.LIVE) onSignal()
        }.launchIn(scope)
        jobs += scope.launch {
            runCatching { ch.subscribe(blockUntilSubscribed = false) }
                .onFailure { _status.value = LiveStatus.OFFLINE }
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
