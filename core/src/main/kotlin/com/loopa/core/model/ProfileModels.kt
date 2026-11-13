package com.loopa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProfileOverview(
    val id: String,
    val username: String,
    val avatar: JsonObject = JsonObject(emptyMap()),
    val xp: Int = 0,
    val level: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    val stats: PlayerStats? = null,
    @SerialName("owned_cosmetics") val ownedCosmetics: List<String> = emptyList(),
    @SerialName("recent_xp") val recentXp: List<XpGain> = emptyList(),
    val history: List<MatchHistoryEntry> = emptyList(),
)

@Serializable
data class PlayerStats(
    @SerialName("matches_played") val matchesPlayed: Int = 0,
    @SerialName("match_wins") val matchWins: Int = 0,
    @SerialName("second_places") val secondPlaces: Int = 0,
    @SerialName("third_places") val thirdPlaces: Int = 0,
    @SerialName("rounds_played") val roundsPlayed: Int = 0,
    @SerialName("round_wins") val roundWins: Int = 0,
    @SerialName("total_points") val totalPoints: Long = 0,
    @SerialName("early_bonuses") val earlyBonuses: Int = 0,
    @SerialName("jokers_used") val jokersUsed: Int = 0,
) {
    /** Win rate in percent, 0 when no matches played. */
    val winRate: Int get() = if (matchesPlayed == 0) 0 else (matchWins * 100 + matchesPlayed / 2) / matchesPlayed
}

@Serializable
data class XpGain(
    val amount: Int,
    val reason: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class MatchHistoryEntry(
    @SerialName("match_id") val matchId: String,
    @SerialName("room_code") val roomCode: String,
    @SerialName("final_rank") val finalRank: Int,
    @SerialName("player_count") val playerCount: Int,
    @SerialName("total_points") val totalPoints: Int,
    @SerialName("round_wins") val roundWins: Int,
    @SerialName("xp_earned") val xpEarned: Int,
    @SerialName("finished_at") val finishedAt: String,
)

@Serializable
data class CosmeticItem(
    val id: String,
    val slot: String,
    @SerialName("name_tr") val name: String,
    @SerialName("required_level") val requiredLevel: Int,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class MatchResults(
    val id: String,
    val status: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("total_rounds") val totalRounds: Int,
    @SerialName("rounds_played") val roundsPlayed: Int = 0,
    val players: List<MatchResultPlayer> = emptyList(),
    @SerialName("round_winners") val roundWinners: List<RoundWinners> = emptyList(),
    @SerialName("game_mode") val gameMode: String = "isim_sehir",
    @SerialName("mode_public") val modePublic: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class MatchResultPlayer(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject = JsonObject(emptyMap()),
    val level: Int = 1,
    val xp: Int = 0,
    @SerialName("total_points") val totalPoints: Int,
    @SerialName("round_wins") val roundWins: Int,
    @SerialName("final_rank") val finalRank: Int? = null,
    @SerialName("xp_earned") val xpEarned: Int = 0,
    @SerialName("valid_answers") val validAnswers: Int = 0,
    @SerialName("unique_answers") val uniqueAnswers: Int = 0,
    /** Correct guesses / answers / votes in the other games. */
    @SerialName("correct_actions") val correctActions: Int = 0,
    @SerialName("early_bonuses") val earlyBonuses: Int = 0,
)

@Serializable
data class RoundWinners(
    val round: Int,
    val letter: String,
    val winners: List<String>? = null,
)
