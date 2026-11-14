package com.loopa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Snapshot of a running game of the mode engine (`get_mode_state`). Filtered per player by the
 * server: a guesser's snapshot never contains the hidden word, a quiz snapshot never the answer
 * before the reveal. Mode-specific parts are decoded by each game's logic object.
 */
@Serializable
data class ModeState(
    @SerialName("server_time") val serverTime: String,
    val mode: String,
    @SerialName("match_id") val matchId: String,
    @SerialName("match_status") val matchStatus: String = "in_progress",
    val public: JsonObject = JsonObject(emptyMap()),
    val round: ModeRound? = null,
    val me: JsonObject = JsonObject(emptyMap()),
) {
    /** Number of rounds / turns / steps of this match, as the server counts them. */
    val total: Int? get() = (public["total"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
}

@Serializable
data class ModeRound(
    val id: String,
    val number: Int,
    val phase: String,
    @SerialName("actor_id") val actorId: String? = null,
    val public: JsonObject = JsonObject(emptyMap()),
    val results: JsonObject? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("deadline_at") val deadlineAt: String? = null,
)

/** Lenient decoder for the mode-specific JSON parts. */
val ModeJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

inline fun <reified T> JsonElement?.decodeOr(default: T): T =
    if (this == null || this is kotlinx.serialization.json.JsonNull) default
    else runCatching { ModeJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), this) }.getOrDefault(default)

@Serializable
data class ModeStat(
    @SerialName("game_mode") val gameMode: String,
    @SerialName("matches_played") val matchesPlayed: Int = 0,
    val wins: Int = 0,
    @SerialName("total_points") val totalPoints: Long = 0,
    @SerialName("best_points") val bestPoints: Int = 0,
    @SerialName("last_played_at") val lastPlayedAt: String? = null,
)
