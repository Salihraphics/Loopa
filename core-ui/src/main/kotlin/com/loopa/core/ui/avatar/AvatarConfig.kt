package com.loopa.core.ui.avatar

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One avatar slot (a render layer). Ids must match `public.cosmetic_items.slot`. */
enum class AvatarSlot(val key: String, val title: String, val optional: Boolean) {
    BACKGROUND("background", "Arka Plan", false),
    BASE("base", "Karakter", false),
    SKIN("skin", "Ten Rengi", false),
    HAIR("hair", "Saç", true),
    HAIR_COLOR("hair_color", "Saç Rengi", false),
    EYES("eyes", "Gözler", false),
    MOUTH("mouth", "İfade", false),
    GLASSES("glasses", "Gözlük", true),
    HAT("hat", "Şapka", true),
    OUTFIT("outfit", "Kıyafet", false),
    ACCESSORY("accessory", "Aksesuar", true),
    FRAME("frame", "Çerçeve", true),
    DECORATION("decoration", "Süsleme", true);

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** Immutable avatar description: slot -> cosmetic item id. Missing optional slots render as "none". */
@Immutable
data class AvatarConfig(val parts: Map<AvatarSlot, String>) {
    operator fun get(slot: AvatarSlot): String? = parts[slot] ?: if (slot.optional) null else AvatarCatalog.defaults[slot]
    fun with(slot: AvatarSlot, id: String?): AvatarConfig =
        AvatarConfig(if (id == null) parts - slot else parts + (slot to id))

    fun toJson(): JsonObject = JsonObject(parts.mapKeys { it.key.key }.mapValues { JsonPrimitive(it.value) })

    companion object {
        val DEFAULT = AvatarConfig(AvatarCatalog.defaults)

        fun fromJson(json: JsonObject?): AvatarConfig {
            if (json == null || json.isEmpty()) return DEFAULT
            val parts = json.mapNotNull { (k, v) ->
                val slot = AvatarSlot.fromKey(k) ?: return@mapNotNull null
                val id = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: return@mapNotNull null
                slot to id
            }.toMap()
            // Required slots fall back to defaults; optional slots stay empty.
            return AvatarConfig(AvatarCatalog.defaults.filterKeys { !it.optional } + parts)
        }
    }
}

object AvatarCatalog {
    val defaults: Map<AvatarSlot, String> = mapOf(
        AvatarSlot.BACKGROUND to "bg_lavender",
        AvatarSlot.BASE to "base_round",
        AvatarSlot.SKIN to "skin_2",
        AvatarSlot.HAIR to "hair_short",
        AvatarSlot.HAIR_COLOR to "hc_brown",
        AvatarSlot.EYES to "eyes_round",
        AvatarSlot.MOUTH to "mouth_smile",
        AvatarSlot.OUTFIT to "outfit_tshirt",
    )

    val skinColors = mapOf(
        "skin_1" to Color(0xFFFFE0C7), "skin_2" to Color(0xFFF5C9A0), "skin_3" to Color(0xFFE0A878),
        "skin_4" to Color(0xFFB97A52), "skin_5" to Color(0xFF8A5634), "skin_6" to Color(0xFFFFC9B5),
        "skin_mint" to Color(0xFFA8E6CF), "skin_lavender" to Color(0xFFCDB8F0),
    )

    val hairColors = mapOf(
        "hc_black" to Color(0xFF2E2A35), "hc_brown" to Color(0xFF6B4430), "hc_blonde" to Color(0xFFF2CF73),
        "hc_ginger" to Color(0xFFD9733F), "hc_gray" to Color(0xFFB9B6C0), "hc_pink" to Color(0xFFF7A1C4),
        "hc_mint" to Color(0xFF7FD1B5), "hc_lavender" to Color(0xFFA58BE0),
    )

    val backgroundColors = mapOf(
        "bg_lavender" to Color(0xFFB9A5E8), "bg_mint" to Color(0xFF9DDCC5), "bg_peach" to Color(0xFFFFB9A5),
        "bg_butter" to Color(0xFFFFE39A), "bg_sky" to Color(0xFFA9D5F2), "bg_dots" to Color(0xFFFFE39A),
        "bg_stripes" to Color(0xFFA9D5F2), "bg_stars" to Color(0xFF5B4A8C), "bg_sunburst" to Color(0xFFFFB9A5),
    )

    /** Ids that are free (level 1) — used for random starter avatars before the server catalog is loaded. */
    val starterOptions: Map<AvatarSlot, List<String>> = mapOf(
        AvatarSlot.BACKGROUND to listOf("bg_lavender", "bg_mint", "bg_peach", "bg_butter", "bg_sky"),
        AvatarSlot.BASE to listOf("base_round", "base_oval", "base_square"),
        AvatarSlot.SKIN to listOf("skin_1", "skin_2", "skin_3", "skin_4", "skin_5", "skin_6"),
        AvatarSlot.HAIR to listOf("hair_short", "hair_curly", "hair_long", "hair_bob"),
        AvatarSlot.HAIR_COLOR to listOf("hc_black", "hc_brown", "hc_blonde", "hc_ginger"),
        AvatarSlot.EYES to listOf("eyes_dot", "eyes_round", "eyes_happy"),
        AvatarSlot.MOUTH to listOf("mouth_smile", "mouth_grin", "mouth_open", "mouth_flat"),
        AvatarSlot.OUTFIT to listOf("outfit_tshirt", "outfit_hoodie"),
        AvatarSlot.ACCESSORY to listOf("acc_blush", "acc_freckles"),
        AvatarSlot.GLASSES to listOf("glasses_round"),
        AvatarSlot.HAT to listOf("hat_cap"),
    )

    fun random(rnd: kotlin.random.Random = kotlin.random.Random.Default): AvatarConfig {
        val parts = mutableMapOf<AvatarSlot, String>()
        starterOptions.forEach { (slot, options) ->
            if (slot.optional && slot != AvatarSlot.HAIR && rnd.nextFloat() < 0.6f) return@forEach
            parts[slot] = options.random(rnd)
        }
        return AvatarConfig(defaults.filterKeys { !it.optional } + parts)
    }
}
