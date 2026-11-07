package com.loopa.core.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a layered avatar. Each slot is drawn by its own [AvatarLayer];
 * adding a new cosmetic only requires a new `when` branch in that layer
 * (plus a row in `cosmetic_items`), never touching the others.
 */
@Composable
fun AvatarView(config: AvatarConfig, modifier: Modifier = Modifier, size: Dp = 56.dp, showFrame: Boolean = true) {
    Canvas(modifier.size(size)) { drawAvatar(config, showFrame) }
}

@Composable
fun AvatarView(json: JsonObject?, modifier: Modifier = Modifier, size: Dp = 56.dp, showFrame: Boolean = true) {
    val config = remember(json) { AvatarConfig.fromJson(json) }
    AvatarView(config, modifier, size, showFrame)
}

private val Ink = Color(0xFF353344)
private val White = Color.White

/** Drawing helpers working on a 100x100 unit grid. */
private class G(val scope: DrawScope) {
    val u = scope.size.minDimension / 100f
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    fun s(w: Float, h: Float) = Size(w * u, h * u)
    fun circle(c: Color, x: Float, y: Float, r: Float) = scope.drawCircle(c, r * u, o(x, y))
    fun ring(c: Color, x: Float, y: Float, r: Float, w: Float) = scope.drawCircle(c, r * u, o(x, y), style = Stroke(w * u))
    fun oval(c: Color, x: Float, y: Float, rx: Float, ry: Float) = scope.drawOval(c, o(x - rx, y - ry), s(rx * 2, ry * 2))
    fun rrect(c: Color, l: Float, t: Float, r: Float, b: Float, rad: Float) =
        scope.drawRoundRect(c, o(l, t), s(r - l, b - t), CornerRadius(rad * u))
    fun rrectStroke(c: Color, l: Float, t: Float, r: Float, b: Float, rad: Float, w: Float) =
        scope.drawRoundRect(c, o(l, t), s(r - l, b - t), CornerRadius(rad * u), style = Stroke(w * u))
    fun line(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float) =
        scope.drawLine(c, o(x1, y1), o(x2, y2), w * u, StrokeCap.Round)
    fun arc(c: Color, l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, w: Float) =
        scope.drawArc(c, start, sweep, false, o(l, t), s(r - l, b - t), style = Stroke(w * u, cap = StrokeCap.Round))
    fun fillArc(c: Color, l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) =
        scope.drawArc(c, start, sweep, true, o(l, t), s(r - l, b - t))
    fun path(c: Color, vararg pts: Float, stroke: Float? = null) {
        val p = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
            close()
        }
        if (stroke == null) scope.drawPath(p, c) else scope.drawPath(p, c, style = Stroke(stroke * u))
    }
    fun star(c: Color, x: Float, y: Float, r: Float, points: Int = 5, inner: Float = 0.45f) {
        val p = Path()
        for (i in 0 until points * 2) {
            val rr = if (i % 2 == 0) r else r * inner
            val a = -PI / 2 + i * PI / points
            val px = (x + rr * cos(a).toFloat()) * u
            val py = (y + rr * sin(a).toFloat()) * u
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        scope.drawPath(p, c)
    }
    fun heart(c: Color, x: Float, y: Float, r: Float) {
        circle(c, x - r * 0.5f, y - r * 0.2f, r * 0.55f)
        circle(c, x + r * 0.5f, y - r * 0.2f, r * 0.55f)
        path(c, x - r * 1.02f, y, x + r * 1.02f, y, x, y + r * 1.1f)
    }
}

private fun shade(c: Color, f: Float) = Color((c.red * f).coerceIn(0f, 1f), (c.green * f).coerceIn(0f, 1f), (c.blue * f).coerceIn(0f, 1f), c.alpha)

/** Geometry of the head for the chosen base, shared by all layers. */
private data class Head(val cx: Float, val cy: Float, val halfW: Float, val top: Float, val bottom: Float, val earY: Float)

