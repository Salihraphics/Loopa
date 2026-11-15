package com.loopa.feature.game.modes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original LOOPA artwork for Krallıklar: a stylized low-poly isometric look drawn with Canvas
 * primitives (no bitmaps, no 3D engine). The hex grid of the server is kept as is; only the
 * projection is isometric: world y is squashed and every tile gets a height (a prism).
 */
internal object KrArt {
    /** Vertical squash of the isometric projection (lower = flatter, more depth on the sides). */
    const val ISO = 0.56f
    /** Height of an unexplored (cloud) tile. */
    const val FOG_H = 0.16f

    val Ink = Color(0xFF353344)
    val Parchment = Color(0xFFFFF9F0)
    val Gold = Color(0xFFF2C14E)

    /**
     * Sea and fog follow the app theme; terrain keeps its natural colours in both.
     * Unexplored land is dark cloud in both themes ("dark and vague"); explored land gets a veil.
     */
    data class Scenery(
        val seaTop: Color, val seaBottom: Color, val wave: Color, val foam: Color,
        val cloudTop: Color, val cloudSide: Color, val cloudPuff: Color, val veil: Color,
    )
    val LightScenery = Scenery(Color(0xFF8FD0EE), Color(0xFF4E9FD1), Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.7f),
        Color(0xFF6C6782), Color(0xFF4E4A62), Color(0xFFB9B4CC).copy(alpha = 0.45f), Color(0xFFEDEAF5).copy(alpha = 0.58f))
    val DarkScenery = Scenery(Color(0xFF235A86), Color(0xFF173B5E), Color(0xFFA9D5F2).copy(alpha = 0.18f), Color(0xFFCFE8F7).copy(alpha = 0.35f),
        Color(0xFF34304A), Color(0xFF242036), Color(0xFF6C6782).copy(alpha = 0.45f), Color(0xFF1E1B2B).copy(alpha = 0.55f))
    // tile dock preview of an unexplored tile
    val FogTop = Color(0xFF6C6782)
    val FogSide = Color(0xFF4E4A62)

    data class Terrain(val top: Color, val side: Color, val height: Float, val name: String)

    // natural biomes: vivid but not neon; earthy sides (soil for lowland, rock for highland)
    private val Soil = Color(0xFFA27D55)
    private val Rock = Color(0xFF7E7A92)
    fun terrain(t: String?): Terrain = when (t) {
        "forest" -> Terrain(Color(0xFF5DAA55), Soil, 0.3f, "Orman")
        "farm" -> Terrain(Color(0xFFE7BE47), Soil, 0.24f, "Tarım arazisi")
        "mine" -> Terrain(Color(0xFFA6A1B8), Rock, 0.52f, "Maden (dağ)")
        "gold" -> Terrain(Color(0xFFB7AB93), Color(0xFF857A66), 0.42f, "Altın tepesi")
        else -> Terrain(Color(0xFF9BD26C), Soil, 0.24f, "Ova")
    }

    /** Top-face vertex k (pointy-top hex, isometric). Edge k runs from vertex k to k + 1. */
    fun vertex(c: Offset, s: Float, k: Int): Offset {
        val a = Math.toRadians(60.0 * k - 30.0)
        return Offset(c.x + s * cos(a).toFloat(), c.y + s * sin(a).toFloat() * ISO)
    }

    fun topPath(c: Offset, s: Float): Path = Path().apply {
        for (k in 0 until 6) { val v = vertex(c, s, k); if (k == 0) moveTo(v.x, v.y) else lineTo(v.x, v.y) }
        close()
    }

    private fun face(c: Offset, s: Float, from: Int, to: Int, drop: Float) = Path().apply {
        val v = (from..to).map { vertex(c, s, it) }
        moveTo(v[0].x, v[0].y); v.drop(1).forEach { lineTo(it.x, it.y) }
        v.reversed().forEach { lineTo(it.x, it.y + drop) }; close()
    }

    /**
     * One prism: three side faces (right shaded, front mid, left lit) in soil or rock with a strip
     * of the top colour along the upper edge (grass overhang), then the top face. [c] is the TOP
     * face centre.
     */
    fun DrawScope.prism(c: Offset, s: Float, h: Float, top: Color, side: Color, tint: Color? = null, band: Color? = null) {
        val drop = h * s
        drawPath(face(c, s, 0, 1, drop), lerp(side, Ink, 0.32f))          // right (shade)
        drawPath(face(c, s, 1, 2, drop), lerp(side, Ink, 0.12f))          // front
        drawPath(face(c, s, 2, 3, drop), lerp(side, Color.White, 0.08f))  // left (light)
        // grass / moss overhang on the upper edge of the sides
        val lip = minOf(drop * 0.32f, s * 0.09f)
        for ((a, b) in listOf(0 to 1, 1 to 2, 2 to 3)) {
            val pa = vertex(c, s, a); val pb = vertex(c, s, b)
            drawPath(Path().apply { moveTo(pa.x, pa.y); lineTo(pb.x, pb.y); lineTo(pb.x, pb.y + lip); lineTo(pa.x, pa.y + lip); close() },
                if (a == 0) lerp(band ?: top, Ink, 0.28f) else lerp(band ?: top, Ink, 0.1f))
            // strata line
            drawLine(Ink.copy(alpha = 0.12f), Offset(pa.x, pa.y + drop * 0.62f), Offset(pb.x, pb.y + drop * 0.62f), s * 0.015f)
        }
        val tp = topPath(c, s)
        drawPath(tp, Brush.linearGradient(listOf(lerp(top, Color.White, 0.2f), top, lerp(top, Ink, 0.08f)),
            Offset(c.x - s, c.y - s * ISO), Offset(c.x + s, c.y + s * ISO)))
        if (tint != null) drawPath(tp, tint)
        drawPath(Path().apply { val a = vertex(c, s, 3); moveTo(a.x, a.y); for (k in 4..6) vertex(c, s, k % 6).let { lineTo(it.x, it.y) } },
            Color.White.copy(alpha = 0.35f), style = Stroke(s * 0.03f, cap = StrokeCap.Round))
        drawPath(tp, Ink.copy(alpha = 0.16f), style = Stroke(s * 0.02f))
    }

    // ------------------------------------------------------------------ fog levels
    /** Unexplored: a low, dark cloud bank with soft billows (no terrain, nothing known). */
    fun DrawScope.cloudTile(c: Offset, s: Float, seed: Int, sc: Scenery, detail: Boolean) {
        prism(c, s, FOG_H, sc.cloudTop, sc.cloudSide)
        if (!detail) return
        for (k in 0..2) {
            val dx = (rnd(seed, k) - 0.5f) * s * 0.9f; val dy = (rnd(seed, k + 5) - 0.5f) * s * 0.5f * ISO
            val w = s * (0.55f + rnd(seed, k + 11) * 0.4f)
            drawOval(sc.cloudPuff, Offset(c.x + dx - w / 2, c.y + dy - w * 0.32f), Size(w, w * 0.42f))
        }
    }

    /** Explored but out of sight: a translucent veil over the terrain and a drifting wisp. */
    fun DrawScope.veil(c: Offset, s: Float, seed: Int, sc: Scenery, tall: Float) {
        drawPath(topPath(c, s), sc.veil)
        // cover what stands on the tile (trees, peaks) with a soft column of mist
        drawOval(Brush.verticalGradient(listOf(Color.Transparent, sc.veil), c.y - s * tall, c.y),
            Offset(c.x - s * 0.85f, c.y - s * tall), Size(s * 1.7f, s * tall + s * 0.2f))
        val dx = (rnd(seed, 3) - 0.5f) * s * 0.5f
        drawOval(sc.veil, Offset(c.x - s * 0.55f + dx, c.y - s * 0.32f), Size(s * 0.9f, s * 0.26f))
    }

    // ------------------------------------------------------------------ terrain details (seeded per tile)
    private fun rnd(seed: Int, k: Int): Float = (((seed * 73856093) xor (k * 19349663)) and 0x7fffffff) % 1000 / 1000f

    /** Contact shadow under an object standing on a tile. */
    private fun DrawScope.shadow(base: Offset, w: Float) =
        drawOval(Ink.copy(alpha = 0.2f), Offset(base.x - w / 2, base.y - w * 0.12f), Size(w, w * 0.24f))

    /** How tall the objects of a terrain stand above the top face (for the mist column). */
    fun tallness(t: String?): Float = when (t) { "forest" -> 0.75f; "mine" -> 1.0f; "gold" -> 0.6f; else -> 0.3f }

    fun DrawScope.details(t: String?, c: Offset, s: Float, seed: Int, depleted: Boolean) {
        when (t) {
            "forest" -> {
                // a dense stand of trees, back to front
                val spots = listOf(Offset(-0.1f, -0.5f), Offset(0.32f, -0.4f), Offset(-0.5f, -0.22f), Offset(0.06f, -0.16f), Offset(0.5f, -0.06f),
                    Offset(-0.3f, 0.1f), Offset(0.22f, 0.2f), Offset(-0.56f, 0.32f), Offset(-0.06f, 0.42f), Offset(0.42f, 0.42f))
                spots.forEachIndexed { k, o ->
                    val p = Offset(c.x + o.x * s + (rnd(seed, k) - 0.5f) * s * 0.08f, c.y + o.y * s * ISO * 1.6f)
                    if (depleted) { if (k % 3 == 0) stump(p, s * 0.5f) else if (k % 3 == 1) grass(p, s) }
                    else if (rnd(seed, k + 20) < 0.3f) roundTree(p, s * (0.34f + rnd(seed, k + 9) * 0.1f))
                    else tree(p, s * (0.42f + rnd(seed, k + 9) * 0.16f), k + seed)
                }
            }
            "mine" -> {
                // grey-lavender mountain range with snow, ore boulders and a mine entrance
                peak(Offset(c.x - s * 0.34f, c.y + s * 0.1f), s * 0.7f, Color(0xFFC9C4DA), Color(0xFF8A84A2), snow = true)
                peak(Offset(c.x + s * 0.14f, c.y + s * 0.02f), s * 1.0f, Color(0xFFD5D1E4), Color(0xFF948EAC), snow = true)
                peak(Offset(c.x + s * 0.5f, c.y + s * 0.22f), s * 0.5f, Color(0xFFBDB7CF), Color(0xFF7F7998), snow = false)
                if (!depleted) {
                    val m = Offset(c.x - s * 0.1f, c.y + s * 0.3f)
                    drawRoundRect(Color(0xFF2E2A3A), Offset(m.x - s * 0.1f, m.y - s * 0.16f), Size(s * 0.2f, s * 0.16f), CornerRadius(s * 0.09f, s * 0.09f))
                    drawLine(Color(0xFF8A6446), Offset(m.x - s * 0.12f, m.y), Offset(m.x - s * 0.12f, m.y - s * 0.17f), s * 0.035f)
                    drawLine(Color(0xFF8A6446), Offset(m.x + s * 0.12f, m.y), Offset(m.x + s * 0.12f, m.y - s * 0.17f), s * 0.035f)
                    drawLine(Color(0xFF8A6446), Offset(m.x - s * 0.14f, m.y - s * 0.17f), Offset(m.x + s * 0.14f, m.y - s * 0.17f), s * 0.04f)
                    // rails and ore
                    drawLine(Color(0xFF6E6880), m, m + Offset(s * 0.28f, s * 0.12f), s * 0.02f)
                    ore(m + Offset(s * 0.3f, s * 0.08f), s * 0.11f, Color(0xFF9FB3C8))
                    ore(m + Offset(-s * 0.3f, s * 0.06f), s * 0.09f, Color(0xFFB7C6D6))
                }
            }
            "gold" -> {
                // rocky outcrops with metallic gold veins
                rock(Offset(c.x - s * 0.26f, c.y + s * 0.1f), s * 0.52f, Color(0xFFC2B7A0), Color(0xFF8E8370))
                rock(Offset(c.x + s * 0.26f, c.y + s * 0.18f), s * 0.42f, Color(0xFFB5AA92), Color(0xFF827763))
                rock(Offset(c.x + s * 0.02f, c.y - s * 0.12f), s * 0.36f, Color(0xFFCABFA8), Color(0xFF948972))
                if (!depleted) for (k in 0..3) {
                    val p = Offset(c.x + (k - 1.5f) * s * 0.2f, c.y + s * (0.14f + 0.06f * (k % 2)))
                    nugget(p, s * (0.07f + 0.025f * (k % 2)))
                }
            }
            "farm" -> {
                // three iso field plots: golden wheat, green sprouts, ploughed soil; a haystack
                val plots = listOf(Triple(Offset(-0.28f, -0.1f), Color(0xFFF0C64C), Color(0xFFD9A93A)),
                    Triple(Offset(0.3f, -0.06f), if (depleted) Color(0xFFC9A877) else Color(0xFFB9D96A), Color(0xFF8DB34E)),
                    Triple(Offset(0.0f, 0.3f), if (depleted) Color(0xFFC9A877) else Color(0xFFF4D266), Color(0xFFD9AE45)))
                plots.forEachIndexed { k, (o, fill, row) ->
                    val pc = Offset(c.x + o.x * s, c.y + o.y * s * ISO * 1.6f); val w = s * 0.46f; val h = s * 0.46f * ISO
                    val plot = Path().apply { moveTo(pc.x - w, pc.y); lineTo(pc.x, pc.y - h); lineTo(pc.x + w, pc.y); lineTo(pc.x, pc.y + h); close() }
                    drawPath(plot, if (depleted && k != 0) Color(0xFFC9A877) else fill)
                    for (r in -2..2) {   // crop rows parallel to one edge of the plot
                        val t = r * 0.3f
                        val p0 = Offset(pc.x - w * 0.5f + t * w * 0.5f, pc.y + t * h * 0.5f)
                        drawLine(row, p0, p0 + Offset(w * 0.5f, -h * 0.5f), s * 0.025f, StrokeCap.Round)
                    }
                    drawPath(plot, Ink.copy(alpha = 0.12f), style = Stroke(s * 0.015f))
                }
                if (!depleted && rnd(seed, 40) < 0.6f) hay(Offset(c.x + s * 0.48f, c.y + s * 0.24f), s * 0.16f)
            }
            else -> {
                for (k in 0..4) grass(Offset(c.x + (rnd(seed, k) - 0.5f) * s * 1.2f, c.y + (rnd(seed, k + 4) - 0.5f) * s * 0.8f * ISO), s)
                if (rnd(seed, 30) < 0.55f) {   // flowers
                    val p = Offset(c.x + (rnd(seed, 31) - 0.5f) * s * 0.8f, c.y + (rnd(seed, 32) - 0.5f) * s * 0.5f * ISO)
                    drawCircle(Color(0xFFFFB9A5), s * 0.035f, p); drawCircle(Color(0xFFFFF4B8), s * 0.035f, p + Offset(s * 0.1f, s * 0.03f))
                    drawCircle(Color(0xFFB9A5E8), s * 0.03f, p + Offset(-s * 0.08f, s * 0.04f))
                }
                if (rnd(seed, 33) < 0.4f) {    // a lone tree or a boulder
                    val p = Offset(c.x + (rnd(seed, 34) - 0.5f) * s * 0.9f, c.y + (rnd(seed, 35) - 0.3f) * s * 0.4f * ISO)
                    if (rnd(seed, 36) < 0.5f) roundTree(p, s * 0.32f) else rock(p, s * 0.18f, Color(0xFFC9C4D6), Color(0xFF8E89A0))
                }
            }
        }
    }

    private fun DrawScope.grass(p: Offset, s: Float) {
        drawLine(Color(0xFF5E9C45), p, p + Offset(-s * 0.05f, -s * 0.1f), s * 0.028f, StrokeCap.Round)
        drawLine(Color(0xFF6DAF50), p, p + Offset(s * 0.05f, -s * 0.11f), s * 0.028f, StrokeCap.Round)
    }

    /** Low-poly conifer: two stacked tiers, light west face and shaded east face. */
    private fun DrawScope.tree(base: Offset, h: Float, k: Int) {
        shadow(base, h * 0.55f)
        drawLine(Color(0xFF7A5536), base, base + Offset(0f, -h * 0.22f), h * 0.09f)
        val light = if (k % 2 == 0) Color(0xFF3F9A55) else Color(0xFF4AA65C)
        val dark = Color(0xFF256B3C)
        for ((bottom, top, w) in listOf(Triple(0.18f, 0.72f, 0.34f), Triple(0.48f, 1.0f, 0.25f))) {
            val tip = base + Offset(0f, -h * top)
            val l = base + Offset(-h * w, -h * bottom); val r = base + Offset(h * w, -h * bottom); val m = base + Offset(h * 0.04f, -h * bottom)
            drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(m.x, m.y); close() }, light)
            drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(m.x, m.y); lineTo(r.x, r.y); close() }, dark)
        }
    }

    /** Round broadleaf tree: two-tone canopy. */
    private fun DrawScope.roundTree(base: Offset, h: Float) {
        shadow(base, h * 0.7f)
        drawLine(Color(0xFF7A5536), base, base + Offset(0f, -h * 0.45f), h * 0.11f)
        val c = base + Offset(0f, -h * 0.72f)
        drawCircle(Color(0xFF3E8F4E), h * 0.36f, c)
        drawCircle(Color(0xFF6CC067), h * 0.25f, c + Offset(-h * 0.08f, -h * 0.08f))
    }

    private fun DrawScope.stump(base: Offset, h: Float) {
        drawRoundRect(Color(0xFF8A6446), base + Offset(-h * 0.1f, -h * 0.18f), Size(h * 0.2f, h * 0.18f), CornerRadius(h * 0.04f))
        drawOval(Color(0xFFE6C9A0), base + Offset(-h * 0.1f, -h * 0.22f), Size(h * 0.2f, h * 0.08f))
    }

    private fun DrawScope.hay(base: Offset, r: Float) {
        shadow(base, r * 2.2f)
        drawArc(Color(0xFFE0B34A), 180f, 180f, true, Offset(base.x - r, base.y - r * 1.4f), Size(r * 2, r * 2.4f))
        drawArc(Color(0xFFF2CF6A), 200f, 90f, true, Offset(base.x - r, base.y - r * 1.4f), Size(r * 2, r * 2.4f))
    }

    /** Low-poly peak: light west face, shaded east face, a chiselled facet and an optional snow cap. */
    private fun DrawScope.peak(base: Offset, h: Float, light: Color, dark: Color, snow: Boolean) {
        shadow(base + Offset(0f, h * 0.02f), h * 1.1f)
        val tip = base + Offset(h * 0.05f, -h)
        val l = base + Offset(-h * 0.55f, 0f); val r = base + Offset(h * 0.55f, 0f); val m = base + Offset(h * 0.12f, 0f)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(m.x, m.y); close() }, light)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(m.x, m.y); lineTo(r.x, r.y); close() }, dark)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x + h * 0.25f, l.y - h * 0.18f); lineTo(m.x - h * 0.05f, m.y); close() }, lerp(light, Color.White, 0.22f))
        if (snow) drawPath(Path().apply {
            moveTo(tip.x, tip.y); lineTo(tip.x - h * 0.2f, tip.y + h * 0.32f); lineTo(tip.x - h * 0.04f, tip.y + h * 0.24f)
            lineTo(tip.x + h * 0.04f, tip.y + h * 0.32f); lineTo(tip.x + h * 0.18f, tip.y + h * 0.3f); close()
        }, Color.White)
    }

    /** Faceted boulder / outcrop. */
    private fun DrawScope.rock(base: Offset, h: Float, light: Color, dark: Color) {
        shadow(base, h * 1.4f)
        val top = base + Offset(-h * 0.1f, -h * 0.75f)
        drawPath(Path().apply { moveTo(base.x - h * 0.7f, base.y); lineTo(base.x - h * 0.5f, base.y - h * 0.5f); lineTo(top.x, top.y); lineTo(base.x + h * 0.05f, base.y); close() }, light)
        drawPath(Path().apply { moveTo(top.x, top.y); lineTo(base.x + h * 0.5f, base.y - h * 0.4f); lineTo(base.x + h * 0.65f, base.y); lineTo(base.x + h * 0.05f, base.y); close() }, dark)
    }

    /** Metallic ore chunk: bluish steel facets with a highlight. */
    private fun DrawScope.ore(c: Offset, r: Float, metal: Color) {
        drawPath(Path().apply { moveTo(c.x - r, c.y); lineTo(c.x - r * 0.3f, c.y - r * 0.9f); lineTo(c.x + r, c.y - r * 0.3f); lineTo(c.x + r * 0.5f, c.y + r * 0.3f); close() }, metal)
        drawPath(Path().apply { moveTo(c.x - r * 0.3f, c.y - r * 0.9f); lineTo(c.x + r, c.y - r * 0.3f); lineTo(c.x + r * 0.1f, c.y - r * 0.2f); close() }, Color.White.copy(alpha = 0.6f))
    }

    private fun DrawScope.nugget(c: Offset, r: Float) {
        drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r * 0.7f); lineTo(c.x - r, c.y); close() }, Color(0xFFE0A93A))
        drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y); close() }, Color(0xFFFFE39A))
        drawCircle(Color.White.copy(alpha = 0.8f), r * 0.18f, c + Offset(-r * 0.3f, -r * 0.4f))
    }

    // ------------------------------------------------------------------ buildings
    /** An isometric box seen from the front: left face lit, right face shaded, flat top. Base = front corner. */
    private fun DrawScope.box(base: Offset, w: Float, d: Float, h: Float, wall: Color, top: Color = lerp(wall, Color.White, 0.3f)) {
        val l = Offset(base.x - w / 2, base.y - d * ISO * 0.5f); val r = Offset(base.x + w / 2, base.y - d * ISO * 0.5f)
        val f = Offset(base.x, base.y); val b = Offset(base.x, base.y - d * ISO)
        drawPath(Path().apply { moveTo(l.x, l.y); lineTo(f.x, f.y); lineTo(f.x, f.y - h); lineTo(l.x, l.y - h); close() }, lerp(wall, Color.White, 0.1f))
        drawPath(Path().apply { moveTo(f.x, f.y); lineTo(r.x, r.y); lineTo(r.x, r.y - h); lineTo(f.x, f.y - h); close() }, lerp(wall, Ink, 0.24f))
        drawPath(Path().apply { moveTo(l.x, l.y - h); lineTo(f.x, f.y - h); lineTo(r.x, r.y - h); lineTo(b.x, b.y - h); close() }, top)
    }

    /** A cone roof. */
    private fun DrawScope.cone(c: Offset, r: Float, h: Float, color: Color) {
        drawPath(Path().apply { moveTo(c.x - r, c.y); lineTo(c.x, c.y - h); lineTo(c.x, c.y + r * 0.3f); close() }, lerp(color, Color.White, 0.12f))
        drawPath(Path().apply { moveTo(c.x, c.y - h); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r * 0.3f); close() }, lerp(color, Ink, 0.3f))
    }

    /** Round tower: lit/shaded halves, crenellated top, cone roof in the owner's colour and a pennant. */
    private fun DrawScope.tower(base: Offset, r: Float, h: Float, stone: Color, roof: Color, pennant: Boolean) {
        drawRect(lerp(stone, Color.White, 0.08f), Offset(base.x - r, base.y - h), Size(r, h))
        drawRect(lerp(stone, Ink, 0.26f), Offset(base.x, base.y - h), Size(r, h))
        drawOval(lerp(stone, Ink, 0.1f), Offset(base.x - r, base.y - r * ISO), Size(r * 2, r * ISO * 2))
        // crenellations
        for (k in 0..3) drawRect(lerp(stone, if (k < 2) Color.White else Ink, 0.12f), Offset(base.x - r + k * r * 0.52f, base.y - h - r * 0.28f), Size(r * 0.3f, r * 0.32f))
        drawOval(lerp(stone, Ink, 0.15f), Offset(base.x - r, base.y - h - r * ISO), Size(r * 2, r * ISO * 2))
        cone(Offset(base.x, base.y - h - r * 0.1f), r * 1.18f, r * 2.1f, roof)
        if (pennant) {
            val p = Offset(base.x, base.y - h - r * 2.2f)
            drawLine(Ink, p, p + Offset(0f, -r * 1.1f), r * 0.12f)
            drawPath(Path().apply { moveTo(p.x, p.y - r * 1.1f); lineTo(p.x + r * 1.2f, p.y - r * 0.85f); lineTo(p.x, p.y - r * 0.6f); close() }, roof)
        }
    }

    /** A wall segment between two ground points with crenellations on top. [lit] picks the face shade. */
    private fun DrawScope.wall(a: Offset, b: Offset, h: Float, stone: Color, shade: Float) {
        val col = if (shade >= 0f) lerp(stone, Color.White, shade) else lerp(stone, Ink, -shade)
        drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x, b.y - h); lineTo(a.x, a.y - h); close() }, col)
        drawLine(Ink.copy(alpha = 0.18f), a, b, h * 0.06f)
        val n = 6
        for (k in 0 until n) {
            val f = (k + 0.25f) / n; val p = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f - h)
            drawRect(col, Offset(p.x, p.y - h * 0.16f), Size((b.x - a.x) / n * 0.5f, h * 0.17f))
        }
    }

    /**
     * Castle. A capital ([grand] = true) is a walled fortress: stone plinth, four corner towers,
     * curtain walls with crenellations, a gatehouse with a banner, a courtyard and a tall keep with
     * turrets; every roof and flag in the kingdom's colour. Without [grand] it is a small hill fort.
     */
    fun DrawScope.castle(c: Offset, s: Float, flag: Color, grand: Boolean = true) {
        val stone = Color(0xFFD9D3E3)
        val k = if (grand) 1.32f else 0.62f
        val W = s * 1.5f * k; val D = s * 1.5f * k * ISO * 0.5f
        val cc = c + Offset(0f, s * 0.12f)
        val F = cc + Offset(0f, D); val B = cc - Offset(0f, D); val L = cc - Offset(W / 2, 0f); val R = cc + Offset(W / 2, 0f)
        val wh = s * 0.32f * k; val tr = s * 0.13f * k; val th = s * 0.55f * k
        // plinth and courtyard
        val ext = 1.12f
        val plinth = Path().apply { moveTo(cc.x, cc.y - D * ext); lineTo(cc.x + W / 2 * ext, cc.y); lineTo(cc.x, cc.y + D * ext); lineTo(cc.x - W / 2 * ext, cc.y); close() }
        drawPath(plinth, lerp(stone, Ink, 0.18f))
        drawPath(Path().apply { moveTo(cc.x - W / 2 * ext, cc.y); lineTo(cc.x, cc.y + D * ext); lineTo(cc.x, cc.y + D * ext + s * 0.06f); lineTo(cc.x - W / 2 * ext, cc.y + s * 0.06f); close() }, lerp(stone, Ink, 0.3f))
        drawPath(Path().apply { moveTo(cc.x + W / 2 * ext, cc.y); lineTo(cc.x, cc.y + D * ext); lineTo(cc.x, cc.y + D * ext + s * 0.06f); lineTo(cc.x + W / 2 * ext, cc.y + s * 0.06f); close() }, lerp(stone, Ink, 0.42f))
        drawPath(Path().apply { moveTo(B.x, B.y); lineTo(R.x, R.y); lineTo(F.x, F.y); lineTo(L.x, L.y); close() }, Color(0xFFE9DCC0))
        // back towers and walls (inner faces are in shade)
        tower(B, tr, th, stone, flag, pennant = grand)
        wall(L, B, wh, stone, -0.22f); wall(B, R, wh, stone, -0.3f)
        // keep with turrets
        val kb = cc + Offset(0f, D * 0.15f)
        box(kb, W * 0.36f, W * 0.36f, s * 0.78f * k, stone)
        val keepTop = kb - Offset(0f, s * 0.78f * k + W * 0.36f * ISO * 0.5f)
        if (grand) {
            tower(keepTop + Offset(-W * 0.12f, W * 0.06f), tr * 0.7f, s * 0.2f, stone, flag, pennant = false)
            tower(keepTop + Offset(W * 0.12f, W * 0.06f), tr * 0.7f, s * 0.2f, stone, flag, pennant = false)
        }
        cone(keepTop + Offset(0f, W * 0.04f), W * 0.15f, s * 0.36f * k, flag)
        // main banner on the keep
        val pole = keepTop - Offset(0f, s * 0.34f * k)
        drawLine(Ink, pole, pole + Offset(0f, -s * 0.34f * k), s * 0.025f)
        drawPath(Path().apply { moveTo(pole.x, pole.y - s * 0.34f * k); lineTo(pole.x + s * 0.34f * k, pole.y - s * 0.26f * k); lineTo(pole.x, pole.y - s * 0.17f * k); close() }, flag)
        // side towers, front walls and the gatehouse
        tower(L, tr, th, stone, flag, pennant = grand)
        tower(R, tr, th, stone, flag, pennant = grand)
        wall(L, F, wh, stone, 0.12f); wall(F, R, wh, stone, -0.24f)
        val g = Offset((L.x + F.x) / 2, (L.y + F.y) / 2)
        drawRoundRect(Color(0xFF4A3F57), Offset(g.x - s * 0.07f * k, g.y - wh * 0.62f), Size(s * 0.14f * k, wh * 0.62f), CornerRadius(s * 0.07f * k, s * 0.07f * k))
        drawLine(Color(0xFF8A6446), Offset(g.x - s * 0.07f * k, g.y - wh * 0.05f), Offset(g.x + s * 0.07f * k, g.y - wh * 0.05f), s * 0.02f)
        // hanging banner beside the gate
        if (grand) {
            val bx = Offset((F.x + R.x) / 2, (F.y + R.y) / 2 - wh * 0.92f)
            drawPath(Path().apply { moveTo(bx.x - s * 0.06f, bx.y); lineTo(bx.x + s * 0.06f, bx.y); lineTo(bx.x + s * 0.06f, bx.y + wh * 0.62f); lineTo(bx.x, bx.y + wh * 0.5f); lineTo(bx.x - s * 0.06f, bx.y + wh * 0.62f); close() }, flag)
        }
        tower(F, tr * 1.05f, th * 1.05f, stone, flag, pennant = grand)
    }

    /** Crown badge floating over a capital so it stands out at every zoom level. */
    fun DrawScope.crownBadge(c: Offset, r: Float, color: Color) {
        drawCircle(Color.White, r * 1.12f, c)
        drawCircle(color, r, c)
        drawPath(Path().apply {
            val e = r * 0.55f
            moveTo(c.x - e, c.y + e * 0.5f); lineTo(c.x - e, c.y - e * 0.35f); lineTo(c.x - e * 0.5f, c.y + e * 0.05f); lineTo(c.x, c.y - e * 0.65f)
            lineTo(c.x + e * 0.5f, c.y + e * 0.05f); lineTo(c.x + e, c.y - e * 0.35f); lineTo(c.x + e, c.y + e * 0.5f); close()
        }, Color.White)
    }

    /** Barracks: a palisade outpost with a watchtower and the owner's flag (a small fort). */
    fun DrawScope.barracks(c: Offset, s: Float, flag: Color) {
        val b = c + Offset(0f, s * 0.12f)
        shadow(b, s * 1.2f)
        val rx = s * 0.52f; val ry = s * 0.52f * ISO
        // back half of the palisade
        for (k in 0..10) {
            val a = Math.PI + k * Math.PI / 10
            val p = Offset(b.x + rx * cos(a).toFloat(), b.y + ry * sin(a).toFloat())
            drawLine(Color(0xFF8A6446), p, p + Offset(0f, -s * 0.2f), s * 0.05f, StrokeCap.Round)
        }
        // tents and watchtower
        drawPath(Path().apply { moveTo(b.x - s * 0.38f, b.y + s * 0.02f); lineTo(b.x - s * 0.2f, b.y - s * 0.3f); lineTo(b.x - s * 0.02f, b.y + s * 0.02f); close() }, Color(0xFFF1E4CC))
        drawPath(Path().apply { moveTo(b.x - s * 0.2f, b.y - s * 0.3f); lineTo(b.x - s * 0.02f, b.y + s * 0.02f); lineTo(b.x - s * 0.12f, b.y + s * 0.02f); close() }, Color(0xFFCDB894))
        box(b + Offset(s * 0.16f, -s * 0.02f), s * 0.22f, s * 0.22f, s * 0.42f, Color(0xFFB08458))
        cone(b + Offset(s * 0.16f, -s * 0.48f), s * 0.17f, s * 0.2f, flag)
        val pole = b + Offset(s * 0.16f, -s * 0.68f)
        drawLine(Ink, pole, pole + Offset(0f, -s * 0.22f), s * 0.025f)
        drawPath(Path().apply { moveTo(pole.x, pole.y - s * 0.22f); lineTo(pole.x + s * 0.22f, pole.y - s * 0.16f); lineTo(pole.x, pole.y - s * 0.1f); close() }, flag)
        // front half of the palisade
        for (k in 0..10) {
            val a = k * Math.PI / 10
            val p = Offset(b.x + rx * cos(a).toFloat(), b.y + ry * sin(a).toFloat())
            drawLine(if (k == 5) Color.Transparent else Color(0xFF9A714E), p, p + Offset(0f, -s * 0.2f), s * 0.05f, StrokeCap.Round)
        }
    }

    fun DrawScope.market(c: Offset, s: Float) {
        val w = s * 0.78f; val b = c + Offset(0f, s * 0.18f)
        shadow(b, w * 1.1f)
        box(b, w * 0.8f, w * 0.5f, w * 0.3f, Color(0xFFC9A27A))
        for (k in 0..4) drawPath(Path().apply {
            val x = b.x - w * 0.46f + k * w * 0.184f; val y = b.y - w * 0.3f
            moveTo(x, y); lineTo(x + w * 0.184f, y); lineTo(x + w * 0.15f, y - w * 0.22f); lineTo(x + w * 0.03f, y - w * 0.22f); close()
        }, if (k % 2 == 0) Color(0xFFE5675A) else Color(0xFFFFF4E6))
        drawCircle(Gold, w * 0.06f, b + Offset(-w * 0.17f, -w * 0.12f)); drawCircle(Color(0xFF6CC067), w * 0.06f, b + Offset(w * 0.02f, -w * 0.1f))
        drawCircle(Color(0xFFE58C78), w * 0.06f, b + Offset(w * 0.2f, -w * 0.12f))
    }

    fun DrawScope.barn(c: Offset, s: Float) {
        val w = s * 0.74f; val b = c + Offset(-s * 0.06f, s * 0.18f)
        shadow(b, w * 1.2f)
        box(b, w * 0.66f, w * 0.48f, w * 0.36f, Color(0xFFC8553D))
        val l = Offset(b.x - w * 0.36f, b.y - w * 0.36f - w * 0.24f * ISO * 0.5f); val r = Offset(b.x + w * 0.36f, l.y); val ridge = Offset(b.x - w * 0.02f, l.y - w * 0.3f)
        drawPath(Path().apply { moveTo(l.x, l.y); lineTo(ridge.x, ridge.y); lineTo(b.x, b.y - w * 0.36f + w * 0.02f); close() }, Color(0xFF6B4A3A))
        drawPath(Path().apply { moveTo(ridge.x, ridge.y); lineTo(r.x, r.y); lineTo(b.x, b.y - w * 0.36f + w * 0.02f); close() }, Color(0xFF4E352A))
        drawRect(Color(0xFFFFF4E6), b + Offset(-w * 0.2f, -w * 0.24f), Size(w * 0.14f, w * 0.22f))
        val so = b + Offset(w * 0.5f, -w * 0.05f)
        drawRoundRect(Color(0xFFDCD6EA), Offset(so.x - w * 0.1f, so.y - w * 0.55f), Size(w * 0.2f, w * 0.55f), CornerRadius(w * 0.04f))
        drawRect(Color(0xFFB4ADC8), Offset(so.x, so.y - w * 0.55f), Size(w * 0.1f, w * 0.55f))
        drawArc(Color(0xFF6FB3E0), 180f, 180f, true, Offset(so.x - w * 0.1f, so.y - w * 0.66f), Size(w * 0.2f, w * 0.22f))
    }

    /** A small territory flag on an owned tile (when nothing else on it shows the owner). */
    fun DrawScope.territoryFlag(c: Offset, s: Float, color: Color) {
        val p = c + Offset(-s * 0.42f, s * 0.08f)
        drawLine(Ink.copy(alpha = 0.8f), p, p + Offset(0f, -s * 0.42f), s * 0.03f)
        drawPath(Path().apply { moveTo(p.x, p.y - s * 0.42f); lineTo(p.x + s * 0.24f, p.y - s * 0.35f); lineTo(p.x, p.y - s * 0.27f); close() }, color)
        drawOval(Ink.copy(alpha = 0.2f), Offset(p.x - s * 0.05f, p.y - s * 0.015f), Size(s * 0.1f, s * 0.03f))
    }

    /** A stylized soldier: owner-coloured tunic and shield, helmet with plume, spear; and a count banner. */
    fun DrawScope.troops(c: Offset, s: Float, color: Color, count: Int, selected: Boolean, bob: Float) {
        val h = s * 0.9f
        val base = c + Offset(s * 0.42f, s * 0.05f - bob * s * 0.06f)
        drawOval(Ink.copy(alpha = 0.22f), Offset(base.x - h * 0.3f, base.y - h * 0.05f + bob * s * 0.06f), Size(h * 0.6f, h * 0.16f))
        val outline = Stroke(h * 0.035f)
        val edge = Ink.copy(alpha = 0.55f)
        // legs
        drawRoundRect(Color(0xFF5A5470), base + Offset(-h * 0.1f, -h * 0.2f), Size(h * 0.08f, h * 0.2f), CornerRadius(h * 0.03f))
        drawRoundRect(Color(0xFF4A4560), base + Offset(h * 0.03f, -h * 0.2f), Size(h * 0.08f, h * 0.2f), CornerRadius(h * 0.03f))
        // tunic
        val body = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(base.x - h * 0.14f, base.y - h * 0.62f, base.x + h * 0.14f, base.y - h * 0.16f, CornerRadius(h * 0.09f))) }
        drawPath(body, lerp(color, Color.White, 0.1f)); drawPath(body, edge, style = outline)
        drawRect(lerp(color, Ink, 0.35f), base + Offset(-h * 0.14f, -h * 0.34f), Size(h * 0.28f, h * 0.05f))
        // head and helmet with plume
        drawCircle(Color(0xFFF6D7BE), h * 0.12f, base + Offset(0f, -h * 0.74f))
        drawArc(Color(0xFFB9B4CC), 180f, 180f, true, base + Offset(-h * 0.14f, -h * 0.9f), Size(h * 0.28f, h * 0.26f))
        drawLine(Color(0xFFB9B4CC), base + Offset(-h * 0.15f, -h * 0.77f), base + Offset(h * 0.15f, -h * 0.77f), h * 0.04f, StrokeCap.Round)
        drawOval(color, base + Offset(-h * 0.05f, -h * 1.0f), Size(h * 0.1f, h * 0.14f))
        // spear
        drawLine(Color(0xFF8A6446), base + Offset(h * 0.22f, -h * 1.12f), base + Offset(h * 0.22f, 0f), h * 0.045f)
        drawPath(Path().apply { val x = base.x + h * 0.22f; val y = base.y - h * 1.12f; moveTo(x, y - h * 0.12f); lineTo(x + h * 0.05f, y + h * 0.02f); lineTo(x - h * 0.05f, y + h * 0.02f); close() }, Color(0xFFDCD6EA))
        // shield
        val sh = Path().apply {
            val x = base.x - h * 0.32f; val y = base.y - h * 0.56f
            moveTo(x, y); lineTo(x + h * 0.27f, y); lineTo(x + h * 0.27f, y + h * 0.22f); lineTo(x + h * 0.135f, y + h * 0.35f); lineTo(x, y + h * 0.22f); close()
        }
        drawPath(sh, color); drawPath(sh, Color.White.copy(alpha = 0.8f), style = Stroke(h * 0.04f))
        if (count <= 0) return
        // count banner
        val label = if (count > 99) "99+" else count.toString()
        val bw = h * (0.32f + 0.16f * label.length); val by = base.y - h * 1.42f
        drawRoundRect(if (selected) Color.White else Ink.copy(alpha = 0.88f), Offset(base.x - bw / 2, by), Size(bw, h * 0.34f), CornerRadius(h * 0.17f))
        drawRoundRect(color, Offset(base.x - bw / 2, by), Size(bw, h * 0.34f), CornerRadius(h * 0.17f), style = Stroke(h * 0.05f))
        drawContext.canvas.nativeCanvas.drawText(label, base.x, by + h * 0.26f, android.graphics.Paint().apply {
            isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = h * 0.28f; isFakeBoldText = true
            this.color = if (selected) android.graphics.Color.rgb(53, 51, 68) else android.graphics.Color.WHITE
        })
    }

    /** A neutral garrison: a small muted shield with its size (quiet, so kingdoms stand out). */
    fun DrawScope.garrison(c: Offset, s: Float, count: Int) {
        val w = s * 0.34f; val o = c + Offset(s * 0.28f, -s * 0.46f)
        val p = Path().apply {
            moveTo(o.x, o.y); lineTo(o.x + w, o.y); lineTo(o.x + w, o.y + w * 0.7f); lineTo(o.x + w / 2, o.y + w * 1.1f); lineTo(o.x, o.y + w * 0.7f); close()
        }
        drawPath(p, Color(0xFF8C86A0)); drawPath(p, Color.White.copy(alpha = 0.8f), style = Stroke(w * 0.08f))
        drawContext.canvas.nativeCanvas.drawText(count.toString(), o.x + w / 2, o.y + w * 0.66f, android.graphics.Paint().apply {
            isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = w * 0.62f; isFakeBoldText = true; color = android.graphics.Color.WHITE
        })
    }

    /** Crossed swords marking a battle of the last turn. */
    fun DrawScope.swords(c: Offset, s: Float, alpha: Float) {
        val r = s * 0.4f
        drawCircle(Color.White.copy(alpha = 0.9f * alpha), r, c)
        drawCircle(Color(0xFFE5675A).copy(alpha = alpha), r * 0.84f, c)
        drawLine(Color.White.copy(alpha = alpha), c + Offset(-r * 0.5f, -r * 0.5f), c + Offset(r * 0.5f, r * 0.5f), r * 0.15f, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = alpha), c + Offset(r * 0.5f, -r * 0.5f), c + Offset(-r * 0.5f, r * 0.5f), r * 0.15f, StrokeCap.Round)
    }

    // ------------------------------------------------------------------ crests (one emblem per kingdom number)
    fun DrawScope.crest(o: Offset, w: Float, color: Color, k: Int) {
        val h = w * 1.18f
        val shield = Path().apply {
            moveTo(o.x, o.y); lineTo(o.x + w, o.y); lineTo(o.x + w, o.y + h * 0.55f)
            quadraticTo(o.x + w, o.y + h * 0.88f, o.x + w / 2, o.y + h); quadraticTo(o.x, o.y + h * 0.88f, o.x, o.y + h * 0.55f); close()
        }
        drawPath(shield, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.25f), lerp(color, Ink, 0.12f)), o.y, o.y + h))
        drawPath(shield, Color.White, style = Stroke(w * 0.08f))
        val c = Offset(o.x + w / 2, o.y + h * 0.45f); val e = w * 0.26f
        val ink = Parchment
        when (((k - 1) % 6 + 6) % 6) {
            0 -> { // tower
                drawRect(ink, Offset(c.x - e * 0.5f, c.y - e * 0.6f), Size(e, e * 1.3f))
                for (j in 0..2) drawRect(ink, Offset(c.x - e * 0.6f + j * e * 0.45f, c.y - e * 0.85f), Size(e * 0.28f, e * 0.3f))
            }
            1 -> { // crown
                drawPath(Path().apply {
                    moveTo(c.x - e, c.y + e * 0.5f); lineTo(c.x - e, c.y - e * 0.4f); lineTo(c.x - e * 0.5f, c.y); lineTo(c.x, c.y - e * 0.7f)
                    lineTo(c.x + e * 0.5f, c.y); lineTo(c.x + e, c.y - e * 0.4f); lineTo(c.x + e, c.y + e * 0.5f); close()
                }, ink)
            }
            2 -> { // star
                drawPath(Path().apply {
                    for (j in 0 until 10) {
                        val a = Math.toRadians(-90.0 + j * 36); val rr = if (j % 2 == 0) e else e * 0.42f
                        val p = Offset(c.x + rr * cos(a).toFloat(), c.y + rr * sin(a).toFloat())
                        if (j == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }, ink)
            }
            3 -> { // sun
                drawCircle(ink, e * 0.5f, c)
                for (j in 0 until 8) {
                    val a = Math.toRadians(j * 45.0)
                    drawLine(ink, c + Offset(e * 0.65f * cos(a).toFloat(), e * 0.65f * sin(a).toFloat()), c + Offset(e * cos(a).toFloat(), e * sin(a).toFloat()), e * 0.16f, StrokeCap.Round)
                }
            }
            4 -> { // leaf
                drawPath(Path().apply { moveTo(c.x, c.y - e); quadraticTo(c.x + e, c.y, c.x, c.y + e); quadraticTo(c.x - e, c.y, c.x, c.y - e); close() }, ink)
                drawLine(lerp(color, Ink, 0.2f), c + Offset(0f, -e * 0.8f), c + Offset(0f, e * 0.8f), e * 0.1f)
            }
            else -> { // waves
                for (j in 0..2) drawArc(ink, 200f, 140f, false, Offset(c.x - e, c.y - e * 0.8f + j * e * 0.55f), Size(e * 2f, e * 0.8f), style = Stroke(e * 0.18f))
            }
        }
    }

    // ------------------------------------------------------------------ resource and journal icons
    fun DrawScope.resourceIcon(kind: String, o: Offset, s: Float) {
        when (kind) {
            "wood" -> { // two logs
                for (j in 0..1) {
                    val y = o.y + s * (0.35f + j * 0.25f); val x = o.x + s * (0.12f + j * 0.12f)
                    drawRoundRect(Color(0xFFB07E54), Offset(x, y), Size(s * 0.66f, s * 0.22f), CornerRadius(s * 0.11f))
                    drawCircle(Color(0xFFF1D3A8), s * 0.1f, Offset(x + s * 0.1f, y + s * 0.11f))
                }
            }
            "iron" -> { // ingot
                drawPath(Path().apply {
                    moveTo(o.x + s * 0.12f, o.y + s * 0.7f); lineTo(o.x + s * 0.28f, o.y + s * 0.38f); lineTo(o.x + s * 0.84f, o.y + s * 0.38f); lineTo(o.x + s * 0.92f, o.y + s * 0.7f); close()
                }, Color(0xFFA49DBC))
                drawRect(Color(0xFFDCD6EA), Offset(o.x + s * 0.3f, o.y + s * 0.4f), Size(s * 0.5f, s * 0.06f))
            }
            "gold" -> {
                drawCircle(Gold, s * 0.32f, Offset(o.x + s * 0.5f, o.y + s * 0.52f))
                drawCircle(Color(0xFFFFE39A), s * 0.2f, Offset(o.x + s * 0.5f, o.y + s * 0.52f), style = Stroke(s * 0.06f))
            }
            "food" -> { // wheat
                drawLine(Color(0xFFB9952F), Offset(o.x + s * 0.5f, o.y + s * 0.9f), Offset(o.x + s * 0.5f, o.y + s * 0.15f), s * 0.07f)
                for (j in 0..3) {
                    val y = o.y + s * (0.22f + j * 0.15f)
                    drawOval(Color(0xFFE9C25A), Offset(o.x + s * 0.28f, y), Size(s * 0.2f, s * 0.12f))
                    drawOval(Color(0xFFE9C25A), Offset(o.x + s * 0.52f, y), Size(s * 0.2f, s * 0.12f))
                }
            }
            "troops" -> troops(Offset(o.x + s * 0.1f, o.y + s * 0.85f), s * 1.0f, Color(0xFF8E74D6), 0, false, 0f)
        }
    }
}
