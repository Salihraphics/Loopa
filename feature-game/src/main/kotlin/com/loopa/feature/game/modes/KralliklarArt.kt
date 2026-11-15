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

    // Pastel Party map palette: soft, light and clean, with enough value contrast to read terrain.
    val Ink = Color(0xFF353344)
    val Parchment = Color(0xFFFFF9F0)
    val Gold = Color(0xFFF2C14E)

    /** Sea and fog follow the app theme; terrain stays the same in both. */
    data class Scenery(val seaTop: Color, val seaBottom: Color, val wave: Color, val fogTop: Color, val fogSide: Color, val fogPuff: Color)
    val LightScenery = Scenery(Color(0xFFD7EEF8), Color(0xFFA9D5F2), Color.White.copy(alpha = 0.55f), Color(0xFFE4DEF0), Color(0xFFC3BAD8), Color.White.copy(alpha = 0.5f))
    val DarkScenery = Scenery(Color(0xFF2E3A5A), Color(0xFF232A44), Color(0xFFA9D5F2).copy(alpha = 0.14f), Color(0xFF3E3856), Color(0xFF2C283F), Color(0xFFB9A5E8).copy(alpha = 0.10f))
    // kept for the tile dock preview
    val FogTop = Color(0xFFE4DEF0)
    val FogSide = Color(0xFFC3BAD8)

    data class Terrain(val top: Color, val side: Color, val height: Float, val name: String)

    fun terrain(t: String?): Terrain = when (t) {
        "forest" -> Terrain(Color(0xFF9DD3AE), Color(0xFF6FA88A), 0.34f, "Orman")
        "farm" -> Terrain(Color(0xFFF6DF95), Color(0xFFD1B06A), 0.26f, "Tarım arazisi")
        "mine" -> Terrain(Color(0xFFCFC9DE), Color(0xFF9A93B1), 0.6f, "Maden (dağ)")
        "gold" -> Terrain(Color(0xFFF6CFA0), Color(0xFFCF9E6C), 0.48f, "Altın tepesi")
        else -> Terrain(Color(0xFFC6E5A8), Color(0xFF94BE7E), 0.26f, "Ova")
    }

    /** Top-face vertex k (pointy-top hex, isometric). */
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
     * One prism: three lit side faces (right shaded, front mid, left lit), a soil band at the
     * bottom, then the top face. [c] is the TOP face centre. [band] draws the owner's colour along
     * the top edge of the sides so territory reads even from far away.
     */
    fun DrawScope.prism(c: Offset, s: Float, h: Float, top: Color, side: Color, tint: Color? = null, band: Color? = null) {
        val drop = h * s
        drawPath(face(c, s, 0, 1, drop), lerp(side, Ink, 0.22f))          // right (shade)
        drawPath(face(c, s, 1, 2, drop), side)                              // front
        drawPath(face(c, s, 2, 3, drop), lerp(side, Color.White, 0.16f))   // left (light)
        // soil band and contact line at the base
        val soil = drop * 0.28f
        for ((a, b) in listOf(0 to 1, 1 to 2, 2 to 3)) {
            val pa = vertex(c, s, a); val pb = vertex(c, s, b)
            drawPath(Path().apply {
                moveTo(pa.x, pa.y + drop - soil); lineTo(pb.x, pb.y + drop - soil); lineTo(pb.x, pb.y + drop); lineTo(pa.x, pa.y + drop); close()
            }, Ink.copy(alpha = 0.10f))
        }
        if (band != null) for ((a, b) in listOf(0 to 1, 1 to 2, 2 to 3)) {
            val pa = vertex(c, s, a); val pb = vertex(c, s, b); val bh = minOf(drop * 0.45f, s * 0.12f)
            drawPath(Path().apply { moveTo(pa.x, pa.y); lineTo(pb.x, pb.y); lineTo(pb.x, pb.y + bh); lineTo(pa.x, pa.y + bh); close() },
                if (a == 0) lerp(band, Ink, 0.2f) else band)
        }
        val tp = topPath(c, s)
        drawPath(tp, Brush.linearGradient(listOf(lerp(top, Color.White, 0.28f), top, lerp(top, Ink, 0.06f)),
            Offset(c.x - s, c.y - s * ISO), Offset(c.x + s, c.y + s * ISO)))
        if (tint != null) drawPath(tp, tint)
        // rim light on the back edges, a soft outline on the front ones
        drawPath(Path().apply { val a = vertex(c, s, 3); moveTo(a.x, a.y); for (k in 4..6) vertex(c, s, k % 6).let { lineTo(it.x, it.y) } },
            Color.White.copy(alpha = 0.55f), style = Stroke(s * 0.035f, cap = StrokeCap.Round))
        drawPath(tp, Ink.copy(alpha = 0.14f), style = Stroke(s * 0.025f))
    }

    // ------------------------------------------------------------------ terrain details (seeded per tile)
    private fun rnd(seed: Int, k: Int): Float = (((seed * 73856093) xor (k * 19349663)) and 0x7fffffff) % 1000 / 1000f

    /** Contact shadow under an object standing on a tile. */
    private fun DrawScope.shadow(base: Offset, w: Float) =
        drawOval(Ink.copy(alpha = 0.16f), Offset(base.x - w / 2, base.y - w * 0.12f), Size(w, w * 0.24f))

    fun DrawScope.details(t: String?, c: Offset, s: Float, seed: Int, depleted: Boolean) {
        when (t) {
            "forest" -> {
                // back to front so nearer trees overlap farther ones
                val spots = listOf(Offset(0.08f, -0.42f), Offset(-0.42f, -0.16f), Offset(0.44f, -0.08f), Offset(-0.06f, 0.06f), Offset(-0.38f, 0.32f), Offset(0.3f, 0.36f))
                spots.forEachIndexed { k, o ->
                    val p = Offset(c.x + o.x * s + (rnd(seed, k) - 0.5f) * s * 0.1f, c.y + o.y * s * ISO * 1.7f)
                    if (depleted) { if (k % 2 == 0) stump(p, s * 0.5f) }
                    else if (rnd(seed, k + 20) < 0.28f) roundTree(p, s * (0.36f + rnd(seed, k + 9) * 0.1f))
                    else tree(p, s * (0.46f + rnd(seed, k + 9) * 0.16f), k)
                }
            }
            "mine" -> {
                peak(Offset(c.x - s * 0.3f, c.y + s * 0.16f), s * 0.6f, Color(0xFFDCD6EA), Color(0xFF9790B0), snow = !depleted)
                peak(Offset(c.x + s * 0.12f, c.y + s * 0.06f), s * 0.86f, Color(0xFFE6E1F2), Color(0xFFA49DBC), snow = !depleted)
                peak(Offset(c.x + s * 0.46f, c.y + s * 0.24f), s * 0.44f, Color(0xFFD3CCE3), Color(0xFF8F88A8), snow = false)
                if (!depleted) {
                    // mine entrance with timber frame
                    val m = Offset(c.x - s * 0.06f, c.y + s * 0.24f)
                    drawRoundRect(Color(0xFF3E3A4C), Offset(m.x - s * 0.09f, m.y - s * 0.14f), Size(s * 0.18f, s * 0.14f), CornerRadius(s * 0.08f, s * 0.08f))
                    drawLine(Color(0xFF9A6F4A), Offset(m.x - s * 0.11f, m.y), Offset(m.x - s * 0.11f, m.y - s * 0.15f), s * 0.035f)
                    drawLine(Color(0xFF9A6F4A), Offset(m.x + s * 0.11f, m.y), Offset(m.x + s * 0.11f, m.y - s * 0.15f), s * 0.035f)
                    drawLine(Color(0xFF9A6F4A), Offset(m.x - s * 0.13f, m.y - s * 0.15f), Offset(m.x + s * 0.13f, m.y - s * 0.15f), s * 0.035f)
                }
            }
            "gold" -> {
                hill(Offset(c.x - s * 0.18f, c.y + s * 0.12f), s * 0.5f, Color(0xFFF8D9AE), Color(0xFFD7A673))
                hill(Offset(c.x + s * 0.24f, c.y + s * 0.2f), s * 0.38f, Color(0xFFF3CFA0), Color(0xFFCC9864))
                if (!depleted) for (k in 0..3) {
                    val p = Offset(c.x + (k - 1.5f) * s * 0.17f, c.y + s * (0.18f + 0.05f * (k % 2)))
                    nugget(p, s * (0.08f + 0.02f * (k % 2)))
                }
            }
            "farm" -> {
                // diagonal field strips with a path, wheat tufts when alive
                for (k in -2..2) {
                    val y = c.y + k * s * 0.15f * ISO * 1.6f
                    val half = s * (0.72f - 0.12f * kotlin.math.abs(k))
                    drawLine(if (depleted) Color(0xFFD8C8A6) else if (k % 2 == 0) Color(0xFFEFC765) else Color(0xFFC3DFA2),
                        Offset(c.x - half, y), Offset(c.x + half, y), s * 0.075f, StrokeCap.Round)
                }
                drawLine(Color(0xFFE8D2A6), Offset(c.x - s * 0.15f, c.y - s * 0.45f * ISO), Offset(c.x + s * 0.15f, c.y + s * 0.45f * ISO), s * 0.05f, StrokeCap.Round)
            }
            else -> {
                for (k in 0..3) {
                    val p = Offset(c.x + (rnd(seed, k) - 0.5f) * s * 1.1f, c.y + (rnd(seed, k + 4) - 0.5f) * s * 0.7f * ISO)
                    drawLine(Color(0xFF7DB070), p, p + Offset(-s * 0.05f, -s * 0.1f), s * 0.03f, StrokeCap.Round)
                    drawLine(Color(0xFF7DB070), p, p + Offset(s * 0.05f, -s * 0.11f), s * 0.03f, StrokeCap.Round)
                }
                if (rnd(seed, 30) < 0.5f) {   // a few flowers
                    val p = Offset(c.x + (rnd(seed, 31) - 0.5f) * s * 0.8f, c.y + (rnd(seed, 32) - 0.5f) * s * 0.5f * ISO)
                    drawCircle(Color(0xFFFFB9A5), s * 0.035f, p); drawCircle(Color(0xFFB9A5E8), s * 0.035f, p + Offset(s * 0.1f, s * 0.03f))
                }
            }
        }
    }

    /** Low-poly conifer: two stacked tiers, light west face and shaded east face. */
    private fun DrawScope.tree(base: Offset, h: Float, k: Int) {
        shadow(base, h * 0.55f)
        drawLine(Color(0xFF8A6446), base, base + Offset(0f, -h * 0.22f), h * 0.09f)
        val light = if (k % 2 == 0) Color(0xFF5FAE7E) else Color(0xFF6BB98A)
        val dark = Color(0xFF3B7F62)
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
        drawLine(Color(0xFF8A6446), base, base + Offset(0f, -h * 0.45f), h * 0.11f)
        val c = base + Offset(0f, -h * 0.72f)
        drawCircle(Color(0xFF4F9A73), h * 0.36f, c)
        drawCircle(Color(0xFF7CC69A), h * 0.26f, c + Offset(-h * 0.08f, -h * 0.07f))
    }

    private fun DrawScope.stump(base: Offset, h: Float) {
        drawRoundRect(Color(0xFF9A714E), base + Offset(-h * 0.1f, -h * 0.18f), Size(h * 0.2f, h * 0.18f), CornerRadius(h * 0.04f))
        drawOval(Color(0xFFE6C9A0), base + Offset(-h * 0.1f, -h * 0.22f), Size(h * 0.2f, h * 0.08f))
    }

    /** Low-poly peak: light west face, shaded east face, a ridge line and an optional snow cap. */
    private fun DrawScope.peak(base: Offset, h: Float, light: Color, dark: Color, snow: Boolean) {
        shadow(base + Offset(0f, h * 0.02f), h * 1.1f)
        val tip = base + Offset(h * 0.05f, -h)
        val l = base + Offset(-h * 0.55f, 0f); val r = base + Offset(h * 0.55f, 0f); val m = base + Offset(h * 0.12f, 0f)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(m.x, m.y); close() }, light)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(m.x, m.y); lineTo(r.x, r.y); close() }, dark)
        // a secondary facet for a chiselled look
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x + h * 0.25f, l.y - h * 0.18f); lineTo(m.x - h * 0.05f, m.y); close() }, lerp(light, Color.White, 0.25f))
        if (snow) drawPath(Path().apply {
            moveTo(tip.x, tip.y); lineTo(tip.x - h * 0.2f, tip.y + h * 0.32f); lineTo(tip.x - h * 0.04f, tip.y + h * 0.24f)
            lineTo(tip.x + h * 0.04f, tip.y + h * 0.32f); lineTo(tip.x + h * 0.18f, tip.y + h * 0.3f); close()
        }, Color.White)
    }

    /** Rounded hill with a lit flank. */
    private fun DrawScope.hill(base: Offset, h: Float, light: Color, dark: Color) {
        shadow(base, h * 1.3f)
        drawArc(dark, 180f, 180f, true, Offset(base.x - h * 0.65f, base.y - h * 0.8f), Size(h * 1.3f, h * 1.6f))
        drawArc(light, 180f, 120f, true, Offset(base.x - h * 0.65f, base.y - h * 0.8f), Size(h * 1.3f, h * 1.6f))
    }

    private fun DrawScope.nugget(c: Offset, r: Float) {
        drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r * 0.7f); lineTo(c.x - r, c.y); close() }, Color(0xFFE0A93A))
        drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y); close() }, Color(0xFFFFE39A))
    }

    // ------------------------------------------------------------------ buildings (isometric boxes with lit / shaded faces)
    /** An isometric box seen from the front-left: left face lit, right face shaded, flat top. */
    private fun DrawScope.box(base: Offset, w: Float, d: Float, h: Float, wall: Color, top: Color = lerp(wall, Color.White, 0.3f)) {
        val l = Offset(base.x - w / 2, base.y - d * ISO * 0.5f); val r = Offset(base.x + w / 2, base.y - d * ISO * 0.5f)
        val f = Offset(base.x, base.y); val b = Offset(base.x, base.y - d * ISO)
        drawPath(Path().apply { moveTo(l.x, l.y); lineTo(f.x, f.y); lineTo(f.x, f.y - h); lineTo(l.x, l.y - h); close() }, lerp(wall, Color.White, 0.12f))
        drawPath(Path().apply { moveTo(f.x, f.y); lineTo(r.x, r.y); lineTo(r.x, r.y - h); lineTo(f.x, f.y - h); close() }, lerp(wall, Ink, 0.2f))
        drawPath(Path().apply { moveTo(l.x, l.y - h); lineTo(f.x, f.y - h); lineTo(r.x, r.y - h); lineTo(b.x, b.y - h); close() }, top)
    }

    /** A cone roof over a tower. */
    private fun DrawScope.cone(c: Offset, r: Float, h: Float, color: Color) {
        drawPath(Path().apply { moveTo(c.x - r, c.y); lineTo(c.x, c.y - h); lineTo(c.x, c.y + r * 0.3f); close() }, lerp(color, Color.White, 0.15f))
        drawPath(Path().apply { moveTo(c.x, c.y - h); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r * 0.3f); close() }, lerp(color, Ink, 0.25f))
    }

    fun DrawScope.castle(c: Offset, s: Float, flag: Color) {
        val w = s * 1.05f; val base = c + Offset(0f, s * 0.24f)
        val stone = Color(0xFFE2DBEF)
        shadow(base, w * 1.2f)
        // curtain wall
        box(base, w * 0.92f, w * 0.7f, w * 0.3f, stone)
        for (k in 0..4) drawRect(lerp(stone, Ink, 0.08f), Offset(base.x - w * 0.44f + k * w * 0.2f, base.y - w * 0.3f - w * 0.32f * ISO + (if (k < 3) k else 4 - k) * w * 0.05f), Size(w * 0.08f, w * 0.07f))
        // keep
        box(base + Offset(0f, -w * 0.12f), w * 0.46f, w * 0.38f, w * 0.62f, stone)
        cone(base + Offset(0f, -w * 0.74f - w * 0.38f * ISO * 0.5f), w * 0.27f, w * 0.34f, flag)
        // corner towers
        for (dx in listOf(-0.44f, 0.44f)) {
            val tb = base + Offset(w * dx, -w * 0.18f * ISO)
            box(tb, w * 0.2f, w * 0.2f, w * 0.48f, stone)
            cone(tb + Offset(0f, -w * 0.48f - w * 0.1f * ISO), w * 0.13f, w * 0.22f, flag)
        }
        // gate
        drawRoundRect(Color(0xFF6B5A7A), base + Offset(-w * 0.16f, -w * 0.2f), Size(w * 0.13f, w * 0.2f), CornerRadius(w * 0.065f, w * 0.065f))
        // banner on the keep
        val pole = base + Offset(0f, -w * 1.08f - w * 0.38f * ISO * 0.5f)
        drawLine(Ink, pole, pole + Offset(0f, -w * 0.3f), w * 0.025f)
        drawPath(Path().apply { moveTo(pole.x, pole.y - w * 0.3f); lineTo(pole.x + w * 0.3f, pole.y - w * 0.24f); lineTo(pole.x, pole.y - w * 0.17f); close() }, flag)
    }

    fun DrawScope.barracks(c: Offset, s: Float, flag: Color) {
        val w = s * 0.78f; val b = c + Offset(0f, s * 0.18f)
        shadow(b, w * 1.1f)
        // canvas tent with a ridge
        drawPath(Path().apply { moveTo(b.x - w * 0.5f, b.y); lineTo(b.x - w * 0.05f, b.y - w * 0.62f); lineTo(b.x + w * 0.12f, b.y); close() }, Color(0xFFF1E4CC))
        drawPath(Path().apply { moveTo(b.x - w * 0.05f, b.y - w * 0.62f); lineTo(b.x + w * 0.5f, b.y - w * 0.12f); lineTo(b.x + w * 0.12f, b.y); close() }, Color(0xFFCDB894))
        drawPath(Path().apply { moveTo(b.x - w * 0.18f, b.y); lineTo(b.x - w * 0.06f, b.y - w * 0.3f); lineTo(b.x + w * 0.04f, b.y); close() }, Color(0xFF6B5A7A))
        // owner pennant
        drawLine(Ink, b + Offset(-w * 0.05f, -w * 0.62f), b + Offset(-w * 0.05f, -w * 0.98f), w * 0.035f)
        drawPath(Path().apply { val x = b.x - w * 0.05f; val y = b.y - w * 0.98f; moveTo(x, y); lineTo(x + w * 0.3f, y + w * 0.08f); lineTo(x, y + w * 0.17f); close() }, flag)
        // weapon rack
        for (k in 0..1) drawLine(Color(0xFF8A6446), b + Offset(w * (0.34f + k * 0.1f), -w * 0.02f), b + Offset(w * (0.3f + k * 0.1f), -w * 0.36f), w * 0.03f)
    }

    fun DrawScope.market(c: Offset, s: Float) {
        val w = s * 0.78f; val b = c + Offset(0f, s * 0.18f)
        shadow(b, w * 1.1f)
        box(b, w * 0.8f, w * 0.5f, w * 0.3f, Color(0xFFD9B48C))
        // striped awning (peach / cream)
        for (k in 0..4) drawPath(Path().apply {
            val x = b.x - w * 0.46f + k * w * 0.184f; val y = b.y - w * 0.3f
            moveTo(x, y); lineTo(x + w * 0.184f, y); lineTo(x + w * 0.15f, y - w * 0.22f); lineTo(x + w * 0.03f, y - w * 0.22f); close()
        }, if (k % 2 == 0) Color(0xFFF08A6C) else Color(0xFFFFF4E6))
        drawCircle(Gold, w * 0.06f, b + Offset(-w * 0.17f, -w * 0.12f)); drawCircle(Color(0xFF9DDCC5), w * 0.06f, b + Offset(w * 0.02f, -w * 0.1f))
        drawCircle(Color(0xFFB9A5E8), w * 0.06f, b + Offset(w * 0.2f, -w * 0.12f))
    }

    fun DrawScope.barn(c: Offset, s: Float) {
        val w = s * 0.74f; val b = c + Offset(-s * 0.06f, s * 0.18f)
        shadow(b, w * 1.2f)
        box(b, w * 0.66f, w * 0.48f, w * 0.36f, Color(0xFFE58C78))
        // pitched roof
        val l = Offset(b.x - w * 0.36f, b.y - w * 0.36f - w * 0.24f * ISO * 0.5f); val r = Offset(b.x + w * 0.36f, l.y); val ridge = Offset(b.x - w * 0.02f, l.y - w * 0.3f)
        drawPath(Path().apply { moveTo(l.x, l.y); lineTo(ridge.x, ridge.y); lineTo(b.x, b.y - w * 0.36f + w * 0.02f); close() }, Color(0xFF8E74D6))
        drawPath(Path().apply { moveTo(ridge.x, ridge.y); lineTo(r.x, r.y); lineTo(b.x, b.y - w * 0.36f + w * 0.02f); close() }, Color(0xFF6E57B0))
        drawRect(Color(0xFFFFF4E6), b + Offset(-w * 0.2f, -w * 0.24f), Size(w * 0.14f, w * 0.22f))
        // silo
        val so = b + Offset(w * 0.5f, -w * 0.05f)
        drawRoundRect(Color(0xFFDCD6EA), Offset(so.x - w * 0.1f, so.y - w * 0.55f), Size(w * 0.2f, w * 0.55f), CornerRadius(w * 0.04f))
        drawRect(Color(0xFFB4ADC8), Offset(so.x, so.y - w * 0.55f), Size(w * 0.1f, w * 0.55f))
        drawArc(Color(0xFFA9D5F2), 180f, 180f, true, Offset(so.x - w * 0.1f, so.y - w * 0.66f), Size(w * 0.2f, w * 0.22f))
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

    /** Soft cloud puffs over an unexplored tile. */
    fun DrawScope.fog(c: Offset, s: Float, seed: Int, puff: Color) {
        val dx = (rnd(seed, 1) - 0.5f) * s * 0.3f
        drawOval(puff, Offset(c.x - s * 0.5f + dx, c.y - s * 0.22f), Size(s * 0.7f, s * 0.3f))
        drawOval(puff, Offset(c.x - s * 0.1f + dx, c.y - s * 0.3f), Size(s * 0.55f, s * 0.32f))
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
