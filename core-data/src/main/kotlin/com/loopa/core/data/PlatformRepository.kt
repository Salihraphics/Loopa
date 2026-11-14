package com.loopa.core.data

import com.loopa.core.model.GamePrefs
import com.loopa.core.model.Leaderboard
import com.loopa.core.model.MatchReward
import com.loopa.core.model.Progression
import com.loopa.core.model.QuestBoard
import com.loopa.core.model.TournamentDetail
import com.loopa.core.model.TournamentSummary
import com.loopa.core.model.Wallet
import com.loopa.core.network.LoopaBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * 0.8.0 platform calls. Coins, XP, quests, badges, streaks and tournament results are decided by
 * the server (SECURITY DEFINER functions); this repository only reads them and sends requests.
 */
class PlatformRepository(private val backend: LoopaBackend) {
    private val _progression = MutableStateFlow<Progression?>(null)
    /** The signed-in player's progression (level, prestige, reputation, coins, streak, team, badges). */
    val progression: StateFlow<Progression?> = _progression.asStateFlow()

    suspend fun refreshProgression(): Progression = backend.rpc("get_progression", Progression.serializer()).also { _progression.value = it }
    suspend fun progressionOf(userId: String): Progression = backend.rpc("get_progression", Progression.serializer()) { put("p_user", userId) }
    suspend fun wallet(): Wallet = backend.rpc("get_wallet", Wallet.serializer())

    suspend fun quests(): QuestBoard = backend.rpc("get_my_quests", QuestBoard.serializer())
    /** Returns the server's answer ("claimed" / "already_claimed") and refreshes the progression. */
    suspend fun claimQuest(id: String): JsonObject {
        val r = backend.rpc("claim_quest") { put("p_quest_id", id) } as? JsonObject ?: JsonObject(emptyMap())
        runCatching { refreshProgression() }
        return r
    }

    suspend fun leaderboard(kind: String, limit: Int = 50, offset: Int = 0): Leaderboard =
        backend.rpc("get_leaderboard", Leaderboard.serializer()) { put("p_kind", kind); put("p_limit", limit); put("p_offset", offset) }

    suspend fun setShowcase(titleId: String?, badgeIds: List<String>) {
        backend.rpc("set_showcase") {
            put("p_title_id", titleId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("p_badge_ids", JsonArray(badgeIds.map { JsonPrimitive(it) }))
        }
        runCatching { refreshProgression() }
    }

    /** Buys a cosmetic for LOOPA Coins; the server checks the price and the balance (never negative). */
    suspend fun purchaseCosmetic(itemId: String): JsonObject =
        (backend.rpc("purchase_cosmetic") { put("p_item_id", itemId) } as? JsonObject ?: JsonObject(emptyMap())).also { runCatching { refreshProgression() } }

    suspend fun createTeam(name: String): JsonObject = (backend.rpc("create_team") { put("p_name", name.trim()) } as? JsonObject ?: JsonObject(emptyMap())).also { runCatching { refreshProgression() } }
    suspend fun joinTeam(code: String): JsonObject = (backend.rpc("join_team") { put("p_code", code.trim().uppercase()) } as? JsonObject ?: JsonObject(emptyMap())).also { runCatching { refreshProgression() } }
    suspend fun leaveTeam() { backend.rpc("leave_team"); runCatching { refreshProgression() } }

    suspend fun gamePrefs(): GamePrefs = backend.rpc("get_game_prefs", GamePrefs.serializer())
    suspend fun setGamePrefs(likes: List<String>, dislikes: List<String>) {
        backend.rpc("set_game_prefs") {
            put("p_likes", JsonArray(likes.map { JsonPrimitive(it) }))
            put("p_dislikes", JsonArray(dislikes.map { JsonPrimitive(it) }))
        }
    }

    /** Coins / streak bonus of one finished match for the signed-in player (null before it is processed). */
    suspend fun matchRewards(matchId: String): MatchReward? {
        val r: JsonElement = backend.rpc("get_match_rewards") { put("p_match_id", matchId) }
        if (r !is JsonObject) return null
        return com.loopa.core.network.LoopaJson.decodeFromJsonElement(MatchReward.serializer(), r)
    }

    // ------------------------------------------------------------ tournaments
    suspend fun tournaments(status: String? = null): List<TournamentSummary> =
        backend.rpc("list_tournaments", ListSerializer(TournamentSummary.serializer())) { put("p_status", status?.let { JsonPrimitive(it) } ?: JsonNull) }
    suspend fun tournament(id: String): TournamentDetail = backend.rpc("get_tournament", TournamentDetail.serializer()) { put("p_t", id) }
    suspend fun createTournament(name: String, mode: String, format: String, maxEntries: Int, options: JsonObject): JsonElement =
        backend.rpc("create_tournament") {
            put("p_name", name.trim()); put("p_mode", mode); put("p_format", format); put("p_max_entries", maxEntries); put("p_mode_options", options)
        }
    suspend fun joinTournament(id: String) { backend.rpc("join_tournament") { put("p_t", id) } }
    suspend fun leaveTournament(id: String) { backend.rpc("leave_tournament") { put("p_t", id) } }
    suspend fun startTournament(id: String) { backend.rpc("start_tournament") { put("p_t", id) } }
    suspend fun cancelTournament(id: String) { backend.rpc("cancel_tournament") { put("p_t", id) } }
    /** Opens (joins) the room of one's own tournament match; returns the room reference from join_room. */
    suspend fun joinTournamentMatch(matchId: String): JsonObject =
        backend.rpc("join_tournament_match") { put("p_tm", matchId) } as? JsonObject ?: JsonObject(emptyMap())

    fun clear() { _progression.value = null }
}
