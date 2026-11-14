package com.loopa.core.game

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Host options of the 0.8.0 games, mirroring the server validators (`_opts_<mode>`). The panel
 * offers exactly the values the server accepts; anything else would be replaced by the server's
 * default anyway. The server remains the only authority.
 */
object ModeOptions {
    sealed interface Option { val key: String; val label: String }
    data class Choice(override val key: String, override val label: String, val values: List<Any>, val default: Any, val display: (Any) -> String = { it.toString() }) : Option
    data class Toggle(override val key: String, override val label: String, val default: Boolean) : Option
    /** A set of disabled items (Kurt Adam roles). */
    data class Disabled(override val key: String, override val label: String, val items: List<Pair<String, String>>) : Option

    private val sec: (Any) -> String = { "$it sn" }

    val kurtAdamRoles = listOf(
        "shapeshifter" to "Şekil Değiştiren", "alpha" to "Alfa Kurt", "silencer" to "Susturucu", "brute" to "Vahşi Kurt",
        "illusionist" to "İllüzyonist", "wolf_cub" to "Kurt Yavrusu", "seer" to "Kâhin", "doctor" to "Doktor", "hunter" to "Avcı",
        "witch" to "Cadı", "detective" to "Dedektif", "guardian" to "Muhafız", "medium" to "Medyum", "mayor" to "Muhtar",
        "jester" to "Soytarı", "assassin" to "Suikastçı (10+ oyuncu)",
    )

    fun of(mode: String): List<Option> = when (mode) {
        "yirmi_bir" -> listOf(Choice("hands", "El sayısı", listOf(3, 5, 7), 5), Choice("turn_seconds", "Hamle süresi", listOf(15, 20, 30), 20, sec))
        "tavla" -> listOf(Choice("points", "Kaç puana", listOf(1, 3, 5), 3), Choice("turn_seconds", "Hamle süresi", listOf(30, 45, 60, 90), 60, sec))
        "kizma_birader" -> listOf(Choice("turn_seconds", "Hamle süresi", listOf(15, 20, 30), 20, sec))
        "amiral_batti" -> listOf(Choice("format", "Biçim", listOf("1v1", "2v2"), "1v1"), Choice("turn_seconds", "Atış süresi", listOf(20, 30, 45), 30, sec))
        "renk_kartlari" -> listOf(Choice("rounds", "El sayısı", listOf(1, 3, 5), 3), Toggle("stacking", "+2 / +4 üst üste konabilir", true),
            Toggle("loopa_cards", "LOOPA kartları (TEMİZLE, AYNA, GAMBLE, ELİNİ GÖRELİM)", true), Choice("turn_seconds", "Hamle süresi", listOf(15, 20, 30), 20, sec))
        "okey" -> listOf(Choice("rounds", "El sayısı", listOf(1, 3, 5, 7), 3), Choice("turn_seconds", "Hamle süresi", listOf(30, 45, 60), 45, sec))
        "milyoner" -> listOf(Choice("max_rounds", "Tur sınırı", listOf(20, 30, 50), 30), Choice("turn_seconds", "Hamle süresi", listOf(20, 30, 45), 30, sec))
        "kurt_adam" -> listOf(Choice("night_seconds", "Gece süresi", listOf(30, 45, 60), 45, sec), Choice("day_seconds", "Tartışma süresi", listOf(60, 90, 120, 180), 90, sec),
            Choice("vote_seconds", "Oylama süresi", listOf(20, 30, 45), 30, sec), Disabled("disabled", "Kapalı roller", kurtAdamRoles))
        "kralliklar" -> listOf(Choice("max_turns", "Güvenlik tur sınırı", listOf(0, 60, 100, 150), 100) { if (it == 0) "Kapalı" else "$it tur" })
        else -> emptyList()
    }

    /** Every option filled in (current value or default), as sent to update_room_settings. */
    fun normalize(mode: String, current: JsonObject): JsonObject = JsonObject(of(mode).associate { o ->
        o.key to when (o) {
            is Choice -> {
                val v = current[o.key]?.let { e -> (e as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.content } }
                val pick = o.values.firstOrNull { it.toString() == v?.toString() } ?: o.default
                if (pick is Int) JsonPrimitive(pick) else JsonPrimitive(pick.toString())
            }
            is Toggle -> JsonPrimitive((current[o.key] as? JsonPrimitive)?.booleanOrNull ?: o.default)
            is Disabled -> JsonArray(((current[o.key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty())
                .filter { k -> o.items.any { it.first == k } }.map { JsonPrimitive(it) })
        }
    })

    fun value(options: JsonObject, key: String): String? = (options[key] as? JsonPrimitive)?.content

    /** Allowed player limits of a game for the room's "Oyuncu sınırı". */
    fun playerLimits(mode: String): List<Int> = when (mode) {
        "tavla", "yirmi_bir" -> listOf(2)
        "okey" -> listOf(4)
        "kizma_birader", "amiral_batti" -> listOf(2, 4)
        "renk_kartlari" -> listOf(4, 6, 8)
        "milyoner" -> listOf(4, 6)
        "kurt_adam" -> listOf(8, 12, 16, 20)
        "kralliklar" -> listOf(4, 8, 12, 16, 20)
        else -> listOf(4, 6, 10)
    }

    /** The limit to use after switching to [mode]: the current one if allowed, else the smallest that fits [players]. */
    fun fitLimit(mode: String, current: Int, players: Int): Int {
        val l = playerLimits(mode)
        return if (current in l) current else l.firstOrNull { it >= players } ?: l.last()
    }
}
