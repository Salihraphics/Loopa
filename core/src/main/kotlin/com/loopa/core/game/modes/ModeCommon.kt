package com.loopa.core.game.modes

import com.loopa.core.game.Timestamps
import com.loopa.core.model.ModeRound
import com.loopa.core.model.ModeState
import com.loopa.core.model.decodeOr

/** Clock helpers shared by every game screen. All times are server times (see ServerClock). */
object ModeTiming {
    /** Milliseconds until the round opens (countdown), 0 once it is open. */
    fun untilStart(round: ModeRound, nowMs: Long): Long =
        ((Timestamps.parse(round.startedAt) ?: nowMs) - nowMs).coerceAtLeast(0)

    fun remainingMs(round: ModeRound, nowMs: Long): Long =
        ((Timestamps.parse(round.deadlineAt) ?: nowMs) - nowMs).coerceAtLeast(0)

    /** Share of the phase time left, 0..1 (1 before the round opens). */
    fun fractionLeft(round: ModeRound, nowMs: Long): Float {
        val start = Timestamps.parse(round.startedAt) ?: return 0f
        val end = Timestamps.parse(round.deadlineAt) ?: return 0f
        if (end <= start) return 0f
        return ((end - nowMs).toFloat() / (end - start)).coerceIn(0f, 1f)
    }

    /** When the client should ask the server to advance (the server re-checks the deadline itself). */
    fun nextTransitionAt(state: ModeState?): Long? {
        val r = state?.round ?: return null
        if (state.matchStatus != "in_progress") return null
        return if (r.phase == "done") Timestamps.parse(state.serverTime) else Timestamps.parse(r.deadlineAt)
    }

    fun seconds(ms: Long): Int = ((ms + 999) / 1000).toInt()
}

/** Phases a screen can be in, common to all games. */
enum class ModePhaseKind { COUNTDOWN, ACTIVE, REVEAL, WAITING }

object ModePhases {
    private val active = setOf("play", "answer", "vote", "write", "turn", "work")
    private val reveal = setOf("reveal", "showcase")

    fun kind(state: ModeState, nowMs: Long): ModePhaseKind {
        val r = state.round ?: return ModePhaseKind.WAITING
        return when {
            r.phase in active && ModeTiming.untilStart(r, nowMs) > 0 -> ModePhaseKind.COUNTDOWN
            r.phase in active -> ModePhaseKind.ACTIVE
            r.phase in reveal -> ModePhaseKind.REVEAL
            else -> ModePhaseKind.WAITING
        }
    }
}

/** "Tur 2 / 5" style progress label from the server's counters. */
fun ModeState.progressLabel(unit: String = "Tur"): String {
    val n = round?.number ?: 0
    return total?.let { "$unit $n / $it" } ?: "$unit $n"
}

inline fun <reified T> ModeState.meAs(default: T): T = me.decodeOr(default)
inline fun <reified T> ModeState.publicAs(default: T): T = public.decodeOr(default)
inline fun <reified T> ModeRound.publicAs(default: T): T = public.decodeOr(default)
inline fun <reified T> ModeRound.resultsAs(default: T): T = results.decodeOr(default)