private fun headFor(base: String?): Head = when (base) {
    "base_oval" -> Head(50f, 47f, 20f, 22f, 72f, 49f)
    "base_square" -> Head(50f, 47f, 21f, 25f, 69f, 48f)
    "base_bean" -> Head(50f, 48f, 23f, 25f, 72f, 50f)
    else -> Head(50f, 47f, 22f, 25f, 69f, 48f)
}

fun DrawScope.drawAvatar(config: AvatarConfig, showFrame: Boolean = true) {
    val g = G(this)
    val clip = Path().apply { addOval(Rect(Offset.Zero, size)) }
    val skin = AvatarCatalog.skinColors[config[AvatarSlot.SKIN]] ?: AvatarCatalog.skinColors.getValue("skin_2")
    val hair = AvatarCatalog.hairColors[config[AvatarSlot.HAIR_COLOR]] ?: AvatarCatalog.hairColors.getValue("hc_brown")
    val head = headFor(config[AvatarSlot.BASE])

    clipPath(clip) {
        BackgroundLayer.draw(g, config[AvatarSlot.BACKGROUND])
        DecorationLayer.drawBehind(g, config[AvatarSlot.DECORATION])
        OutfitLayer.drawBack(g, config[AvatarSlot.OUTFIT])
        HairLayer.drawBack(g, config[AvatarSlot.HAIR], hair, head)
        OutfitLayer.draw(g, config[AvatarSlot.OUTFIT], skin)
        HeadLayer.draw(g, config[AvatarSlot.BASE], skin, head)
        AccessoryLayer.drawFace(g, config[AvatarSlot.ACCESSORY], head)
        EyesLayer.draw(g, config[AvatarSlot.EYES], head)
        MouthLayer.draw(g, config[AvatarSlot.MOUTH], head)
        HairLayer.drawFront(g, config[AvatarSlot.HAIR], hair, head)
        AccessoryLayer.drawOver(g, config[AvatarSlot.ACCESSORY], head)
        GlassesLayer.draw(g, config[AvatarSlot.GLASSES], head)
        HatLayer.draw(g, config[AvatarSlot.HAT], head, hair)
        DecorationLayer.drawFront(g, config[AvatarSlot.DECORATION])
    }
    if (showFrame) FrameLayer.draw(g, config[AvatarSlot.FRAME])
}

private interface AvatarLayer

private object BackgroundLayer : AvatarLayer {
    fun draw(g: G, id: String?) {
        val base = AvatarCatalog.backgroundColors[id] ?: AvatarCatalog.backgroundColors.getValue("bg_lavender")
        g.circle(base, 50f, 50f, 50f)
        when (id) {
            "bg_dots" -> for (x in 0..5) for (y in 0..5) g.circle(White.copy(alpha = 0.55f), 8f + x * 17f + (y % 2) * 8f, 8f + y * 17f, 3f)
            "bg_stripes" -> for (i in -2..8) g.path(White.copy(alpha = 0.35f), i * 16f, 0f, i * 16f + 8f, 0f, i * 16f - 22f, 100f, i * 16f - 30f, 100f)
            "bg_stars" -> listOf(15f to 18f, 80f to 22f, 22f to 75f, 85f to 70f, 60f to 10f, 10f to 45f, 90f to 45f)
                .forEach { (x, y) -> g.star(Color(0xFFFFE39A), x, y, 3.5f) }
            "bg_sunburst" -> g.scope.rotate(0f) {
                for (i in 0 until 12) {
                    val a1 = (i * 30f) * (PI / 180f).toFloat()
                    val a2 = (i * 30f + 15f) * (PI / 180f).toFloat()
                    g.path(Color(0xFFFFE39A).copy(alpha = 0.7f), 50f, 50f, 50f + 80f * cos(a1), 50f + 80f * sin(a1), 50f + 80f * cos(a2), 50f + 80f * sin(a2))
                }
            }
            else -> g.circle(White.copy(alpha = 0.18f), 30f, 25f, 26f)
        }
    }
}

