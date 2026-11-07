package com.loopa.feature.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.RoomSession
import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.game.RoundTiming
import com.loopa.core.model.JokerType
import com.loopa.core.model.OwnedJoker
import com.loopa.core.model.RoomState
import com.loopa.core.model.RoundPhase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface GameToast {
    data class JokerUsed(val type: JokerType, val seconds: Int?) : GameToast
    data class EarlyBonus(val points: Int) : GameToast
    data class Error(val message: String) : GameToast
}

@OptIn(FlowPreview::class)
class GameViewModel(private val c: DataContainer, roomId: String) : ViewModel() {
    val session: RoomSession = c.sessions.open(roomId)
    val clock get() = session.clock

    /** Local draft answers per round id (the server copy is the authority once locked). */
    private val _drafts = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val drafts: StateFlow<Map<String, Map<String, String>>> = _drafts.asStateFlow()
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()
    private val _busyJoker = MutableStateFlow(false)
    val busyJoker: StateFlow<Boolean> = _busyJoker.asStateFlow()
    private val _votingIds = MutableStateFlow<Set<String>>(emptySet())
    val votingIds: StateFlow<Set<String>> = _votingIds.asStateFlow()
    private val _toasts = MutableSharedFlow<GameToast>(extraBufferCapacity = 8)
    val toasts: SharedFlow<GameToast> = _toasts.asSharedFlow()
    private val dirty = mutableSetOf<String>()
    private var autosave: Job? = null
    private var finalSaveDoneFor: String? = null

    /** Seeds drafts from the server snapshot (e.g. after an app restart / reconnect). */
    fun syncFromServer(state: RoomState) {
        val round = state.round ?: return
        if (round.phase != RoundPhase.ANSWERING) return
        val me = state.me.userId
        val server = round.answers.filter { it.userId == me }.associate { it.categoryKey to it.text }
        if (server.isEmpty()) return
        _drafts.update { d ->
            val local = d[round.id]
            if (local == null) d + (round.id to server) else d
        }
    }

    fun setAnswer(roundId: String, category: String, value: String) {
        _drafts.update { d -> d + (roundId to ((d[roundId] ?: emptyMap()) + (category to value.take(40)))) }
        dirty += roundId
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(1_200)
            flushDraft(roundId)
        }
    }

    private suspend fun flushDraft(roundId: String) {
        if (roundId !in dirty) return
        val answers = _drafts.value[roundId] ?: return
        runCatching { session.saveDraft(roundId, answers) }.onSuccess { dirty -= roundId }
    }

    /** Called by the UI right before the player's own deadline so typed answers are kept. */
    fun finalSave(roundId: String) {
        if (finalSaveDoneFor == roundId) return
        finalSaveDoneFor = roundId
        autosave?.cancel()
        viewModelScope.launch { dirty += roundId; flushDraft(roundId) }
    }

    fun submit(state: RoomState) {
        val round = state.round ?: return
        if (_submitting.value) return
        _submitting.value = true
        autosave?.cancel()
        viewModelScope.launch {
            try {
                val answers = round.categories.associate { it.key to (_drafts.value[round.id]?.get(it.key) ?: "") }
                val bonus = session.submit(round.id, answers)
                dirty -= round.id
                if (bonus > 0) _toasts.tryEmit(GameToast.EarlyBonus(bonus))
            } catch (e: LoopaException) {
                if (e.error != LoopaError.ANSWERS_LOCKED) _toasts.tryEmit(GameToast.Error(e.error.message))
            } finally {
                _submitting.value = false
            }
        }
    }

    fun useJoker(state: RoomState, joker: OwnedJoker, target: String?, category: String?) {
        val round = state.round ?: return
        if (_busyJoker.value) return
        _busyJoker.value = true
        viewModelScope.launch {
            try {
                session.useJoker(round.id, joker.id, joker.type, target, category)
                val seconds = when (joker.type) {
                    JokerType.EXTRA_TIME -> 10
                    JokerType.TIME_THIEF -> 5
                    JokerType.SLOWDOWN -> 10
                    JokerType.DOUBLE_POINTS -> null
                }
                _toasts.tryEmit(GameToast.JokerUsed(joker.type, seconds))
            } catch (e: LoopaException) {
                _toasts.tryEmit(GameToast.Error(e.error.message))
            } finally {
                _busyJoker.value = false
            }
        }
    }

    fun vote(answerId: String, accept: Boolean) {
        if (answerId in _votingIds.value) return
        _votingIds.update { it + answerId }
        viewModelScope.launch {
            try {
                session.vote(answerId, accept)
            } catch (e: LoopaException) {
                if (e.error != LoopaError.ALREADY_VOTED) _toasts.tryEmit(GameToast.Error(e.error.message))
            } finally {
                _votingIds.update { it - answerId }
            }
        }
    }

    fun skip() {
        viewModelScope.launch {
            try { session.skipReveal() } catch (e: LoopaException) { _toasts.tryEmit(GameToast.Error(e.error.message)) }
        }
    }

    fun remainingMs(state: RoomState, nowMs: Long): Long {
        val round = state.round ?: return 0
        return RoundTiming.remainingMs(round, state.me.userId, nowMs)
    }
}
