package com.loopa.core.data

import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.game.RoundTiming
import com.loopa.core.game.ServerClock
import com.loopa.core.game.Timestamps
import com.loopa.core.model.JokerEvent
import com.loopa.core.model.JokerType
import com.loopa.core.model.RoomSettings
import com.loopa.core.model.RoomState
import com.loopa.core.network.LiveStatus
import com.loopa.core.network.LoopaBackend
import com.loopa.core.network.LoopaJson
import com.loopa.core.network.RoomSignalListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.random.Random

enum class ConnectionState { LIVE, CONNECTING, OFFLINE }

sealed interface SessionEvent {
    data object Kicked : SessionEvent
    data object RoomClosed : SessionEvent
    data class JokerHit(val event: JokerEvent) : SessionEvent
    data class MatchFinished(val matchId: String) : SessionEvent
    data class Error(val error: LoopaError) : SessionEvent
}

/**
 * Client-side view of one room. The database is authoritative; this class
 *  - re-fetches the full snapshot whenever a realtime signal arrives
 *    (plus a slow safety poll, plus after every action),
 *  - keeps a [ServerClock] in sync from snapshot timestamps,
 *  - asks the server to advance the round (`game_tick`) once a server
 *    deadline has passed — the server re-checks the deadline itself,
 *  - sends heartbeats so connection status / host transfer work.
 */