private object HeadLayer : AvatarLayer {
    fun draw(g: G, id: String?, skin: Color, h: Head) {
        // neck
        g.rrect(shade(skin, 0.9f), 44f, h.bottom - 8f, 56f, 80f, 4f)
        // ears
        g.circle(shade(skin, 0.95f), h.cx - h.halfW, h.earY, 4.5f)
        g.circle(shade(skin, 0.95f), h.cx + h.halfW, h.earY, 4.5f)
        when (id) {
            "base_oval" -> g.oval(skin, h.cx, h.cy, h.halfW, (h.bottom - h.top) / 2f)
            "base_square" -> g.rrect(skin, h.cx - h.halfW, h.top, h.cx + h.halfW, h.bottom, 12f)
            "base_bean" -> {
                g.oval(skin, h.cx, h.cy - 1f, h.halfW - 1f, 23f)
                g.oval(skin, h.cx, h.cy + 8f, h.halfW, 16f)
            }
            else -> g.circle(skin, h.cx, h.cy, h.halfW)
        }
        // soft cheek light
        g.oval(White.copy(alpha = 0.12f), h.cx - 8f, h.top + 10f, 9f, 6f)
    }
}

private object EyesLayer : AvatarLayer {
    fun draw(g: G, id: String?, h: Head) {
        val y = h.cy + 1f
        val lx = h.cx - 8f
        val rx = h.cx + 8f
        when (id) {
            "eyes_dot" -> { g.circle(Ink, lx, y, 2.6f); g.circle(Ink, rx, y, 2.6f) }
            "eyes_happy" -> { g.arc(Ink, lx - 4f, y - 2f, lx + 4f, y + 5f, 200f, 140f, 2.2f); g.arc(Ink, rx - 4f, y - 2f, rx + 4f, y + 5f, 200f, 140f, 2.2f) }
            "eyes_wink" -> { roundEye(g, lx, y); g.arc(Ink, rx - 4f, y - 3f, rx + 4f, y + 3f, 20f, 140f, 2.2f) }
            "eyes_sleepy" -> {
                listOf(lx, rx).forEach { x ->
                    g.fillArc(White, x - 4.5f, y - 4f, x + 4.5f, y + 4f, 0f, 180f)
                    g.circle(Ink, x, y + 1.5f, 2f)
                    g.line(Ink, x - 4.5f, y, x + 4.5f, y, 1.8f)
                }
            }
            "eyes_star" -> { g.star(Ink, lx, y, 4f); g.star(Ink, rx, y, 4f); g.circle(Color(0xFFFFE39A), lx, y, 1f); g.circle(Color(0xFFFFE39A), rx, y, 1f) }
            "eyes_heart" -> { g.heart(Color(0xFFE76F8E), lx, y - 0.5f, 3.6f); g.heart(Color(0xFFE76F8E), rx, y - 0.5f, 3.6f) }
            else -> { roundEye(g, lx, y); roundEye(g, rx, y) }
        }
    }

    private fun roundEye(g: G, x: Float, y: Float) {
        g.circle(White, x, y, 4.6f)
        g.circle(Ink, x + 0.6f, y + 0.4f, 2.7f)
        g.circle(White, x + 1.6f, y - 0.8f, 0.9f)
    }
}

