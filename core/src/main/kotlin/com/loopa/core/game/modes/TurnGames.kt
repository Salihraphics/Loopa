package com.loopa.core.game.modes

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Client-side helpers of the 0.8.0 board and card games. They only shape the display and
 * offer hints (which checker may move, which die fits); every move is validated again by the
 * server, which alone decides legality, dice, shuffles and results.
 */
object TavlaLogic {
    /** Mover-relative position: points 1..24 (index 0..23, own checkers > 0), bar and borne-off counts. */
    data class Pos(val b: List<Int>, val bar: Int, val obar: Int, val off: Int)

    /** Board from the server (player-1 space, index 0 = point 1) seen by [side] (1 or 2). */
    fun relative(b: List<Int>, side: Int): List<Int> = if (side == 1) b else List(24) { k -> -b[23 - k] }

    /** One checker move like the server's _tv_step; [from] 25 = bar. Null when illegal. */
    fun step(p: Pos, from: Int, die: Int): Pos? {
        val b = p.b.toMutableList()
        var bar = p.bar; var obar = p.obar; var off = p.off
        if (bar > 0 && from != 25) return null
        val t: Int
        if (from == 25) {
            if (bar <= 0) return null
            t = 25 - die
        } else {
            if (from !in 1..24 || b[from - 1] <= 0) return null
            t = from - die
        }
        if (t <= 0) {
            if (bar > 0) return null
            if ((7..24).any { b[it - 1] > 0 }) return null
            if (t < 0 && (from + 1..6).any { b[it - 1] > 0 }) return null
            b[from - 1] -= 1
            return Pos(b, bar, obar, off + 1)
        }
        if (b[t - 1] < -1) return null
        if (b[t - 1] == -1) { b[t - 1] = 0; obar += 1 }
        if (from == 25) bar -= 1 else b[from - 1] -= 1
        b[t - 1] += 1
        return Pos(b, bar, obar, off)
    }

    /** Dice values (distinct) that can move a checker from [from] now. */
    fun diceFor(p: Pos, from: Int, dice: List<Int>): List<Int> = dice.distinct().filter { step(p, from, it) != null }

    /** Points (and 25 for the bar) from which some remaining die can move. */
    fun sources(p: Pos, dice: List<Int>): List<Int> = (listOf(25) + (24 downTo 1)).filter { f -> dice.any { step(p, f, it) != null } }
}

/** Display of cards and tiles. */
object CardFace {
    fun rank(r: Int): String = when (r) { 1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> r.toString() }
    fun suit(s: String): String = when (s) { "S" -> "♠"; "H" -> "♥"; "D" -> "♦"; "C" -> "♣"; else -> "?" }
    fun isRed(s: String): Boolean = s == "H" || s == "D"

    /** Value of a 21 hand, same rule as the server (aces 11 or 1). */
    fun yirmiBir(ranks: List<Int>): Int {
        var total = 0; var aces = 0
        ranks.forEach { r -> when { r == 1 -> { aces++; total += 11 }; r >= 10 -> total += 10; else -> total += r } }
        while (total > 21 && aces > 0) { total -= 10; aces-- }
        return total
    }

    /** Renk Kartları card label. */
    fun renk(v: String): String = when (v) {
        "skip" -> "⊘"; "rev" -> "⇄"; "d2" -> "+2"; "d4" -> "+4"; "wild" -> "Joker"
        "temizle" -> "TEMİZLE"; "ayna" -> "AYNA"; "gamble" -> "GAMBLE"; "goster" -> "ELİNİ GÖRELİM"
        else -> v
    }

    /** 101 Okey tile id ("r5a", "fj1") -> colour and number; null colour for a false joker. */
    data class Tile(val id: String, val color: String?, val number: Int?)
    fun okeyTile(id: String): Tile =
        if (id.startsWith("fj")) Tile(id, null, null) else Tile(id, id.take(1), id.drop(1).dropLast(1).toIntOrNull())
}

/** Axial hex coordinates (pointy-top) for the Krallıklar map. */
object Hex {
    fun distance(q1: Int, r1: Int, q2: Int, r2: Int): Int = (abs(q1 - q2) + abs(r1 - r2) + abs(q1 + r1 - q2 - r2)) / 2
    /** Centre of a hex of [size] (centre-to-corner) in pixels. */
    fun center(q: Int, r: Int, size: Float): Pair<Float, Float> = Pair(size * sqrt(3f) * (q + r / 2f), size * 1.5f * r)
    /** Nearest hex to a point (inverse of [center]). */
    fun at(x: Float, y: Float, size: Float): Pair<Int, Int> {
        val qf = (sqrt(3f) / 3f * x - 1f / 3f * y) / size
        val rf = (2f / 3f * y) / size
        val sf = -qf - rf
        var q = Math.round(qf); var r = Math.round(rf); val s = Math.round(sf)
        val dq = abs(q - qf); val dr = abs(r - rf); val ds = abs(s - sf)
        if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s
        return Pair(q, r)
    }
    /** Turns a speed (tiles per turn) into the number of turn ends until arrival, like the server. */
    fun turnsToArrive(distance: Int, speed: Int): Int = maxOf((distance + speed - 1) / maxOf(speed, 1), 1)
}
