package com.loopa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// 0.8.0 platform: coins, progression, quests, leaderboards, badges, teams, tournaments.
// Every value here is read from the server; the client never computes a reward or a balance.

@Serializable
data class CoinTx(val amount: Int, val reason: String, @SerialName("balance_after") val balanceAfter: Int = 0, @SerialName("created_at") val createdAt: String? = null)

@Serializable
data class Wallet(val coins: Int = 0, @SerialName("lifetime_earned") val lifetimeEarned: Int = 0, val recent: List<CoinTx> = emptyList())

@Serializable
/** A player row with 0.8.0 extras (streak, title, rank / score in leaderboards). */
data class PlayerCard(
    @SerialName("user_id") val userId: String = "",
    val username: String = "Oyuncu",
    val avatar: JsonObject = JsonObject(emptyMap()),
    val level: Int = 1,
    val streak: Int = 0,
    val title: BadgeRef? = null,
    val rank: Int? = null,
    val score: Long? = null,
    @SerialName("name_effect") val nameEffect: String? = null,
)

@Serializable
data class BadgeRef(val id: String, val name: String, val rarity: String? = null)

@Serializable
data class Badge(
    val id: String,
    val kind: String = "badge",
    val name: String,
    val description: String? = null,
    val rarity: String? = null,
    val equipped: Boolean = false,
    val icon: String? = null,
    @SerialName("unlocked_at") val unlockedAt: String? = null,
)

@Serializable
data class TeamInfo(
    val id: String,
    val name: String,
    @SerialName("current_streak") val currentStreak: Int = 0,
    @SerialName("best_streak") val bestStreak: Int = 0,
    @SerialName("join_code") val joinCode: String? = null,
    val members: List<PlayerCard> = emptyList(),
)

@Serializable
data class GameStat(
    @SerialName("game_mode") val gameMode: String,
    @SerialName("matches_played") val matchesPlayed: Int = 0,
    val wins: Int = 0,
    @SerialName("win_rate") val winRate: Int = 0,
    @SerialName("best_points") val bestPoints: Int = 0,
    @SerialName("play_seconds") val playSeconds: Long = 0,
    val extra: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class Progression(
    @SerialName("user_id") val userId: String = "",
    val level: Int = 1,
    val xp: Long = 0,
    val prestige: Int = 0,
    @SerialName("prestige_level_required") val prestigeLevelRequired: Int = 100,
    val reputation: Int = 0,
    val coins: Int? = null,
    val streak: Int = 0,
    @SerialName("best_streak") val bestStreak: Int = 0,
    @SerialName("streak_multiplier") val streakMultiplier: Double = 1.0,
    val team: TeamInfo? = null,
    @SerialName("weekly_xp") val weeklyXp: Long = 0,
    val showcase: JsonObject = JsonObject(emptyMap()),
    val badges: List<Badge> = emptyList(),
    val trophies: Int = 0,
    @SerialName("game_stats") val gameStats: List<GameStat> = emptyList(),
)

@Serializable
data class Quest(
    val id: String,
    val period: String,
    val difficulty: String? = null,
    val title: String,
    val progress: Int = 0,
    val target: Int = 1,
    val coins: Int = 0,
    val xp: Int = 0,
    val completed: Boolean = false,
    val claimed: Boolean = false,
)

@Serializable
data class QuestBoard(
    @SerialName("day_resets_at") val dayResetsAt: String? = null,
    @SerialName("week_resets_at") val weekResetsAt: String? = null,
    val quests: List<Quest> = emptyList(),
)

@Serializable
data class RankRef(val rank: Int, val score: Long)

@Serializable
data class Leaderboard(
    val kind: String = "total",
    @SerialName("resets_at") val resetsAt: String? = null,
    val rows: List<PlayerCard> = emptyList(),
    @SerialName("total_rows") val totalRows: Int = 0,
    val me: RankRef? = null,
)

@Serializable
data class GamePrefs(val likes: List<String> = emptyList(), val dislikes: List<String> = emptyList(), val set: Boolean = false, val tags: List<String> = emptyList())

@Serializable
data class MatchReward(
    @SerialName("game_mode") val gameMode: String = "",
    @SerialName("reward_class") val rewardClass: String = "",
    val won: Boolean = false,
    val valid: Boolean = false,
    val reason: String? = null,
    val coins: Int = 0,
    @SerialName("xp_bonus") val xpBonus: Int = 0,
    val multiplier: Double = 1.0,
)

@Serializable
data class TournamentSummary(
    val id: String,
    val name: String,
    @SerialName("game_mode") val gameMode: String,
    val format: String,
    @SerialName("team_size") val teamSize: Int = 1,
    @SerialName("max_entries") val maxEntries: Int = 8,
    val status: String,
    @SerialName("current_round") val currentRound: Int = 0,
    @SerialName("host_id") val hostId: String = "",
    @SerialName("entries_count") val entriesCount: Int = 0,
    val joined: Boolean = false,
    @SerialName("champion_entry") val championEntry: String? = null,
)

@Serializable
data class TournamentEntry(
    val id: String,
    val name: String? = null,
    val seed: Int? = null,
    val status: String = "active",
    @SerialName("eliminated_round") val eliminatedRound: Int? = null,
    val players: List<PlayerCard> = emptyList(),
    val mine: Boolean = false,
)

@Serializable
data class TournamentMatch(
    val id: String,
    val round: Int,
    val slot: Int = 0,
    val entries: List<String> = emptyList(),
    val status: String,
    @SerialName("winner_entry") val winnerEntry: String? = null,
    @SerialName("eliminated_entry") val eliminatedEntry: String? = null,
    @SerialName("room_code") val roomCode: String? = null,
    val mine: Boolean = false,
)

@Serializable
data class TournamentDetail(
    val id: String,
    val name: String,
    @SerialName("game_mode") val gameMode: String,
    val format: String,
    @SerialName("team_size") val teamSize: Int = 1,
    @SerialName("max_entries") val maxEntries: Int = 8,
    val status: String,
    @SerialName("current_round") val currentRound: Int = 0,
    @SerialName("host_id") val hostId: String = "",
    @SerialName("champion_entry") val championEntry: String? = null,
    val rewarded: Boolean = false,
    val host: PlayerCard? = null,
    val entries: List<TournamentEntry> = emptyList(),
    val matches: List<TournamentMatch> = emptyList(),
)