class RoomSession(
    val roomId: String,
    private val backend: LoopaBackend,
    val clock: ServerClock,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Safety re-fetch intervals (in game, in lobby). Realtime signals are the primary update path. */
    private val pollIntervalsMs: Pair<Long, Long> = 4_000L to 8_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow<RoomState?>(null)
    val state: StateFlow<RoomState?> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()
    private val fetchOk = MutableStateFlow(true)
    private val refreshRequests = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)
    private val signals = RoomSignalListener(backend, roomId, scope) { requestRefresh() }
    private val seenJokerEvents = mutableSetOf<String>()
    private var started = false
    private var lastMatchSeen: String? = null

    val connection: StateFlow<ConnectionState> = combine(signals.status, fetchOk) { live, ok ->
        when {
            !ok -> ConnectionState.OFFLINE
            live == LiveStatus.LIVE -> ConnectionState.LIVE
            live == LiveStatus.CONNECTING -> ConnectionState.CONNECTING
            else -> ConnectionState.CONNECTING   // realtime down, polling still works
        }
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.CONNECTING)

    val myUserId: String? get() = backend.currentUserId

    fun start() {
        if (started) return
        started = true
        signals.start()
        scope.launch { refreshLoop() }
        scope.launch { heartbeatLoop() }
        scope.launch { safetyPollLoop() }
        scope.launch { tickLoop() }
        requestRefresh()
    }

    fun stop() {
        scope.launch { signals.stop() }.invokeOnCompletion { scope.cancel() }
    }

    fun requestRefresh() { refreshRequests.trySend(Unit) }

    private suspend fun refreshLoop() {
        for (req in refreshRequests) {
            delay(80)    // coalesce bursts of signals
            fetchSnapshot()
        }
    }

    private suspend fun fetchSnapshot() {
        val sent = System.currentTimeMillis()
        try {
            val s = backend.rpc("get_room_state", RoomState.serializer()) { put("p_room_id", roomId) }
            val received = System.currentTimeMillis()
            Timestamps.parse(s.serverTime)?.let { clock.addSample(sent, received, it) }
            fetchOk.value = true
            apply(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoopaException) {
            if (e.error == LoopaError.NETWORK) fetchOk.value = false
            if (e.error == LoopaError.NOT_IN_ROOM) _events.tryEmit(SessionEvent.Kicked)
        } catch (e: Exception) {
            fetchOk.value = false
        }
    }

    private fun apply(s: RoomState) {
        val prev = _state.value
        _state.value = s
        if (s.me.status == "kicked" && prev?.me?.status != "kicked") _events.tryEmit(SessionEvent.Kicked)
        if (s.room.closed && prev?.room?.closed != true) _events.tryEmit(SessionEvent.RoomClosed)
        // match finished -> results
        val finished = s.room.lastMatchId
        if (prev != null && finished != null && finished != lastMatchSeen && prev.room.currentMatchId == finished && !s.room.inGame) {
            _events.tryEmit(SessionEvent.MatchFinished(finished))
        }
        lastMatchSeen = finished
        // jokers that targeted me
        val me = s.me.userId
        s.round?.jokerEvents?.forEach { ev ->
            if (seenJokerEvents.add(ev.id) && prev != null && ev.targetUserId == me) _events.tryEmit(SessionEvent.JokerHit(ev))
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            try {
                backend.rpc("room_heartbeat") { put("p_room_id", roomId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoopaException) {
                if (e.error == LoopaError.NOT_IN_ROOM) requestRefresh()
            }
            delay(10_000)
        }
    }

    private suspend fun safetyPollLoop() {
        while (true) {
            val inGame = _state.value?.room?.inGame == true
            delay(if (inGame) pollIntervalsMs.first else pollIntervalsMs.second)
            requestRefresh()
        }
    }

    /** Schedules `game_tick` for the next server deadline (with jitter so clients don't stampede). */
    private suspend fun tickLoop() {
        _state.map { s ->
            val r = s?.round
            if (s?.room?.inGame == true && s.me.status == "active" && r != null) r.id + r.phase + (RoundTiming.nextTransitionAt(r) ?: 0) else null
        }.distinctUntilChanged().collectLatest { key ->
            if (key == null) return@collectLatest
            val round = _state.value?.round ?: return@collectLatest
            val at = RoundTiming.nextTransitionAt(round) ?: return@collectLatest
            val wait = at - clock.now() + Random.nextLong(150, 700)
            if (wait > 0) delay(wait)
            var attempts = 0
            while (attempts < 8) {
                attempts++
                val advanced = runCatching {
                    (backend.rpc("game_tick") { put("p_room_id", roomId) } as? JsonPrimitive)?.content == "true"
                }.getOrDefault(false)
                requestRefresh()
                if (advanced) break
                delay(1_500L)
            }
        }
    }

    // ------------------------------------------------------------------ actions
    private suspend fun act(block: suspend () -> Unit) {
        try {
            block()
        } finally {
            requestRefresh()
        }
    }

    suspend fun setReady(ready: Boolean) = act { backend.rpc("set_ready") { put("p_room_id", roomId); put("p_ready", ready) } }
    suspend fun setSpectator(spectator: Boolean) = act { backend.rpc("set_role") { put("p_room_id", roomId); put("p_spectator", spectator) } }
    suspend fun kick(userId: String) = act { backend.rpc("kick_player") { put("p_room_id", roomId); put("p_user_id", userId) } }
    suspend fun transferHost(userId: String) = act { backend.rpc("transfer_host") { put("p_room_id", roomId); put("p_user_id", userId) } }
    suspend fun startMatch() = act { backend.rpc("start_match") { put("p_room_id", roomId) } }
    suspend fun closeRoom() = act { backend.rpc("close_room") { put("p_room_id", roomId) } }
    suspend fun skipReveal() = act { backend.rpc("skip_reveal") { put("p_room_id", roomId) } }

    suspend fun updateSettings(settings: RoomSettings, isPublic: Boolean) = act {
        backend.rpc("update_room_settings") {
            put("p_room_id", roomId)
            put("p_settings", LoopaJson.encodeToJsonElement(settings))
            put("p_is_public", isPublic)
        }
    }

    suspend fun leave() {
        withTimeoutOrNull(5_000) { runCatching { backend.rpc("leave_room") { put("p_room_id", roomId) } } }
    }

    /** Draft autosave — does not lock answers. */
    suspend fun saveDraft(roundId: String, answers: Map<String, String>) {
        backend.rpc("save_answers") {
            put("p_round_id", roundId)
            put("p_answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
        }
    }

    /** Locks answers. Returns the early-completion bonus awarded by the server (0 or 5). */
    suspend fun submit(roundId: String, answers: Map<String, String>): Int {
        var bonus = 0
        act {
            val res = backend.rpc("submit_answers") {
                put("p_round_id", roundId)
                put("p_answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
            }
            bonus = ((res as? JsonObject)?.get("early_bonus") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        }
        return bonus
    }

    suspend fun useJoker(roundId: String, jokerId: String, type: JokerType, target: String?, category: String?) = act {
        backend.rpc("use_joker") {
            put("p_round_id", roundId)
            put("p_joker_id", jokerId)
            if (type.needsTarget && target != null) put("p_target", target)
            if (type.needsCategory && category != null) put("p_category", category)
        }
    }

    suspend fun vote(answerId: String, accept: Boolean) = act {
        backend.rpc("cast_vote") { put("p_answer_id", answerId); put("p_accept", accept) }
    }
}

/** Keeps exactly one live [RoomSession] for the room the player is in. */
class RoomSessionManager(private val backend: LoopaBackend, private val clock: ServerClock) {
    private val _current = MutableStateFlow<RoomSession?>(null)
    val current: StateFlow<RoomSession?> = _current.asStateFlow()

    fun open(roomId: String): RoomSession {
        _current.value?.let { if (it.roomId == roomId) return it else it.stop() }
        return RoomSession(roomId, backend, clock).also {
            _current.value = it
            it.start()
        }
    }

    fun close() {
        _current.value?.stop()
        _current.value = null
    }
}
