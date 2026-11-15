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
    /** Vertical squash of the isometric projection. */
    const val ISO = 0.6f

    val Sea = Color(0xFF2B5D7A)
    val SeaDeep = Color(0xFF1C4560)
    val Ink = Color(0xFF26222E)
    val Parchment = Color(0xFFF3E7CF)
    val Gold = Color(0xFFE8B23A)
    val FogTop = Color(0xFF3A3F4E)
    val FogSide = Color(0xFF2A2E3A)

    data class Terrain(val top: Color, val side: Color, val height: Float, val name: String)

    fun terrain(t: String?): Terrain = when (t) {
        "forest" -> Terrain(Color(0xFF6FA35A), Color(0xFF4C6E3D), 0.26f, "Orman")
        "farm" -> Terrain(Color(0xFFD9C46A), Color(0xFF9C8A45), 0.2f, "Tarım arazisi")
        "mine" -> Terrain(Color(0xFF9AA3A8), Color(0xFF66707A), 0.44f, "Maden (dağ)")
        "gold" -> Terrain(Color(0xFFC9A86A), Color(0xFF8C7346), 0.36f, "Altın tepesi")
        else -> Terrain(Color(0xFF9ACB72), Color(0xFF6A8F4F), 0.2f, "Ova")
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

    /** One prism: front side faces then the top face. [c] is the TOP face centre. */
    fun DrawScope.prism(c: Offset, s: Float, h: Float, top: Color, side: Color, tint: Color? = null) {
        val drop = h * s
        // visible side: vertices 0 (-30°) .. 4 (210°) through the bottom
        val side1 = Path().apply {
            val v = (0..4).map { vertex(c, s, it) }
            moveTo(v[0].x, v[0].y)
            v.drop(1).forEach { lineTo(it.x, it.y) }
            v.reversed().forEach { lineTo(it.x, it.y + drop) }
            close()
        }
        drawPath(side1, Brush.verticalGradient(listOf(side, lerp(side, Color.Black, 0.35f)), c.y, c.y + s * ISO + drop))
        // a lighter left half of the side for low-poly shading
        val left = Path().apply {
            val a = vertex(c, s, 2); val b = vertex(c, s, 3); val d = vertex(c, s, 4)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); lineTo(d.x, d.y + drop); lineTo(b.x, b.y + drop); lineTo(a.x, a.y + drop); close()
        }
        drawPath(left, Color.White.copy(alpha = 0.08f))
        val tp = topPath(c, s)
        drawPath(tp, Brush.linearGradient(listOf(lerp(top, Color.White, 0.18f), top, lerp(top, Color.Black, 0.08f)),
            Offset(c.x - s, c.y - s * ISO), Offset(c.x + s, c.y + s * ISO)))
        if (tint != null) drawPath(tp, tint)
        drawPath(tp, Color.Black.copy(alpha = 0.18f), style = Stroke(s * 0.035f))
    }

    // ------------------------------------------------------------------ terrain details (seeded per tile)
    private fun rnd(seed: Int, k: Int): Float = (((seed * 73856093) xor (k * 19349663)) and 0x7fffffff) % 1000 / 1000f

    fun DrawScope.details(t: String?, c: Offset, s: Float, seed: Int, depleted: Boolean) {
        when (t) {
            "forest" -> {
                val spots = listOf(Offset(-0.38f, -0.1f), Offset(0.05f, -0.28f), Offset(0.36f, 0.02f), Offset(-0.08f, 0.2f))
                spots.forEachIndexed { k, o ->
                    val p = Offset(c.x + o.x * s + (rnd(seed, k) - 0.5f) * s * 0.12f, c.y + o.y * s * ISO * 2f)
                    if (depleted) stump(p, s * 0.5f) else tree(p, s * (0.48f + rnd(seed, k + 9) * 0.16f), k)
                }
            }
            "mine" -> {
                peak(Offset(c.x - s * 0.22f, c.y + s * 0.12f), s * 0.62f, Color(0xFFA9B1B6), Color(0xFF6E777F), snow = !depleted)
                peak(Offset(c.x + s * 0.28f, c.y + s * 0.18f), s * 0.46f, Color(0xFFB6BDC2), Color(0xFF7A838B), snow = false)
                if (!depleted) drawOval(Color(0xFF2B2B30), Offset(c.x - s * 0.08f, c.y + s * 0.08f), Size(s * 0.16f, s * 0.12f))
            }
            "gold" -> {
                peak(Offset(c.x, c.y + s * 0.15f), s * 0.6f, Color(0xFFD1B47A), Color(0xFF9B7F4C), snow = false)
                if (!depleted) for (k in 0..2) {
                    val p = Offset(c.x + (k - 1) * s * 0.18f, c.y + s * (0.05f + 0.06f * (k % 2)))
                    nugget(p, s * 0.09f)
                }
            }
            "farm" -> {
                // furrows across the top face, wheat when alive
                for (k in -2..2) {
                    val y = c.y + k * s * 0.13f * ISO * 1.6f
                    val half = s * (0.78f - 0.12f * kotlin.math.abs(k))
                    drawLine(if (depleted) Color(0xFF8C7A55) else if (k % 2 == 0) Color(0xFFE9D27A) else Color(0xFF8DBA5A),
                        Offset(c.x - half, y), Offset(c.x + half, y), s * 0.07f, StrokeCap.Round)
                }
            }
            else -> {
                for (k in 0..2) {
                    val p = Offset(c.x + (rnd(seed, k) - 0.5f) * s * 1.1f, c.y + (rnd(seed, k + 4) - 0.5f) * s * 0.7f * ISO)
                    drawLine(Color(0xFF6E9E4E), p, p + Offset(-s * 0.05f, -s * 0.1f), s * 0.03f, StrokeCap.Round)
                    drawLine(Color(0xFF6E9E4E), p, p + Offset(s * 0.05f, -s * 0.11f), s * 0.03f, StrokeCap.Round)
                }
            }
        }
    }

    /** Low-poly conifer: two-tone triangles (light left, dark right). */
    private fun DrawScope.tree(base: Offset, h: Float, k: Int) {
        drawLine(Color(0xFF6B4A2E), base, base + Offset(0f, -h * 0.25f), h * 0.1f)
        val tip = base + Offset(0f, -h)
        val l = base + Offset(-h * 0.32f, -h * 0.2f); val r = base + Offset(h * 0.32f, -h * 0.2f); val m = base + Offset(0f, -h * 0.2f)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(m.x, m.y); close() }, if (k % 2 == 0) Color(0xFF4F8F4A) else Color(0xFF5C9E52))
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(m.x, m.y); lineTo(r.x, r.y); close() }, Color(0xFF2F6136))
    }

    private fun DrawScope.stump(base: Offset, h: Float) {
        drawRoundRect(Color(0xFF7A5634), base + Offset(-h * 0.1f, -h * 0.18f), Size(h * 0.2f, h * 0.18f), CornerRadius(h * 0.04f))
        drawOval(Color(0xFFC79B6A), base + Offset(-h * 0.1f, -h * 0.22f), Size(h * 0.2f, h * 0.08f))
    }

    /** Low-poly peak: light west face, dark east face, optional snow cap. */
    private fun DrawScope.peak(base: Offset, h: Float, light: Color, dark: Color, snow: Boolean) {
        val tip = base + Offset(h * 0.05f, -h)
        val l = base + Offset(-h * 0.55f, 0f); val r = base + Offset(h * 0.55f, 0f); val m = base + Offset(h * 0.1f, 0f)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(m.x, m.y); close() }, light)
        drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(m.x, m.y); lineTo(r.x, r.y); close() }, dark)
        if (snow) drawPath(Path().apply {
            moveTo(tip.x, tip.y); lineTo(tip.x - h * 0.18f, tip.y + h * 0.3f); lineTo(tip.x + h * 0.02f, tip.y + h * 0.22f); lineTo(tip.x + h * 0.16f, tip.y + h * 0.3f); close()
        }, Color(0xFFF4F6F8))
    }

    private fun DrawScope.nugget(c: Offset, r: Float) {
        drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r * 0.7f); lineTo(c.x - r, c.y); close() }, Gold)
        drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y); close() }, Color(0xFFFFE08A))
    }

    // ------------------------------------------------------------------ buildings
    fun DrawScope.castle(c: Offset, s: Float, flag: Color) {
        val w = s * 0.9f; val base = c + Offset(0f, s * 0.18f)
        // keep
        drawRect(Color(0xFFD9CDB4), base + Offset(-w * 0.3f, -w * 0.62f), Size(w * 0.6f, w * 0.62f))
        drawRect(Color(0xFFB4A68A), base + Offset(w * 0.05f, -w * 0.62f), Size(w * 0.25f, w * 0.62f))
        // towers
        for (dx in listOf(-0.42f, 0.3f)) {
            drawRect(Color(0xFFE6DCC6), base + Offset(w * dx, -w * 0.82f), Size(w * 0.14f, w * 0.82f))
            drawPath(Path().apply {
                val x = base.x + w * dx; val y = base.y - w * 0.82f
                moveTo(x - w * 0.03f, y); lineTo(x + w * 0.07f, y - w * 0.22f); lineTo(x + w * 0.17f, y); close()
            }, lerp(flag, Color.Black, 0.25f))
        }
        // crenellations
        for (k in 0..3) drawRect(Color(0xFFD9CDB4), base + Offset(-w * 0.3f + k * w * 0.17f, -w * 0.7f), Size(w * 0.09f, w * 0.09f))
        // gate
        drawRoundRect(Color(0xFF5A4030), base + Offset(-w * 0.08f, -w * 0.26f), Size(w * 0.16f, w * 0.26f), CornerRadius(w * 0.08f))
        // flag
        val pole = base + Offset(-w * 0.05f, -w * 0.62f)
        drawLine(Ink, pole, pole + Offset(0f, -w * 0.45f), w * 0.03f)
        drawPath(Path().apply { moveTo(pole.x, pole.y - w * 0.45f); lineTo(pole.x + w * 0.32f, pole.y - w * 0.37f); lineTo(pole.x, pole.y - w * 0.28f); close() }, flag)
    }

    fun DrawScope.barracks(c: Offset, s: Float, flag: Color) {
        val w = s * 0.75f; val b = c + Offset(0f, s * 0.15f)
        drawPath(Path().apply { moveTo(b.x - w * 0.5f, b.y); lineTo(b.x, b.y - w * 0.6f); lineTo(b.x + w * 0.5f, b.y); close() }, Color(0xFFB7A57D))
        drawPath(Path().apply { moveTo(b.x, b.y - w * 0.6f); lineTo(b.x + w * 0.5f, b.y); lineTo(b.x + w * 0.1f, b.y); close() }, Color(0xFF8E7D58))
        drawPath(Path().apply { moveTo(b.x - w * 0.1f, b.y); lineTo(b.x, b.y - w * 0.3f); lineTo(b.x + w * 0.1f, b.y); close() }, Color(0xFF4A3B2A))
        drawLine(Ink, b + Offset(0f, -w * 0.6f), b + Offset(0f, -w * 0.95f), w * 0.04f)
        drawRect(flag, b + Offset(0f, -w * 0.95f), Size(w * 0.26f, w * 0.16f))
    }

    fun DrawScope.market(c: Offset, s: Float) {
        val w = s * 0.75f; val b = c + Offset(0f, s * 0.15f)
        drawRect(Color(0xFF8A5E3B), b + Offset(-w * 0.4f, -w * 0.35f), Size(w * 0.8f, w * 0.35f))
        for (k in 0..4) drawPath(Path().apply {
            val x = b.x - w * 0.46f + k * w * 0.184f
            moveTo(x, b.y - w * 0.35f); lineTo(x + w * 0.184f, b.y - w * 0.35f); lineTo(x + w * 0.15f, b.y - w * 0.55f); lineTo(x + w * 0.03f, b.y - w * 0.55f); close()
        }, if (k % 2 == 0) Color(0xFFE05A47) else Color(0xFFF6EFE2))
        drawCircle(Gold, w * 0.07f, b + Offset(-w * 0.15f, -w * 0.12f)); drawCircle(Color(0xFF8DBA5A), w * 0.07f, b + Offset(w * 0.12f, -w * 0.12f))
    }

    fun DrawScope.barn(c: Offset, s: Float) {
        val w = s * 0.7f; val b = c + Offset(0f, s * 0.15f)
        drawRect(Color(0xFFB9473A), b + Offset(-w * 0.35f, -w * 0.4f), Size(w * 0.7f, w * 0.4f))
        drawPath(Path().apply { moveTo(b.x - w * 0.45f, b.y - w * 0.4f); lineTo(b.x, b.y - w * 0.72f); lineTo(b.x + w * 0.45f, b.y - w * 0.4f); close() }, Color(0xFF6E2A23))
        drawRect(Color(0xFFF2E6D6), b + Offset(-w * 0.1f, -w * 0.28f), Size(w * 0.2f, w * 0.28f))
    }

    /** A stylized soldier with a shield in the owner's colour and a count banner. */
    fun DrawScope.troops(c: Offset, s: Float, color: Color, count: Int, selected: Boolean, bob: Float) {
        val h = s * 0.86f
        val base = c + Offset(s * 0.42f, s * 0.05f - bob * s * 0.06f)
        drawOval(Color.Black.copy(alpha = 0.25f), Offset(base.x - h * 0.3f, base.y - h * 0.05f + bob * s * 0.06f), Size(h * 0.6f, h * 0.16f))
        drawRoundRect(lerp(color, Color.Black, 0.3f), base + Offset(-h * 0.13f, -h * 0.62f), Size(h * 0.26f, h * 0.55f), CornerRadius(h * 0.08f))
        drawCircle(Color(0xFFF0D2B0), h * 0.13f, base + Offset(0f, -h * 0.74f))
        drawArc(Color(0xFF8A9098), 180f, 180f, true, base + Offset(-h * 0.15f, -h * 0.9f), Size(h * 0.3f, h * 0.26f))
        drawLine(Color(0xFF6B4A2E), base + Offset(h * 0.2f, -h * 1.05f), base + Offset(h * 0.2f, 0f), h * 0.05f)
        drawPath(Path().apply {
            val x = base.x - h * 0.3f; val y = base.y - h * 0.55f
            moveTo(x, y); lineTo(x + h * 0.26f, y); lineTo(x + h * 0.26f, y + h * 0.22f); lineTo(x + h * 0.13f, y + h * 0.34f); lineTo(x, y + h * 0.22f); close()
        }, color)
        if (count <= 0) return
        // count banner
        val label = if (count > 99) "99+" else count.toString()
        val bw = h * (0.32f + 0.16f * label.length); val by = base.y - h * 1.36f
        drawRoundRect(if (selected) Color.White else Ink.copy(alpha = 0.85f), Offset(base.x - bw / 2, by), Size(bw, h * 0.34f), CornerRadius(h * 0.17f))
        drawRoundRect(color, Offset(base.x - bw / 2, by), Size(bw, h * 0.34f), CornerRadius(h * 0.17f), style = Stroke(h * 0.05f))
        drawContext.canvas.nativeCanvas.drawText(label, base.x, by + h * 0.26f, android.graphics.Paint().apply {
            isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = h * 0.28f; isFakeBoldText = true
            this.color = if (selected) android.graphics.Color.rgb(38, 34, 46) else android.graphics.Color.WHITE
        })
    }

    /** A neutral garrison: a small grey shield with its size (quiet, so kingdoms stand out). */
    fun DrawScope.garrison(c: Offset, s: Float, count: Int) {
        val w = s * 0.34f; val o = c + Offset(s * 0.28f, -s * 0.42f)
        drawPath(Path().apply {
            moveTo(o.x, o.y); lineTo(o.x + w, o.y); lineTo(o.x + w, o.y + w * 0.7f); lineTo(o.x + w / 2, o.y + w * 1.1f); lineTo(o.x, o.y + w * 0.7f); close()
        }, Color(0xFF6C7280))
        drawContext.canvas.nativeCanvas.drawText(count.toString(), o.x + w / 2, o.y + w * 0.66f, android.graphics.Paint().apply {
            isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = w * 0.62f; isFakeBoldText = true; color = android.graphics.Color.WHITE
        })
    }

    /** Soft fog puffs over an unexplored tile. */
    fun DrawScope.fog(c: Offset, s: Float, seed: Int) {
        drawOval(Color(0xFFB9BECB).copy(alpha = 0.07f), Offset(c.x - s * 0.55f + (rnd(seed, 1) - 0.5f) * s * 0.3f, c.y - s * 0.2f), Size(s * 1.1f, s * 0.4f))
    }

    /** Crossed swords marking a battle of the last turn. */
    fun DrawScope.swords(c: Offset, s: Float, alpha: Float) {
        val r = s * 0.4f
        drawCircle(Color(0xFFE05A47).copy(alpha = 0.85f * alpha), r * 0.9f, c)
        drawLine(Color.White.copy(alpha = alpha), c + Offset(-r * 0.55f, -r * 0.55f), c + Offset(r * 0.55f, r * 0.55f), r * 0.16f, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = alpha), c + Offset(r * 0.55f, -r * 0.55f), c + Offset(-r * 0.55f, r * 0.55f), r * 0.16f, StrokeCap.Round)
    }

    // ------------------------------------------------------------------ crests (one emblem per kingdom number)
    fun DrawScope.crest(o: Offset, w: Float, color: Color, k: Int) {
        val h = w * 1.18f
        val shield = Path().apply {
            moveTo(o.x, o.y); lineTo(o.x + w, o.y); lineTo(o.x + w, o.y + h * 0.55f)
            quadraticTo(o.x + w, o.y + h * 0.88f, o.x + w / 2, o.y + h); quadraticTo(o.x, o.y + h * 0.88f, o.x, o.y + h * 0.55f); close()
        }
        drawPath(shield, Brush.verticalGradient(listOf(lerp(color, Color.White, 0.2f), lerp(color, Color.Black, 0.2f)), o.y, o.y + h))
        drawPath(shield, Parchment, style = Stroke(w * 0.07f))
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
                drawLine(lerp(color, Color.Black, 0.2f), c + Offset(0f, -e * 0.8f), c + Offset(0f, e * 0.8f), e * 0.1f)
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
                    drawRoundRect(Color(0xFF9B6B3F), Offset(x, y), Size(s * 0.66f, s * 0.22f), CornerRadius(s * 0.11f))
                    drawCircle(Color(0xFFE2BC8A), s * 0.1f, Offset(x + s * 0.1f, y + s * 0.11f))
                }
            }
            "iron" -> { // ingot
                drawPath(Path().apply {
                    moveTo(o.x + s * 0.12f, o.y + s * 0.7f); lineTo(o.x + s * 0.28f, o.y + s * 0.38f); lineTo(o.x + s * 0.84f, o.y + s * 0.38f); lineTo(o.x + s * 0.92f, o.y + s * 0.7f); close()
                }, Color(0xFF9AA3AB))
                drawRect(Color(0xFFC9D0D6), Offset(o.x + s * 0.3f, o.y + s * 0.4f), Size(s * 0.5f, s * 0.06f))
            }
            "gold" -> {
                drawCircle(Gold, s * 0.32f, Offset(o.x + s * 0.5f, o.y + s * 0.52f))
                drawCircle(Color(0xFFFFE08A), s * 0.2f, Offset(o.x + s * 0.5f, o.y + s * 0.52f), style = Stroke(s * 0.06f))
            }
            "food" -> { // wheat
                drawLine(Color(0xFFB9952F), Offset(o.x + s * 0.5f, o.y + s * 0.9f), Offset(o.x + s * 0.5f, o.y + s * 0.15f), s * 0.07f)
                for (j in 0..3) {
                    val y = o.y + s * (0.22f + j * 0.15f)
                    drawOval(Color(0xFFE9C25A), Offset(o.x + s * 0.28f, y), Size(s * 0.2f, s * 0.12f))
                    drawOval(Color(0xFFE9C25A), Offset(o.x + s * 0.52f, y), Size(s * 0.2f, s * 0.12f))
                }
            }
            "troops" -> troops(Offset(o.x + s * 0.1f, o.y + s * 0.85f), s * 1.05f, Color(0xFF8E74D6), 0, false, 0f)
        }
    }
}