private object MouthLayer : AvatarLayer {
    fun draw(g: G, id: String?, h: Head) {
        val y = h.cy + 11f
        val x = h.cx
        when (id) {
            "mouth_grin" -> {
                g.fillArc(Ink, x - 7f, y - 6f, x + 7f, y + 6f, 0f, 180f)
                g.fillArc(White, x - 5.5f, y - 5f, x + 5.5f, y + 1.5f, 0f, 180f)
            }
            "mouth_open" -> { g.oval(Ink, x, y + 1f, 3.6f, 4.2f); g.oval(Color(0xFFE76F8E), x, y + 3f, 2.2f, 1.6f) }
            "mouth_flat" -> g.line(Ink, x - 4.5f, y + 1f, x + 4.5f, y + 1f, 2.2f)
            "mouth_tongue" -> {
                g.arc(Ink, x - 6f, y - 4f, x + 6f, y + 3f, 20f, 140f, 2.2f)
                g.oval(Color(0xFFE76F8E), x + 1f, y + 4f, 2.6f, 3f)
            }
            "mouth_cat" -> {
                g.arc(Ink, x - 6f, y - 2f, x, y + 3f, 10f, 160f, 2f)
                g.arc(Ink, x, y - 2f, x + 6f, y + 3f, 10f, 160f, 2f)
            }
            else -> g.arc(Ink, x - 6f, y - 5f, x + 6f, y + 3f, 20f, 140f, 2.4f)
        }
    }
}

private object HairLayer : AvatarLayer {
    fun drawBack(g: G, id: String?, c: Color, h: Head) {
        val dark = shade(c, 0.85f)
        when (id) {
            "hair_long" -> g.rrect(dark, h.cx - h.halfW - 4f, h.top + 4f, h.cx + h.halfW + 4f, h.bottom + 14f, 14f)
            "hair_bob" -> g.rrect(dark, h.cx - h.halfW - 4f, h.top + 2f, h.cx + h.halfW + 4f, h.cy + 16f, 12f)
            "hair_afro" -> g.circle(dark, h.cx, h.top + 16f, h.halfW + 12f)
            "hair_ponytail" -> {
                g.oval(dark, h.cx + h.halfW + 4f, h.cy + 4f, 6f, 14f)
                g.circle(Color(0xFFE76F8E), h.cx + h.halfW + 2f, h.top + 14f, 3f)
            }
            "hair_curly" -> for (i in 0..6) g.circle(dark, h.cx - h.halfW + i * (h.halfW * 2 / 6f), h.top + 8f, 7f)
        }
    }

    fun drawFront(g: G, id: String?, c: Color, h: Head) {
        val l = h.cx - h.halfW
        val r = h.cx + h.halfW
        when (id) {
            null, "none" -> Unit
            "hair_short" -> {
                g.fillArc(c, l - 1f, h.top - 3f, r + 1f, h.top + 26f, 180f, 180f)
                g.path(c, l + 2f, h.top + 10f, h.cx + 6f, h.top + 8f, h.cx - 2f, h.top + 16f)
            }
            "hair_curly" -> for (i in 0..5) g.circle(c, l + 2f + i * ((h.halfW * 2 - 4) / 5f), h.top + 4f + (i % 2) * 2f, 6.5f)
            "hair_long", "hair_bob" -> {
                g.fillArc(c, l - 2f, h.top - 3f, r + 2f, h.top + 24f, 180f, 180f)
                g.rrect(c, l - 3f, h.top + 8f, l + 4f, h.cy + (if (id == "hair_long") 22f else 14f), 4f)
                g.rrect(c, r - 4f, h.top + 8f, r + 3f, h.cy + (if (id == "hair_long") 22f else 14f), 4f)
                if (id == "hair_bob") g.rrect(c, l, h.top + 4f, r, h.top + 12f, 3f)
            }
            "hair_bun" -> {
                g.circle(c, h.cx, h.top - 5f, 8f)
                g.fillArc(c, l - 1f, h.top - 3f, r + 1f, h.top + 22f, 180f, 180f)
            }
            "hair_spiky" -> {
                for (i in 0..4) {
                    val x = l + 2f + i * ((h.halfW * 2 - 4) / 4f)
                    g.path(c, x - 6f, h.top + 8f, x, h.top - 8f, x + 6f, h.top + 8f)
                }
                g.fillArc(c, l, h.top - 1f, r, h.top + 18f, 180f, 180f)
            }
            "hair_ponytail" -> g.fillArc(c, l - 1f, h.top - 3f, r + 1f, h.top + 24f, 180f, 180f)
            "hair_afro" -> for (i in 0..7) {
                val a = PI + i * PI / 7
                g.circle(c, h.cx + (h.halfW + 4f) * cos(a).toFloat(), h.top + 14f + (h.halfW * 0.7f) * sin(a).toFloat(), 8f)
            }
            "hair_mohawk" -> {
                g.rrect(c, h.cx - 4f, h.top - 12f, h.cx + 4f, h.top + 10f, 4f)
                g.rrect(shade(c, 0.8f), l + 4f, h.top + 2f, l + 8f, h.top + 12f, 2f)
                g.rrect(shade(c, 0.8f), r - 8f, h.top + 2f, r - 4f, h.top + 12f, 2f)
            }
        }
    }
}

