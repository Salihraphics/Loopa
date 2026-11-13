package com.loopa.core.game.modes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor

// ---------------------------------------------------------------- Anlat Bakalım
@Serializable data class TabooCard(val word: String = "", val taboo: List<String> = emptyList())

@Serializable
data class CardResult(@SerialName("user_id") val userId: String? = null, val word: String = "", val result: String = "", val at: String? = null)

@Serializable
data class AnlatView(
    @SerialName("my_team") val myTeam: Int? = null,
    /** narrator | guesser | watcher | spectator */
    val role: String = "spectator",
    val secret: TabooCard? = null,
    val feed: List<CardResult> = emptyList(),
)

@Serializable
data class AnlatPublic(val teams: List<List<String>> = emptyList(), @SerialName("team_scores") val teamScores: List<Int> = listOf(0, 0))

@Serializable
data class AnlatRoundPublic(
    val team: Int = 0,
    @SerialName("passes_left") val passesLeft: Int = 0,
    @SerialName("cards_done") val cardsDone: Int = 0,
    @SerialName("turn_score") val turnScore: Int = 0,
)

@Serializable
data class AnlatResults(val team: Int = 0, @SerialName("turn_score") val turnScore: Int = 0, val cards: List<CardResult> = emptyList())

object AnlatBakalimLogic {
    const val PASSES_PER_TURN = 3
    val TEAM_NAMES = listOf("Mavi Takım", "Turuncu Takım")
    fun teamOf(userId: String?, teams: List<List<String>>): Int? = teams.indexOfFirst { userId in it }.takeIf { it >= 0 }
    fun teamName(team: Int?) = team?.let { TEAM_NAMES.getOrNull(it) } ?: "İzleyici"
    /** Leading team, or null on a draw. */
    fun leader(scores: List<Int>): Int? = when {
        scores.size < 2 || scores[0] == scores[1] -> null
        scores[0] > scores[1] -> 0
        else -> 1
    }
    fun resultLabel(result: String) = when (result) { "correct" -> "Bildi"; "pass" -> "Pas"; "taboo" -> "Yasaklı kelime"; else -> result }
}

// ---------------------------------------------------------------- Bilgi Arenası
@Serializable
data class QuizRoundPublic(val question: String = "", val options: List<String> = emptyList(), val category: String = "", val seconds: Int = 20)

@Serializable
data class QuizView(@SerialName("my_choice") val myChoice: Int? = null, val answered: List<String> = emptyList())

@Serializable
data class QuizAnswer(@SerialName("user_id") val userId: String, val choice: Int? = null, val correct: Boolean = false, val points: Int = 0)

@Serializable
data class QuizResults(val answer: Int = -1, val explanation: String = "", val answers: List<QuizAnswer> = emptyList())

object BilgiArenasiLogic {
    const val BASE = 100
    const val SPEED_BONUS = 50
    /** Points a correct answer would earn now (same formula as the server). */
    fun pointsAt(fractionLeft: Float): Int = BASE + floor(SPEED_BONUS * fractionLeft.coerceIn(0f, 1f)).toInt()
    val LETTERS = listOf("A", "B", "C", "D")
}

// ---------------------------------------------------------------- İki Doğru Bir Yalan
@Serializable data class Statements(val items: List<String> = emptyList(), val lie: Int = -1)

@Serializable
data class TruthsView(val mine: Statements? = null, @SerialName("my_vote") val myVote: Int? = null, val done: List<String> = emptyList())

@Serializable data class TruthsRoundPublic(val statements: List<String> = emptyList())

@Serializable
data class TruthsVote(@SerialName("user_id") val userId: String, val choice: Int = -1, val correct: Boolean? = null)

@Serializable
data class TruthsResults(val lie: Int = -1, val fooled: Int = 0, @SerialName("author_points") val authorPoints: Int = 0, val votes: List<TruthsVote> = emptyList())

object IkiDogruBirYalanLogic {
    const val MIN_LEN = 3
    const val MAX_LEN = 90
    const val FINDER_POINTS = 10
    const val FOOL_POINTS = 5

    /** Local check before sending; the server checks the same rules again. Null = fine. */
    fun validate(items: List<String>, lie: Int?): String? {
        if (items.size != 3) return "Üç ifade yazmalısın."
        items.forEachIndexed { i, s ->
            val t = s.trim()
            if (t.length < MIN_LEN) return "${i + 1}. ifade çok kısa."
            if (t.length > MAX_LEN) return "${i + 1}. ifade en fazla $MAX_LEN karakter olabilir."
        }
        if (lie == null || lie !in 0..2) return "Hangisinin yalan olduğunu seç."
        return null
    }
}

// ---------------------------------------------------------------- En Muhtemel Kim?
@Serializable data class LikelyRoundPublic(val prompt: String = "")

@Serializable data class LikelyView(@SerialName("my_vote") val myVote: String? = null, val voted: List<String> = emptyList())

@Serializable data class LikelyCount(@SerialName("user_id") val userId: String, val votes: Int = 0)

@Serializable data class LikelyVote(@SerialName("user_id") val userId: String, val target: String)

@Serializable
data class LikelyResults(val tops: List<String> = emptyList(), val counts: List<LikelyCount> = emptyList(), val votes: List<LikelyVote> = emptyList())

object EnMuhtemelKimLogic {
    const val MATCH_POINTS = 10
    /** Everyone a player may vote for: the others in the match. */
    fun targets(players: List<String>, me: String?): List<String> = players.filter { it != me }
    fun share(votes: Int, total: Int): Float = if (total <= 0) 0f else votes.toFloat() / total
}

// ---------------------------------------------------------------- Çizim Telefonu
@Serializable data class TelephoneRoundPublic(val step: Int = 1, val steps: Int = 1, val kind: String = "draw")

@Serializable data class TelephoneItem(val kind: String = "start", val step: Int? = null, val payload: JsonObject? = null)

@Serializable
data class TelephoneView(val chain: Int? = null, val task: TelephoneItem? = null, val submitted: Boolean = false, val done: List<String> = emptyList())

@Serializable
data class TelephoneChainItem(
    val kind: String = "start",
    @SerialName("user_id") val userId: String? = null,
    val payload: JsonObject? = null,
    val missing: Boolean = false,
)

@Serializable data class TelephoneChain(val items: List<TelephoneChainItem> = emptyList(), val matched: Boolean = false)

@Serializable data class TelephoneResults(val chains: List<TelephoneChain> = emptyList())

object CizimTelefonuLogic {
    const val STEP_POINTS = 10
    const val CHAIN_BONUS = 20
    fun text(payload: JsonObject?): String? = (payload?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content
    fun strokes(payload: JsonObject?) = DrawingCodec.decodeAll(payload?.get("strokes"))
    /** What the player is asked to do with the item they received. */
    fun instruction(kind: String): String = when (kind) {
        "draw" -> "Bu ifadeyi çiz:"
        else -> "Bu çizim neyi anlatıyor?"
    }
}
