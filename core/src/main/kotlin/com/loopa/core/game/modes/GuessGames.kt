package com.loopa.core.game.modes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

// Shared data of the three describe-and-guess games (Gizli Kelime, Sessiz Sinema, Çiz ve Tahmin Et).
// Each game has its own logic object below with its own scoring and rules.

@Serializable
data class GuessAnswer(val word: String = "", val aliases: List<String> = emptyList())

@Serializable
data class GuessFeedItem(
    @SerialName("user_id") val userId: String,
    val correct: Boolean = false,
    /** Null for someone else's correct guess while the round is running (the server hides it). */
    val text: String? = null,
    val points: Int = 0,
    val at: String? = null,
)

@Serializable
data class GuessView(
    /** Only for the describer / drawer / actor, or after the round. */
    val secret: GuessAnswer? = null,
    val solved: Boolean = false,
    val feed: List<GuessFeedItem> = emptyList(),
    @SerialName("stroke_seq") val strokeSeq: Long = 0,
)

@Serializable
data class GuessRoundPublic(val category: String = "", val words: Int = 1, val hint: String? = null)

@Serializable
data class SolvedEntry(@SerialName("user_id") val userId: String, val points: Int = 0)

@Serializable
data class GuessResults(
    val answer: String = "",
    @SerialName("actor_left") val actorLeft: Boolean = false,
    val solved: List<SolvedEntry> = emptyList(),
    @SerialName("actor_points") val actorPoints: Int = 0,
)

/** Gizli Kelime: one player describes a word aloud, everybody else types guesses. */
object GizliKelimeLogic {
    const val DESCRIBER_BONUS = 10
    /** Points of the next correct guesser when [alreadySolved] players have guessed (30, 25, 20, 15, 10 …). */
    fun pointsForNext(alreadySolved: Int): Int = max(10, 30 - 5 * alreadySolved)
    fun everyoneSolved(guessers: Int, solved: Int) = guessers > 0 && solved >= guessers
}

/** Sessiz Sinema: the actor mimes, the first correct guess ends the round. */
object SessizSinemaLogic {
    const val ACTOR_BONUS = 20
    fun pointsAt(fractionLeft: Float): Int = 20 + (20 * fractionLeft.coerceIn(0f, 1f)).roundToInt()
    fun wordCountLabel(words: Int) = if (words <= 1) "Tek kelime" else "$words kelime"
}

/** Çiz ve Tahmin Et: the drawer draws live, the faster a correct guess the more points. */
object CizTahminLogic {
    const val DRAWER_BONUS = 8
    fun pointsAt(fractionLeft: Float): Int = 10 + (40 * fractionLeft.coerceIn(0f, 1f)).roundToInt()
    /** "_ _ _ _   _ _ _" — letters hidden, spaces kept wider. */
    fun hintLabel(hint: String?): String =
        hint.orEmpty().split(' ').filter { it.isNotEmpty() }.joinToString("   ") { w -> w.toList().joinToString(" ") }
}
