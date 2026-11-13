package com.loopa.core.game.modes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drawings are lists of strokes in a resolution-independent 0..1000 grid, so every phone renders
 * them the same way. Format (validated by the server): {"c":colour 0..11,"w":width 1..40,"e":eraser,
 * "p":[x0,y0,x1,y1,…]} or {"clear":true}.
 */
data class Stroke(
    val color: Int,
    val width: Int,
    val eraser: Boolean = false,
    val points: List<Int> = emptyList(),   // x0, y0, x1, y1 …
    val clear: Boolean = false,
) {
    val pointCount: Int get() = points.size / 2
}

object DrawingCodec {
    const val GRID = 1000
    const val MAX_POINTS_PER_STROKE = 400          // 800 numbers: server limit
    /** Pastel Party palette + ink; index 0 is the eraser/background colour. */
    val PALETTE: List<Long> = listOf(
        0xFFFFFFFF, 0xFF353344, 0xFFE78383, 0xFFF08A6C, 0xFFF2C14E, 0xFF5FB896,
        0xFF6FB3E0, 0xFF8E74D6, 0xFFB9A5E8, 0xFFFFB9A5, 0xFF9DDCC5, 0xFF8B5E3C,
    )
    val WIDTHS = listOf(4, 8, 16, 28)

    /** Converts a view coordinate to the grid. */
    fun quantize(value: Float, size: Float): Int = if (size <= 0f) 0 else (value / size * GRID).roundToInt().coerceIn(0, GRID)

    /**
     * Drops points closer than [minStep] grid units to the previous kept point (fewer bytes over the
     * network, same look), always keeping the first and the last point, and caps the count.
     */
    fun simplify(points: List<Int>, minStep: Int = 4): List<Int> {
        if (points.size <= 4) return points
        val out = ArrayList<Int>(points.size)
        out += points[0]; out += points[1]
        var lx = points[0]; var ly = points[1]
        var i = 2
        while (i < points.size - 2) {
            val x = points[i]; val y = points[i + 1]
            if (abs(x - lx) + abs(y - ly) >= minStep) { out += x; out += y; lx = x; ly = y }
            i += 2
        }
        out += points[points.size - 2]; out += points[points.size - 1]
        return if (out.size / 2 > MAX_POINTS_PER_STROKE) out.take(MAX_POINTS_PER_STROKE * 2) else out
    }

    /** Splits a long stroke into pieces the server accepts (each continues where the last ended). */
    fun split(stroke: Stroke): List<Stroke> {
        if (stroke.clear || stroke.pointCount <= MAX_POINTS_PER_STROKE) return listOf(stroke)
        val pieces = mutableListOf<Stroke>()
        var start = 0
        val step = (MAX_POINTS_PER_STROKE - 1) * 2
        while (start < stroke.points.size - 2) {
            val end = minOf(start + MAX_POINTS_PER_STROKE * 2, stroke.points.size)
            pieces += stroke.copy(points = stroke.points.subList(start, end))
            start += step
        }
        return pieces
    }

    fun encode(s: Stroke): JsonObject = if (s.clear) buildJsonObject { put("clear", true) } else buildJsonObject {
        put("c", s.color.coerceIn(0, PALETTE.size - 1))
        put("w", s.width.coerceIn(1, 40))
        if (s.eraser) put("e", true)
        put("p", buildJsonArray { s.points.forEach { add(JsonPrimitive(it.coerceIn(0, GRID))) } })
    }

    fun encodeAll(strokes: List<Stroke>): JsonArray = JsonArray(strokes.flatMap(::split).map(::encode))

    fun decode(e: JsonElement?): Stroke? {
        val o = e as? JsonObject ?: return null
        if ((o["clear"] as? JsonPrimitive)?.booleanOrNull == true) return Stroke(0, 1, clear = true)
        val pts = (o["p"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
        return Stroke(
            color = o["c"]?.jsonPrimitive?.intOrNull ?: 1,
            width = o["w"]?.jsonPrimitive?.intOrNull ?: 6,
            eraser = (o["e"] as? JsonPrimitive)?.booleanOrNull == true,
            points = if (pts.size % 2 == 0) pts else pts.dropLast(1),
        )
    }

    fun decodeAll(e: JsonElement?): List<Stroke> = (e as? JsonArray)?.mapNotNull(::decode) ?: emptyList()

    /** Strokes after the last "clear": what is on the canvas now. */
    fun visible(strokes: List<Stroke>): List<Stroke> {
        val lastClear = strokes.indexOfLast { it.clear }
        return if (lastClear < 0) strokes else strokes.subList(lastClear + 1, strokes.size)
    }
}

/** One entry of get_strokes. */
@Serializable
data class StrokeEntry(val seq: Long, @SerialName("s") val stroke: JsonElement)
