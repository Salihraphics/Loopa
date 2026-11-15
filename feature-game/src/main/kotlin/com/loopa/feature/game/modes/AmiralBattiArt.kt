package com.loopa.feature.game.modes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The five ship classes. Drawn as original top-down silhouettes (no bitmaps). */
internal enum class ShipClass(val label: String, val len: Int) {
    CARRIER("Uçak gemisi", 5), BATTLESHIP("Zırhlı", 4), CRUISER("Kruvazör", 3), SUBMARINE("Denizaltı", 3), DESTROYER("Muhrip", 2);

    companion object {
        /** Class of the [index]-th ship of a fleet list (3-cell ships alternate cruiser / submarine). */
        fun of(lengths: List<Int>, index: Int): ShipClass {
            val len = lengths.getOrNull(index) ?: return DESTROYER
            return byLen(len, lengths.take(index).count { it == len })
        }
        fun byLen(len: Int, nth: Int): ShipClass = when (len) {
            5 -> CARRIER; 4 -> BATTLESHIP; 3 -> if (nth % 2 == 0) CRUISER else SUBMARINE; else -> DESTROYER
        }
    }
}

/** A ship on a board: origin cell, length, direction and class. */
internal data class NavalShip(val x: Int, val y: Int, val len: Int, val h: Boolean, val cls: ShipClass) {
    fun cells(): List<Pair<Int, Int>> = (0 until len).map { if (h) (x + it) to y else x to (y + it) }
}

/** Naval tactical command palette: deep navy, steel, cyan accents, orange hits, pale misses. */
internal object Naval {
    val Bg = Color(0xFF050D18)
    val Surface = Color(0xFF0A1A2B)
    val Card = Color(0xFF10233A)
    val CardHigh = Color(0xFF173049)
    val Rim = Color(0xFF22405C)
    val Cyan = Color(0xFF3FD0E0)
    val Ice = Color(0xFF8FD3FF)
    val Text = Color(0xFFE8F1F8)
    val Soft = Color(0xFF8FA6BA)
    val Hit = Color(0xFFFF6B3D)
    val HitDeep = Color(0xFFE8402A)
    val Miss = Color(0xFFC9D6E2)
    val Amber = Color(0xFFFFB547)
    val Good = Color(0xFF4FD8A0)
    val TeamA = Cyan
    val TeamB = Amber
    private val SeaTop = Color(0xFF0B2A44)
    private val SeaBottom = Color(0xFF061A2E)
    private val Hull = Color(0xFF6E7F90)
    private val Deck = Color(0xFF8C9CAC)
    private val Detail = Color(0xFF3F4F63)
    private val Outline = Color(0xFF07131F)

    fun colLabel(x: Int): String = ('A' + x).toString()
    fun cellName(x: Int, y: Int): String = colLabel(x) + (y + 1)