private object OutfitLayer : AvatarLayer {
    fun drawBack(g: G, id: String?) {
        if (id == "outfit_cape") g.path(Color(0xFFE15A5A), 22f, 76f, 78f, 76f, 92f, 104f, 8f, 104f)
    }

    fun draw(g: G, id: String?, skin: Color) {
        val (main, accent) = when (id) {
            "outfit_hoodie" -> Color(0xFFA9D5F2) to Color(0xFF6FB3E0)
            "outfit_stripes" -> Color.White to Color(0xFFB9A5E8)
            "outfit_sweater" -> Color(0xFFFFE39A) to Color(0xFFF2C14E)
            "outfit_overall" -> Color(0xFF9DDCC5) to Color(0xFF6FB3E0)
            "outfit_jersey" -> Color(0xFFB9A5E8) to Color.White
            "outfit_suit" -> Color(0xFF3F3A57) to Color.White
            "outfit_cape" -> Color(0xFFFFE39A) to Color(0xFFE15A5A)
            else -> Color(0xFFFFB9A5) to Color(0xFFF08A6C)
        }
        if (id == "outfit_hoodie") g.oval(shade(main, 0.88f), 50f, 77f, 18f, 7f)
        g.rrect(main, 18f, 76f, 82f, 120f, 20f)
        when (id) {
            "outfit_hoodie" -> {
                g.line(accent, 46f, 80f, 45f, 90f, 1.6f); g.line(accent, 54f, 80f, 55f, 90f, 1.6f)
                g.rrect(shade(main, 0.9f), 38f, 92f, 62f, 104f, 4f)
            }
            "outfit_stripes" -> for (i in 0..3) g.rrect(accent, 18f, 82f + i * 7f, 82f, 85f + i * 7f, 1f)
            "outfit_sweater" -> { g.rrect(accent, 40f, 75f, 60f, 81f, 3f); for (i in 0..4) g.circle(accent, 30f + i * 10f, 92f, 1.5f) }
            "outfit_overall" -> {
                g.rrect(accent, 32f, 86f, 68f, 120f, 6f)
                g.line(accent, 34f, 87f, 30f, 76f, 3f); g.line(accent, 66f, 87f, 70f, 76f, 3f)
                g.circle(Color(0xFFFFE39A), 36f, 89f, 1.8f); g.circle(Color(0xFFFFE39A), 64f, 89f, 1.8f)
            }
            "outfit_jersey" -> { g.rrect(accent, 18f, 76f, 26f, 120f, 4f); g.rrect(accent, 74f, 76f, 82f, 120f, 4f); g.star(accent, 50f, 91f, 6f) }
            "outfit_suit" -> {
                g.path(accent, 42f, 76f, 58f, 76f, 50f, 96f)
                g.path(Color(0xFFE15A5A), 48.5f, 79f, 51.5f, 79f, 52.5f, 92f, 50f, 95f, 47.5f, 92f)
            }
            "outfit_cape" -> g.circle(Color(0xFFF2C14E), 50f, 79f, 3f)
            else -> g.fillArc(shade(skin, 0.9f), 42f, 70f, 58f, 82f, 0f, 180f)
        }
    }
}

