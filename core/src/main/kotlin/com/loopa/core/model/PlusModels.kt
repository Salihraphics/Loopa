package com.loopa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Membership, promo codes and the game catalogue. Every value comes from the server
 * (`get_my_plan`, `redeem_promo_code`, `get_game_catalog`); the client never decides a
 * plan, an access right, a promo duration or a rotation week on its own.
 */
enum class PlanTier(val serverName: String, val label: String) {
    FREE("free", "Ücretsiz"),
    PLUS("plus", "LOOPA Plus"),
    ADMIN("admin", "Admin");

    companion object {
        /** Unknown values are treated as the lowest plan: the server is the authority anyway. */
        fun fromServer(value: String?): PlanTier = entries.firstOrNull { it.serverName == value } ?: FREE
    }
}

@Serializable
data class Membership(
    val plan: String = "free",
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("plus_until") val plusUntil: String? = null,
    @SerialName("plus_unlimited") val plusUnlimited: Boolean = false,
    @SerialName("google_linked") val googleLinked: Boolean = false,
    @SerialName("server_time") val serverTime: String? = null,
    val entitlements: List<EntitlementWindow> = emptyList(),
) {
    val tier: PlanTier get() = PlanTier.fromServer(plan)
    val hasPlusAccess: Boolean get() = tier != PlanTier.FREE
}

@Serializable
data class EntitlementWindow(
    val id: String,
    val source: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String? = null,
    val revoked: Boolean = false,
)

/** Answer of `redeem_promo_code`. status: "redeemed" | "already_redeemed". */
@Serializable
data class PromoRedemption(
    val status: String,
    val code: String,
    val days: Int? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("plus_until") val plusUntil: String? = null,
    @SerialName("redeemed_at") val redeemedAt: String? = null,
) {
    val isNew: Boolean get() = status == "redeemed"
}

@Serializable
data class GameCatalog(
    @SerialName("server_time") val serverTime: String? = null,
    val plan: String = "free",
    val rotation: Rotation = Rotation(),
    val modes: List<CatalogMode> = emptyList(),
) {
    fun mode(id: String): CatalogMode? = modes.firstOrNull { it.id == id }
}

@Serializable
data class Rotation(
    val active: Boolean = false,
    @SerialName("starts_on") val startsOn: String? = null,
    @SerialName("week_slot") val weekSlot: Int? = null,
    @SerialName("week_starts_at") val weekStartsAt: String? = null,
    @SerialName("week_ends_at") val weekEndsAt: String? = null,
    @SerialName("free_modes") val freeModes: List<String> = emptyList(),
    @SerialName("next_week_slot") val nextWeekSlot: Int? = null,
    @SerialName("next_free_modes") val nextFreeModes: List<String> = emptyList(),
)

@Serializable
data class CatalogMode(
    val id: String,
    val name: String = "",
    val description: String = "",
    val status: String = "coming_soon",
    @SerialName("always_free") val alwaysFree: Boolean = false,
    @SerialName("min_players") val minPlayers: Int = 2,
    @SerialName("max_players") val maxPlayers: Int = 10,
    val access: ModeAccess = ModeAccess(),
)

@Serializable
data class ModeAccess(
    val allowed: Boolean = false,
    val reason: String? = null,
    @SerialName("unlocked_by") val unlockedBy: String? = null,
    val plan: String? = null,
    @SerialName("week_slot") val weekSlot: Int? = null,
    @SerialName("in_rotation") val inRotation: Boolean = false,
)
