package com.loopa.core.game.modes

import com.loopa.core.game.TurkishText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class ChainLink(val word: String = "", @SerialName("user_id") val userId: String? = null, val points: Int = 0)

@Serializable
data class ChainPublic(
    val word: String = "",
    val letter: String = "",
    val lives: Map<String, Int> = emptyMap(),
    val chain: List<ChainLink> = emptyList(),
    val total: Int? = null,
)

@Serializable data class ChainRoundPublic(val word: String = "", val letter: String = "", val seconds: Int = 20)

@Serializable
data class ChainResults(val valid: Boolean = false, val word: String? = null, val points: Int = 0, val timeout: Boolean = false,
                        val left: Boolean = false, val lives: Int? = null)

@Serializable data class ChainView(val history: List<ChainLink> = emptyList())

/**
 * Kelime Zinciri: each word starts with the last letter of the previous one. The comparison is
 * spelling-insensitive, like the server's dictionary keys: ş/s, ç/c, ğ/g … count as one letter, so a
 * word ending in "ğ" (dağ) continues with G (gemi).
 */
object KelimeZinciriLogic {
    const val START_LIVES = 3
    const val SURVIVOR_BONUS = 15

    enum class Check { OK, EMPTY, MULTIPLE_WORDS, NOT_LETTERS, TOO_SHORT, WRONG_LETTER, USED }

    fun key(word: String) = TurkishText.key(word)
    fun lastLetter(word: String): String = key(word).takeLast(1)

    /** Points of a valid word (same formula as the server): 10, +1 per letter over 4, at most 15. */
    fun points(word: String): Int = 10 + (key(word).length - 4).coerceIn(0, 5)

    /** Quick local check, so obvious mistakes do not need a round trip. The server decides. */
    fun precheck(input: String, letter: String, used: Collection<String> = emptyList()): Check {
        val t = input.trim()
        if (t.isEmpty()) return Check.EMPTY
        if (t.any { it.isWhitespace() }) return Check.MULTIPLE_WORDS
        if (!t.all { it.isLetter() }) return Check.NOT_LETTERS
        val k = key(t)
        if (k.length < 2) return Check.TOO_SHORT
        if (letter.isNotEmpty() && !k.startsWith(letter)) return Check.WRONG_LETTER
        if (used.any { key(it) == k }) return Check.USED
        return Check.OK
    }

    fun message(c: Check, letter: String): String? = when (c) {
        Check.OK -> null
        Check.EMPTY -> "Bir kelime yaz."
        Check.MULTIPLE_WORDS -> "Tek kelime yazmalısın."
        Check.NOT_LETTERS -> "Yalnızca harf kullan."
        Check.TOO_SHORT -> "Kelime çok kısa."
        Check.WRONG_LETTER -> "Kelime ${letterLabel(letter)} ile başlamalı."
        Check.USED -> "Bu kelime zincirde zaten var."
    }

    /** Folded letters cover the Turkish pair: "s" -> "S / Ş". */
    fun letterLabel(letter: String): String = when (letter) {
        "c" -> "C / Ç"; "g" -> "G / Ğ"; "i" -> "I / İ"; "o" -> "O / Ö"; "s" -> "S / Ş"; "u" -> "U / Ü"
        else -> letter.uppercase()
    }
}