private object AccessoryLayer : AvatarLayer {
    fun drawFace(g: G, id: String?, h: Head) {
        when (id) {
            "acc_blush" -> { g.oval(Color(0xFFF7A1C4).copy(alpha = 0.7f), h.cx - 13f, h.cy + 7f, 4f, 2.5f); g.oval(Color(0xFFF7A1C4).copy(alpha = 0.7f), h.cx + 13f, h.cy + 7f, 4f, 2.5f) }
            "acc_freckles" -> listOf(-14f, -11f, -12f, 11f, 14f, 12f).forEachIndexed { i, dx ->
                g.circle(Color(0xFFB0704A), h.cx + dx, h.cy + 6f + (i % 3) * 1.6f, 0.9f)
            }
        }
    }

    fun drawOver(g: G, id: String?, h: Head) {
        when (id) {
            "acc_earrings" -> { g.circle(Color(0xFFF2C14E), h.cx - h.halfW, h.earY + 6f, 2.2f); g.circle(Color(0xFFF2C14E), h.cx + h.halfW, h.earY + 6f, 2.2f) }
            "acc_bowtie" -> {
                g.path(Color(0xFFE15A5A), 50f, 79f, 41f, 74f, 41f, 84f)
                g.path(Color(0xFFE15A5A), 50f, 79f, 59f, 74f, 59f, 84f)
                g.circle(shade(Color(0xFFE15A5A), 0.8f), 50f, 79f, 2.2f)
            }
            "acc_scarf" -> { g.rrect(Color(0xFFE15A5A), 34f, 71f, 66f, 79f, 4f); g.rrect(shade(Color(0xFFE15A5A), 0.85f), 56f, 74f, 63f, 94f, 3f) }
            "acc_bandaid" -> {
                g.scope.rotate(-25f, g.o(h.cx + 12f, h.cy + 7f)) { g.rrect(Color(0xFFF2D0A9), h.cx + 7f, h.cy + 5f, h.cx + 17f, h.cy + 9f, 2f) }
            }
            "acc_necklace" -> {
                g.arc(Color(0xFFF2C14E), 38f, 66f, 62f, 86f, 20f, 140f, 1.4f)
                g.circle(Color(0xFF9DDCC5), 50f, 86f, 2.4f)
            }
        }
    }
}

private object GlassesLayer : AvatarLayer {
    fun draw(g: G, id: String?, h: Head) {
        val y = h.cy + 1f
        val lx = h.cx - 8f
        val rx = h.cx + 8f
        when (id) {
            "glasses_round" -> { g.ring(Ink, lx, y, 6f, 1.6f); g.ring(Ink, rx, y, 6f, 1.6f); g.line(Ink, lx + 6f, y, rx - 6f, y, 1.6f) }
            "glasses_square" -> {
                g.rrectStroke(Ink, lx - 6f, y - 5f, lx + 6f, y + 5f, 2f, 1.8f); g.rrectStroke(Ink, rx - 6f, y - 5f, rx + 6f, y + 5f, 2f, 1.8f)
                g.line(Ink, lx + 6f, y, rx - 6f, y, 1.8f)
            }
            "glasses_sun" -> {
                g.rrect(Ink, lx - 6.5f, y - 4.5f, lx + 6.5f, y + 5f, 4f); g.rrect(Ink, rx - 6.5f, y - 4.5f, rx + 6.5f, y + 5f, 4f)
                g.line(Ink, lx + 6f, y - 1f, rx - 6f, y - 1f, 1.8f)
                g.line(White.copy(alpha = 0.5f), lx - 3f, y - 2f, lx, y - 2f, 1.2f)
            }
            "glasses_star" -> { g.star(Color(0xFFE76F8E), lx, y, 8f, inner = 0.55f); g.star(Color(0xFFE76F8E), rx, y, 8f, inner = 0.55f); g.circle(White.copy(alpha = 0.6f), lx, y, 3.5f); g.circle(White.copy(alpha = 0.6f), rx, y, 3.5f) }
            "glasses_monocle" -> { g.ring(Color(0xFFF2C14E), rx, y, 6f, 1.8f); g.line(Color(0xFFF2C14E), rx + 4f, y + 5f, rx + 8f, y + 22f, 0.9f) }
        }
    }
}

