package com.loopa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Authoritative room snapshot returned by the `get_room_state` RPC.
 * Every field is produced server-side; the client never derives scores,
 * timers or inventories on its own.
 */
@Serializable
data class RoomState(
    @SerialName("server_time") val serverTime: String,
    val me: Me,
    val room: Room,
    val players: List<RoomPlayer> = emptyList(),
    val match: Match? = null,
    val round: Round? = null,
) {
    val isHost: Boolean get() = room.hostId == me.userId
    val isSpectator: Boolean get() = me.role == "spectator"
    fun player(userId: String?): RoomPlayer? = players.firstOrNull { it.userId == userId }
    val activePlayers: List<RoomPlayer> get() = players.filter { it.role == "player" }
    val spectators: List<RoomPlayer> get() = players.filter { it.role == "spectator" }
}

@Serializable
data class Me(
    @SerialName("user_id") val userId: String,
    val role: String,
    val status: String,
)

@Serializable
data class Room(
    val id: String,
    val code: String,
    @SerialName("host_id") val hostId: String,
    val status: String,
    @SerialName("is_public") val isPublic: Boolean,
    val settings: RoomSettings,
    @SerialName("current_match_id") val currentMatchId: String? = null,
    @SerialName("last_match_id") val lastMatchId: String? = null,
) {
    val inGame: Boolean get() = status == "in_game"
    val closed: Boolean get() = status == "closed"
}

@Serializable
data class RoomSettings(
    @SerialName("round_seconds") val roundSeconds: Int = 60,
    val rounds: Int = 5,
    @SerialName("max_players") val maxPlayers: Int = 10,
    @SerialName("vote_seconds") val voteSeconds: Int = 20,
    @SerialName("allow_spectators") val allowSpectators: Boolean = true,
    @SerialName("excluded_letters") val excludedLetters: List<String> = emptyList(),
    val categories: List<Category> = emptyList(),
) {
    companion object {
        val DURATIONS = listOf(30, 60, 90, 120)
        val ROUNDS = listOf(3, 5, 10, 15)
        val PLAYER_LIMITS = listOf(4, 6, 10)
        const val MIN_CATEGORIES = 3
        const val MAX_CATEGORIES = 12
        const val MAX_CUSTOM_CATEGORIES = 6
    }
}

@Serializable
data class Category(
    val key: String,
    val name: String,
    val custom: Boolean = false,
)

@Serializable
data class RoomPlayer(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject = JsonObject(emptyMap()),
    val level: Int = 1,
    val role: String = "player",
    @SerialName("is_ready") val isReady: Boolean = false,
    @SerialName("is_host") val isHost: Boolean = false,
    val online: Boolean = true,
)

@Serializable
data class Match(
    val id: String,
    val status: String,
    @SerialName("total_rounds") val totalRounds: Int,
    @SerialName("current_round") val currentRound: Int,
    val settings: RoomSettings,
    val scoreboard: List<ScoreEntry> = emptyList(),
    @SerialName("my_jokers") val myJokers: List<OwnedJoker> = emptyList(),
)

@Serializable
data class ScoreEntry(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject = JsonObject(emptyMap()),
    @SerialName("total_points") val totalPoints: Int,
    @SerialName("round_wins") val roundWins: Int,
    val jokers: Int = 0,
)

@Serializable
data class OwnedJoker(
    val id: String,
    val type: JokerType,
    val source: String = "match_start",
    @SerialName("source_round_id") val sourceRoundId: String? = null,
)

@Serializable
data class Round(
    val id: String,
    val number: Int,
    val letter: String,
    val categories: List<Category>,
    val phase: RoundPhase,
    @SerialName("started_at") val startedAt: String,
    @SerialName("base_deadline") val baseDeadline: String,
    @SerialName("voting_deadline") val votingDeadline: String? = null,
    @SerialName("reveal_started_at") val revealStartedAt: String? = null,
    @SerialName("reveal_deadline") val revealDeadline: String? = null,
    @SerialName("results_deadline") val resultsDeadline: String? = null,
    @SerialName("first_submitter_id") val firstSubmitterId: String? = null,
    val players: List<RoundPlayer> = emptyList(),
    @SerialName("joker_events") val jokerEvents: List<JokerEvent> = emptyList(),
    val answers: List<Answer> = emptyList(),
) {
    fun player(userId: String): RoundPlayer? = players.firstOrNull { it.userId == userId }
    fun answersFor(categoryKey: String): List<Answer> = answers.filter { it.categoryKey == categoryKey }
}

@Serializable
enum class RoundPhase {
    @SerialName("answering") ANSWERING,
    @SerialName("voting") VOTING,
    @SerialName("reveal") REVEAL,
    @SerialName("results") RESULTS,
    @SerialName("finished") FINISHED,
}

@Serializable
data class RoundPlayer(
    @SerialName("user_id") val userId: String,
    @SerialName("deadline_at") val deadlineAt: String,
    val submitted: Boolean = false,
    @SerialName("submitted_at") val submittedAt: String? = null,
    val points: Int? = null,
    @SerialName("early_bonus") val earlyBonus: Int = 0,
    @SerialName("is_round_winner") val isRoundWinner: Boolean = false,
    @SerialName("double_category") val doubleCategory: String? = null,
    @SerialName("joker_used") val jokerUsed: Boolean = false,
)

@Serializable
data class JokerEvent(
    val id: String,
    @SerialName("user_id") val userId: String,
    val type: JokerType,
    @SerialName("target_user_id") val targetUserId: String? = null,
    val seconds: Int? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("category_key") val categoryKey: String? = null,
)

@Serializable
data class Answer(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("category_key") val categoryKey: String,
    val text: String = "",
    val normalized: String = "",
    val validation: String? = null,
    @SerialName("final_status") val finalStatus: String? = null,
    @SerialName("is_duplicate") val isDuplicate: Boolean = false,
    val points: Int = 0,
    val doubled: Boolean = false,
    @SerialName("my_vote") val myVote: Boolean? = null,
    @SerialName("votes_cast") val votesCast: Int = 0,
    @SerialName("votes_needed") val votesNeeded: Int? = null,
    @SerialName("votes_accept") val votesAccept: Int? = null,
    @SerialName("votes_reject") val votesReject: Int? = null,
) {
    val isPendingVote: Boolean get() = validation == "uncertain" && finalStatus == null
    val accepted: Boolean get() = finalStatus == "accepted"
    val empty: Boolean get() = finalStatus == "empty" || validation == "empty"
}

@Serializable
data class PublicRoom(
    val id: String,
    val code: String,
    @SerialName("host_name") val hostName: String,
    @SerialName("host_avatar") val hostAvatar: JsonObject = JsonObject(emptyMap()),
    val players: Int,
    @SerialName("max_players") val maxPlayers: Int,
    @SerialName("round_seconds") val roundSeconds: Int,
    val rounds: Int,
    val categories: Int,
)

@Serializable
data class RoomRef(
    val id: String,
    val code: String,
    val status: String? = null,
)

/** Built-in categories (mirror of `public.categories`; the server stays authoritative for names). */
object BuiltInCategories {
    val defaults = listOf(
        Category("isim", "İsim"), Category("sehir", "Şehir"), Category("hayvan", "Hayvan"), Category("bitki", "Bitki"),
        Category("esya", "Eşya"), Category("ulke", "Ülke"), Category("meslek", "Meslek"), Category("unlu", "Ünlü"),
    )
    val extras = listOf(Category("marka", "Marka"), Category("yemek", "Yemek"), Category("film", "Film"), Category("oyun", "Oyun"))
    val all = defaults + extras
}
