package com.loopa.feature.game.modes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.RoomSession
import com.loopa.core.game.LoopaException
import com.loopa.core.game.modes.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Shared ViewModel of the 9 mode games. It only forwards player actions to the server
 * (mode_act / mode_draw) and exposes the server's snapshot; it never computes a score.
 */
class ModeGameViewModel(private val c: DataContainer, roomId: String) : ViewModel() {
    val session: RoomSession = c.sessions.open(roomId)
    val clock get() = session.clock
    val mode = session.mode
    val strokes = session.strokes

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** Short positive feedback from the server's answer, e.g. "Doğru! +30". */
    private val _flash = MutableStateFlow<String?>(null)
    val flash: StateFlow<String?> = _flash.asStateFlow()

    fun dismissError() { _error.value = null }
    fun dismissFlash() { _flash.value = null }
    fun showError(message: String) { _error.value = message }

    fun act(kind: String, payload: JsonObject = JsonObject(emptyMap()), onResult: (JsonObject) -> Unit = {}) {
        val roundId = mode.value?.round?.id ?: return
        if (_busy.value != null) return
        _busy.value = kind
        viewModelScope.launch {
            try {
                val r = session.modeAct(roundId, kind, payload)
                _error.value = null
                onResult(r)
            } catch (e: LoopaException) {
                _error.value = e.error.message
            } finally {
                _busy.value = null
            }
        }
    }

    fun flash(text: String) { _flash.value = text }

    fun skip() = viewModelScope.launch { runCatching { session.modeSkip() } }

    fun leave(onLeft: () -> Unit) = viewModelScope.launch {
        session.leave()
        c.sessions.close()
        onLeft()
    }

    // ------------------------------------------------------------ live drawing (Çiz ve Tahmin Et)
    private val _local = MutableStateFlow<Pair<String?, List<Stroke>>>(null to emptyList())
    /** The drawer's own canvas (drawn immediately, sent in batches). */
    val localStrokes: StateFlow<Pair<String?, List<Stroke>>> = _local.asStateFlow()
    private val pending = mutableListOf<Stroke>()
    private var flushing = false

    fun addStroke(roundId: String, stroke: Stroke) {
        val (rid, list) = _local.value
        _local.value = roundId to (if (rid == roundId) list else emptyList()) + stroke
        synchronized(pending) { pending += stroke }
        scheduleFlush(roundId)
    }

    fun clearCanvas(roundId: String) = addStroke(roundId, Stroke(0, 1, clear = true))

    private fun scheduleFlush(roundId: String) {
        if (flushing) return
        flushing = true
        viewModelScope.launch {
            delay(180)          // batch the strokes of a short burst
            val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
            try {
                batch.chunked(50).forEach { session.draw(roundId, it) }
            } catch (e: LoopaException) {
                _error.value = e.error.message
            } finally {
                flushing = false
                if (synchronized(pending) { pending.isNotEmpty() }) scheduleFlush(roundId)
            }
        }
    }
}