private object HatLayer : AvatarLayer {
    fun draw(g: G, id: String?, h: Head, hair: Color) {
        val l = h.cx - h.halfW
        val r = h.cx + h.halfW
        val t = h.top
        when (id) {
            "hat_cap" -> {
                g.fillArc(Color(0xFFE15A5A), l - 1f, t - 6f, r + 1f, t + 22f, 180f, 180f)
                g.rrect(shade(Color(0xFFE15A5A), 0.82f), h.cx - 2f, t + 5f, r + 14f, t + 10f, 3f)
                g.circle(White, h.cx, t - 5f, 1.8f)
            }
            "hat_beanie" -> {
                g.fillArc(Color(0xFF9DDCC5), l - 1f, t - 8f, r + 1f, t + 22f, 180f, 180f)
                g.rrect(shade(Color(0xFF9DDCC5), 0.85f), l - 2f, t + 3f, r + 2f, t + 11f, 3f)
                g.circle(Color(0xFFFFE39A), h.cx, t - 9f, 4.5f)
            }
            "hat_party" -> {
                g.path(Color(0xFFB9A5E8), h.cx - 11f, t + 4f, h.cx + 11f, t + 4f, h.cx, t - 22f)
                g.line(Color(0xFFFFE39A), h.cx - 7f, t - 4f, h.cx + 7f, t - 4f, 2.2f)
                g.line(Color(0xFF9DDCC5), h.cx - 4f, t - 12f, h.cx + 4f, t - 12f, 2f)
                g.circle(Color(0xFFFFB9A5), h.cx, t - 22f, 3.2f)
            }
            "hat_bucket" -> {
                g.path(Color(0xFFFFE39A), l + 3f, t + 8f, r - 3f, t + 8f, r - 7f, t - 8f, l + 7f, t - 8f)
                g.rrect(shade(Color(0xFFFFE39A), 0.85f), l - 6f, t + 6f, r + 6f, t + 12f, 4f)
            }
            "hat_headphones" -> {
                g.arc(Ink, l - 4f, t - 8f, r + 4f, t + 40f, 180f, 180f, 3.2f)
                g.rrect(Color(0xFFB9A5E8), l - 8f, h.earY - 7f, l + 1f, h.earY + 7f, 4f)
                g.rrect(Color(0xFFB9A5E8), r - 1f, h.earY - 7f, r + 8f, h.earY + 7f, 4f)
            }
            "hat_flower" -> {
                g.arc(Color(0xFF65B995), l, t - 2f, r, t + 22f, 190f, 160f, 2f)
                listOf(-14f, -5f, 5f, 14f).forEachIndexed { i, dx ->
                    val col = listOf(Color(0xFFFFB9A5), Color(0xFFFFE39A), Color(0xFFF7A1C4), Color(0xFFA9D5F2))[i]
                    val y = t + 2f + kotlin.math.abs(dx) * 0.25f
                    for (k in 0 until 5) {
                        val a = k * 2 * PI / 5
                        g.circle(col, h.cx + dx + 2.6f * cos(a).toFloat(), y + 2.6f * sin(a).toFloat(), 2.2f)
                    }
                    g.circle(Color(0xFFF2C14E), h.cx + dx, y, 1.5f)
                }
            }
            "hat_wizard" -> {
                g.path(Color(0xFF5B4A8C), h.cx - 15f, t + 6f, h.cx + 15f, t + 6f, h.cx + 4f, t - 30f)
                g.rrect(shade(Color(0xFF5B4A8C), 0.8f), l - 6f, t + 3f, r + 6f, t + 9f, 3f)
                g.star(Color(0xFFFFE39A), h.cx, t - 6f, 4f)
                g.star(Color(0xFFFFE39A), h.cx + 5f, t - 18f, 2.5f)
            }
            "hat_crown" -> {
                val gold = Color(0xFFF2C14E)
                g.path(gold, l + 2f, t + 6f, r - 2f, t + 6f, r - 2f, t - 10f, h.cx + 9f, t - 2f, h.cx, t - 14f, h.cx - 9f, t - 2f, l + 2f, t - 10f)
                g.circle(Color(0xFFE76F8E), h.cx, t + 1f, 2.2f)
                g.circle(Color(0xFF9DDCC5), h.cx - 11f, t + 2f, 1.6f)
                g.circle(Color(0xFFA9D5F2), h.cx + 11f, t + 2f, 1.6f)
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = hair
    }
}

private object DecorationLayer : AvatarLayer {
    fun drawBehind(g: G, id: String?) {
        if (id == "deco_halo") g.oval(Color(0xFFFFF3B0).copy(alpha = 0.6f), 50f, 50f, 40f, 40f)
    }

    fun drawFront(g: G, id: String?) {
        when (id) {
            "deco_sparkles" -> listOf(Triple(16f, 26f, 5f), Triple(84f, 30f, 4f), Triple(80f, 62f, 3f), Triple(20f, 60f, 3.5f))
                .forEach { (x, y, r) -> g.star(Color.White, x, y, r, points = 4, inner = 0.3f) }
            "deco_hearts" -> { g.heart(Color(0xFFE76F8E), 15f, 30f, 4f); g.heart(Color(0xFFF7A1C4), 85f, 26f, 3.2f); g.heart(Color(0xFFE76F8E), 86f, 58f, 2.6f) }
            "deco_lightning" -> g.path(Color(0xFFFFE39A), 84f, 14f, 74f, 34f, 81f, 34f, 74f, 52f, 90f, 28f, 83f, 28f)
            "deco_halo" -> g.ring(Color(0xFFF2C14E), 50f, 12f, 10f, 2.2f)
        }
    }
}

private object FrameLayer : AvatarLayer {
    fun draw(g: G, id: String?) {
        val w = 5f
        when (id) {
            "frame_mint" -> g.ring(Color(0xFF5FB896), 50f, 50f, 50f - w / 2, w)
            "frame_gold" -> {
                g.ring(Color(0xFFF2C14E), 50f, 50f, 50f - w / 2, w)
                g.ring(Color(0xFFFFE39A), 50f, 50f, 50f - w / 2, w / 3)
            }
            "frame_loopa" -> {
                g.arc(Color(0xFFB9A5E8), w / 2, w / 2, 100f - w / 2, 100f - w / 2, 0f, 180f, w)
                g.arc(Color(0xFF9DDCC5), w / 2, w / 2, 100f - w / 2, 100f - w / 2, 180f, 180f, w)
            }
            "frame_rainbow" -> g.scope.drawCircle(
                Brush.sweepGradient(listOf(Color(0xFFFFB9A5), Color(0xFFFFE39A), Color(0xFF9DDCC5), Color(0xFFA9D5F2), Color(0xFFB9A5E8), Color(0xFFFFB9A5)), g.o(50f, 50f)),
                (50f - w / 2) * g.u, g.o(50f, 50f), style = Stroke(w * g.u),
            )
            else -> g.ring(White.copy(alpha = 0.9f), 50f, 50f, 50f - 1.2f, 2.4f)
        }
    }
}