    // ------------------------------------------------------------------ board
    /** Ocean with a slow swell, coordinate grid and corner brackets. [t] in 0..1 animates the swell. */
    fun DrawScope.ocean(b: Rect, n: Int, t: Float, radar: Boolean) {
        drawRect(Brush.verticalGradient(listOf(SeaTop, SeaBottom), b.top, b.bottom), b.topLeft, b.size)
        val cell = b.width / n
        // swell: soft sine lines drifting across the chart
        for (k in 0 until n * 2) {
            val y0 = b.top + (k + 0.5f) * cell / 2
            val path = Path()
            var x = b.left
            path.moveTo(x, y0)
            while (x <= b.right) {
                val y = y0 + sin(((x - b.left) / cell * 1.3f + k * 0.9f + t * 2 * PI.toFloat())) * cell * 0.06f
                path.lineTo(x, y); x += cell / 4
            }
            drawPath(path, Ice.copy(alpha = 0.035f), style = Stroke(cell * 0.03f))
        }
        if (radar) {
            // a faint radar sweep from the chart centre (only while targeting)
            val c = b.center; val r = b.width * 0.72f; val a = t * 360f
            rotate(a, c) {
                drawArc(Brush.sweepGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.10f), Color.Transparent), c), 0f, 40f, true,
                    Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
            }
        }
        for (i in 0..n) {
            val major = i % 5 == 0 || i == n
            val a = if (major) 0.32f else 0.16f
            drawLine(Cyan.copy(alpha = a), Offset(b.left + i * cell, b.top), Offset(b.left + i * cell, b.bottom), if (major) 1.4f else 1f)
            drawLine(Cyan.copy(alpha = a), Offset(b.left, b.top + i * cell), Offset(b.right, b.top + i * cell), if (major) 1.4f else 1f)
        }
        val k = cell * 0.9f
        listOf(b.topLeft to Offset(1f, 1f), Offset(b.right, b.top) to Offset(-1f, 1f), Offset(b.left, b.bottom) to Offset(1f, -1f), b.bottomRight to Offset(-1f, -1f))
            .forEach { (o, d) ->
                drawLine(Cyan, o, o + Offset(k * d.x, 0f), 3f, StrokeCap.Square)
                drawLine(Cyan, o, o + Offset(0f, k * d.y), 3f, StrokeCap.Square)
            }
    }

    /** Column letters above and row numbers left of the chart. */
    fun DrawScope.coordinates(b: Rect, n: Int) {
        val cell = b.width / n
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = (cell * 0.38f).coerceIn(14f, 30f)
            color = android.graphics.Color.argb(200, 143, 166, 186); isFakeBoldText = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        for (i in 0 until n) {
            drawContext.canvas.nativeCanvas.drawText(colLabel(i), b.left + (i + 0.5f) * cell, b.top - cell * 0.18f, paint)
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", b.left - cell * 0.42f, b.top + (i + 0.5f) * cell + paint.textSize * 0.36f, paint)
        }
    }

    fun cellRect(b: Rect, n: Int, x: Int, y: Int): Rect {
        val cell = b.width / n
        return Rect(b.left + x * cell, b.top + y * cell, b.left + (x + 1) * cell, b.top + (y + 1) * cell)
    }

    // ------------------------------------------------------------------ ships (top-down silhouettes)
    /**
     * Draws [s] on the chart. [tint] colours the hull (enemy wrecks, invalid placement),
     * [alpha] fades it (sinking), [sinkDepth] 0..1 narrows it as it goes under.
     */
    fun DrawScope.ship(b: Rect, n: Int, s: NavalShip, tint: Color? = null, alpha: Float = 1f, sinkDepth: Float = 0f) {
        val cell = b.width / n
        val left = b.left + s.x * cell; val top = b.top + s.y * cell
        val L = s.len * cell; val W = cell * (1f - 0.35f * sinkDepth)
        withTransform({
            if (s.h) translate(left, top + (cell - W) / 2) else { translate(left + cell - (cell - W) / 2, top); rotate(90f, Offset.Zero) }
        }) { hull(s.cls, L, W, tint, alpha) }
    }

    private fun DrawScope.hull(cls: ShipClass, L: Float, W: Float, tint: Color?, alpha: Float) {
        val m = W * 0.1f
        val hullC = (tint?.let { lerp(Hull, it, 0.55f) } ?: Hull).copy(alpha = alpha)
        val deckC = (tint?.let { lerp(Deck, it, 0.45f) } ?: Deck).copy(alpha = alpha)
        val detail = Detail.copy(alpha = alpha); val out = Outline.copy(alpha = 0.7f * alpha)
        val inset = when (cls) { ShipClass.CARRIER -> 0.08f; ShipClass.BATTLESHIP -> 0.17f; ShipClass.CRUISER -> 0.22f; ShipClass.SUBMARINE -> 0.27f; ShipClass.DESTROYER -> 0.25f }
        val y0 = W * inset; val y1 = W - W * inset; val mid = W / 2
        val bow = if (cls == ShipClass.CARRIER) W * 0.5f else (y1 - y0) * 1.6f
        val p = Path().apply {
            moveTo(m + (y1 - y0) * 0.4f, y0)
            lineTo(L - m - bow, y0)
            quadraticTo(L - m - bow * 0.2f, y0, L - m, mid)
            quadraticTo(L - m - bow * 0.2f, y1, L - m - bow, y1)
            lineTo(m + (y1 - y0) * 0.4f, y1)
            quadraticTo(m, y1, m, mid)
            quadraticTo(m, y0, m + (y1 - y0) * 0.4f, y0)
            close()
        }
        // wake behind the stern and a soft shadow
        drawOval(Color.White.copy(alpha = 0.06f * alpha), Offset(-W * 0.1f, y0 - W * 0.04f), Size(W * 0.45f, y1 - y0 + W * 0.08f))
        drawPath(p, Brush.verticalGradient(listOf(lerp(hullC, Color.White, 0.18f), hullC, lerp(hullC, Outline, 0.25f)), y0, y1))
        drawPath(p, out, style = Stroke(W * 0.035f))
        when (cls) {
            ShipClass.CARRIER -> {
                // angled flight deck, runway, island, parked aircraft
                val deck = Path().apply { moveTo(m * 2, y0 + W * 0.04f); lineTo(L - bow, y0 + W * 0.04f); lineTo(L - m * 3, mid); lineTo(L - bow, y1 - W * 0.04f); lineTo(m * 2, y1 - W * 0.04f); close() }
                drawPath(deck, lerp(deckC, Detail, 0.45f))
                drawLine(Color.White.copy(alpha = 0.55f * alpha), Offset(m * 3, mid), Offset(L - bow, mid - W * 0.12f), W * 0.03f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(W * 0.18f, W * 0.12f)))
                drawLine(Color.White.copy(alpha = 0.35f * alpha), Offset(L * 0.2f, y1 - W * 0.14f), Offset(L * 0.62f, y0 + W * 0.16f), W * 0.025f)
                drawRoundRect(detail, Offset(L * 0.62f, y0 + W * 0.04f), Size(L * 0.1f, W * 0.18f), CornerRadius(W * 0.03f))
                drawCircle(Ice.copy(alpha = 0.7f * alpha), W * 0.035f, Offset(L * 0.67f, y0 + W * 0.13f))
                for (k in 0..2) jet(Offset(L * (0.18f + k * 0.12f), y1 - W * 0.22f), W * 0.14f, deckC)
            }
            ShipClass.BATTLESHIP -> {
                turret(Offset(L * 0.2f, mid), W * 0.14f, -1, deckC, detail)
                turret(Offset(L * 0.68f, mid), W * 0.14f, 1, deckC, detail)
                turret(Offset(L * 0.8f, mid), W * 0.13f, 1, deckC, detail)
                drawRoundRect(deckC, Offset(L * 0.34f, mid - W * 0.16f), Size(L * 0.26f, W * 0.32f), CornerRadius(W * 0.06f))
                drawRoundRect(detail, Offset(L * 0.42f, mid - W * 0.08f), Size(L * 0.1f, W * 0.16f), CornerRadius(W * 0.04f))
            }
            ShipClass.CRUISER -> {
                turret(Offset(L * 0.24f, mid), W * 0.12f, -1, deckC, detail)
                turret(Offset(L * 0.74f, mid), W * 0.12f, 1, deckC, detail)
                drawRoundRect(deckC, Offset(L * 0.38f, mid - W * 0.13f), Size(L * 0.24f, W * 0.26f), CornerRadius(W * 0.05f))
                drawCircle(detail, W * 0.05f, Offset(L * 0.5f, mid))
            }
            ShipClass.SUBMARINE -> {
                // dark cigar hull, sail and diving planes
                drawPath(p, Outline.copy(alpha = 0.35f * alpha))
                drawRoundRect(lerp(hullC, Outline, 0.4f), Offset(L * 0.5f, mid - W * 0.08f), Size(L * 0.16f, W * 0.16f), CornerRadius(W * 0.08f))
                drawLine(detail, Offset(L * 0.56f, mid - W * 0.18f), Offset(L * 0.56f, mid + W * 0.18f), W * 0.04f, StrokeCap.Round)
                drawLine(detail, Offset(L * 0.1f, mid - W * 0.14f), Offset(L * 0.1f, mid + W * 0.14f), W * 0.04f, StrokeCap.Round)
                drawLine(Ice.copy(alpha = 0.3f * alpha), Offset(L * 0.2f, mid), Offset(L * 0.45f, mid), W * 0.02f)
            }
            ShipClass.DESTROYER -> {
                turret(Offset(L * 0.74f, mid), W * 0.12f, 1, deckC, detail)
                drawRoundRect(deckC, Offset(L * 0.32f, mid - W * 0.12f), Size(L * 0.28f, W * 0.24f), CornerRadius(W * 0.05f))
                drawOval(detail, Offset(L * 0.22f, mid - W * 0.06f), Size(L * 0.1f, W * 0.12f))
            }
        }
    }

    private fun DrawScope.turret(c: Offset, r: Float, dir: Int, body: Color, detail: Color) {
        drawLine(detail, c, c + Offset(dir * r * 2.1f, -r * 0.25f), r * 0.28f, StrokeCap.Round)
        drawLine(detail, c, c + Offset(dir * r * 2.1f, r * 0.25f), r * 0.28f, StrokeCap.Round)
        drawCircle(lerp(body, Color.White, 0.1f), r, c)
        drawCircle(detail, r, c, style = Stroke(r * 0.18f))
    }

    private fun DrawScope.jet(c: Offset, s: Float, col: Color) {
        val p = Path().apply { moveTo(c.x + s, c.y); lineTo(c.x - s * 0.6f, c.y - s * 0.7f); lineTo(c.x - s * 0.3f, c.y); lineTo(c.x - s * 0.6f, c.y + s * 0.7f); close() }
        drawPath(p, lerp(col, Color.White, 0.25f))
    }

    // ------------------------------------------------------------------ permanent markers
    fun DrawScope.missMark(r: Rect) {
        val c = r.center; val k = r.width * 0.2f
        drawCircle(Miss.copy(alpha = 0.18f), r.width * 0.3f, c, style = Stroke(r.width * 0.03f))
        drawLine(Miss.copy(alpha = 0.85f), c + Offset(-k, -k), c + Offset(k, k), r.width * 0.06f, StrokeCap.Round)
        drawLine(Miss.copy(alpha = 0.85f), c + Offset(k, -k), c + Offset(-k, k), r.width * 0.06f, StrokeCap.Round)
    }

    /** Damage: scorch ring, burning core and a small flame. [flicker] 0..1 animates the flame. */
    fun DrawScope.hitMark(r: Rect, flicker: Float) {
        val c = r.center; val w = r.width
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1A0E0A).copy(alpha = 0.85f), Color.Transparent), c, w * 0.48f), w * 0.48f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD27A), Hit, HitDeep.copy(alpha = 0.0f)), c, w * 0.3f), w * 0.3f, c)
        val fh = w * (0.32f + 0.08f * flicker)
        val flame = Path().apply { moveTo(c.x - w * 0.1f, c.y); quadraticTo(c.x - w * 0.08f, c.y - fh * 0.6f, c.x + w * 0.02f, c.y - fh); quadraticTo(c.x + w * 0.04f, c.y - fh * 0.4f, c.x + w * 0.1f, c.y); close() }
        drawPath(flame, Color(0xFFFFB547).copy(alpha = 0.9f))
        drawCircle(Color(0xFF3A3F47).copy(alpha = 0.35f), w * 0.14f, c + Offset(w * 0.12f, -fh * 0.9f))
    }

    /** Targeting reticle: rotating brackets and crosshair. [t] 0..1 animates. */
    fun DrawScope.reticle(r: Rect, t: Float, color: Color = Cyan) {
        val c = r.center; val half = r.width * (0.62f - 0.08f * sin(t * 2 * PI.toFloat()))
        rotate(t * 90f, c) {
            for (q in 0 until 4) rotate(q * 90f, c) {
                drawLine(color, Offset(c.x - half, c.y - half), Offset(c.x - half + half * 0.5f, c.y - half), 3f, StrokeCap.Round)
                drawLine(color, Offset(c.x - half, c.y - half), Offset(c.x - half, c.y - half + half * 0.5f), 3f, StrokeCap.Round)
            }
        }
        drawLine(color.copy(alpha = 0.7f), Offset(c.x, r.top - r.height * 0.3f), Offset(c.x, r.bottom + r.height * 0.3f), 1.5f)
        drawLine(color.copy(alpha = 0.7f), Offset(r.left - r.width * 0.3f, c.y), Offset(r.right + r.width * 0.3f, c.y), 1.5f)
        drawCircle(color, r.width * 0.07f, c)
    }

    // ------------------------------------------------------------------ effects (f = 0..1 progress)
    fun DrawScope.splash(r: Rect, f: Float) {
        val c = r.center; val w = r.width
        for (k in 0..1) {
            val g = (f - k * 0.18f).coerceIn(0f, 1f)
            drawCircle(Color.White.copy(alpha = 0.7f * (1 - g)), w * (0.15f + 0.6f * g), c, style = Stroke(w * 0.05f * (1 - g) + 1f))
        }
        for (k in 0 until 7) {
            val a = k / 7f * 2 * PI.toFloat()
            val d = w * 0.5f * f; val up = w * 0.5f * sin(f * PI.toFloat())
            drawCircle(Ice.copy(alpha = 1 - f), w * 0.05f, c + Offset(cos(a) * d, sin(a) * d * 0.5f - up))
        }
    }

    fun DrawScope.explosion(r: Rect, f: Float) {
        val c = r.center; val w = r.width
        val flash = (1 - f * 2.2f).coerceAtLeast(0f)
        if (flash > 0) drawCircle(Color(0xFFFFF1C2).copy(alpha = flash), w * (0.4f + 0.5f * f), c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD27A).copy(alpha = 1 - f), Hit.copy(alpha = 0.8f * (1 - f)), Color.Transparent), c, w * (0.3f + 0.9f * f)),
            w * (0.3f + 0.9f * f), c)
        for (k in 0 until 9) {
            val a = k / 9f * 2 * PI.toFloat() + 0.4f
            val d = w * (0.2f + 1.1f * f)
            drawLine(Color(0xFFFFB547).copy(alpha = 1 - f), c + Offset(cos(a) * d * 0.6f, sin(a) * d * 0.6f), c + Offset(cos(a) * d, sin(a) * d), w * 0.05f, StrokeCap.Round)
        }
        drawCircle(Color(0xFF6B7380).copy(alpha = 0.5f * (1 - f)), w * (0.5f + 0.8f * f), c, style = Stroke(w * 0.08f))
        splash(r, (f * 1.2f).coerceAtMost(1f))
    }

    /** The ship slips under: fade, narrow, bubbles and rings over its cells. */
    fun DrawScope.sinking(b: Rect, n: Int, s: NavalShip, f: Float) {
        ship(b, n, s, tint = HitDeep, alpha = (1 - f).coerceAtLeast(0f), sinkDepth = f)
        s.cells().forEachIndexed { i, (x, y) ->
            val r = cellRect(b, n, x, y)
            val g = ((f - i * 0.08f) * 1.2f).coerceIn(0f, 1f)
            drawCircle(Color.White.copy(alpha = 0.5f * (1 - g)), r.width * (0.2f + 0.5f * g), r.center, style = Stroke(2f))
            for (k in 0..2) drawCircle(Ice.copy(alpha = 0.7f * (1 - g)), r.width * 0.05f, r.center + Offset((k - 1) * r.width * 0.2f, -r.height * 0.5f * g - k * 3f))
        }
    }

    fun DrawScope.torpedoRun(from: Offset, to: Offset, f: Float, cell: Float) {
        val head = Offset(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f)
        drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.6f)), from, head), from, head, cell * 0.12f, StrokeCap.Round)
        drawCircle(Ice, cell * 0.1f, head)
        drawCircle(Color.White.copy(alpha = 0.3f), cell * 0.22f, head, style = Stroke(2f))
    }

    fun DrawScope.sonarPing(r: Rect, f: Float) {
        for (k in 0..2) {
            val g = (f - k * 0.2f).coerceIn(0f, 1f)
            drawCircle(Cyan.copy(alpha = 0.6f * (1 - g)), r.width * 0.75f * g, r.center, style = Stroke(3f))
        }
    }

    fun DrawScope.reconSweep(line: Rect, horizontal: Boolean, f: Float) {
        drawRect(Ice.copy(alpha = 0.12f * sin(f * PI.toFloat())), line.topLeft, line.size)
        val c = if (horizontal) Offset(line.left + line.width * f, line.center.y) else Offset(line.center.x, line.top + line.height * f)
        val s = (if (horizontal) line.height else line.width) * 0.45f
        withTransform({ if (!horizontal) rotate(90f, c) }) {
            val p = Path().apply { moveTo(c.x + s, c.y); lineTo(c.x - s * 0.4f, c.y - s); lineTo(c.x - s * 0.15f, c.y); lineTo(c.x - s * 0.4f, c.y + s); close() }
            drawPath(p, Color.White.copy(alpha = 0.9f))
            drawLine(Color.White, c + Offset(-s * 0.7f, 0f), c + Offset(s, 0f), s * 0.18f, StrokeCap.Round)
        }
    }
}
