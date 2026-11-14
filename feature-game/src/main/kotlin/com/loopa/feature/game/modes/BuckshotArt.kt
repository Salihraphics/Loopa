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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate

/** Original LOOPA drawings for the Buckshot table: the table, the shotgun, shells and the 9 items. */
internal object BsArt {
    val Night = Color(0xFF121216)
    val Steel = Color(0xFF26282E)
    val SteelLight = Color(0xFF3A3D45)
    val Edge = Color(0xFF55595F)
    val Lamp = Color(0xFFFFB25C)
    val Rust = Color(0xFFE0533D)
    val Bone = Color(0xFFEDE6DA)
    val Brass = Color(0xFFC99A3B)
    val BlankBlue = Color(0xFF6F8FA8)
    val Felt = Color(0xFF2F3A33)

    /** Room, lamp light and an oval metal table with a felt top. */
    fun DrawScope.table(glow: Float) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0B0E), Night)))
        val c = Offset(size.width / 2, size.height * 0.5f)
        // lamp cone from above
        drawCircle(Brush.radialGradient(listOf(Lamp.copy(alpha = 0.30f * glow), Color.Transparent), c.copy(y = size.height * 0.42f), size.minDimension * 0.75f),
            size.minDimension * 0.75f, c.copy(y = size.height * 0.42f))
        val w = size.width * 0.86f; val h = size.height * 0.62f
        val tl = Offset(c.x - w / 2, c.y - h / 2)
        // rim, shadow, felt
        drawOval(Color.Black.copy(alpha = 0.55f), tl + Offset(0f, h * 0.05f), Size(w, h))
        drawOval(Brush.verticalGradient(listOf(SteelLight, Steel, Color(0xFF1A1B1F)), tl.y, tl.y + h), tl, Size(w, h))
        val inset = w * 0.055f
        drawOval(Brush.radialGradient(listOf(Color(0xFF3B4A40), Felt, Color(0xFF1E2621)), c, w * 0.48f), tl + Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset))
        drawOval(Edge, tl, Size(w, h), style = Stroke(w * 0.008f))
        // rivets on the rim
        for (k in 0 until 16) {
            val a = Math.toRadians(k * 22.5)
            drawCircle(Edge, w * 0.008f, Offset(c.x + (w / 2 - inset / 2) * kotlin.math.cos(a).toFloat(), c.y + (h / 2 - inset / 2) * kotlin.math.sin(a).toFloat()))
        }
    }

    /** The shotgun, centred at [c], pointing along [angle] degrees (0 = right). */
    fun DrawScope.shotgun(c: Offset, len: Float, angle: Float, flash: Float) {
        rotate(angle, c) {
            val t = len * 0.07f
            // stock
            val stock = Path().apply {
                moveTo(c.x - len * 0.5f, c.y - t * 0.6f); lineTo(c.x - len * 0.18f, c.y - t * 0.9f)
                lineTo(c.x - len * 0.18f, c.y + t * 0.9f); lineTo(c.x - len * 0.52f, c.y + t * 1.6f); close()
            }
            drawPath(stock, Brush.verticalGradient(listOf(Color(0xFF8A5A3C), Color(0xFF5A3826)), c.y - t, c.y + t * 1.6f))
            // receiver and pump
            drawRoundRect(Color(0xFF2B2D33), Offset(c.x - len * 0.2f, c.y - t), Size(len * 0.24f, t * 2f), CornerRadius(t * 0.3f))
            drawRoundRect(Color(0xFF6B4630), Offset(c.x + len * 0.12f, c.y + t * 0.1f), Size(len * 0.16f, t * 1.0f), CornerRadius(t * 0.4f))
            // barrel with a highlight
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFB8BEC6), Color(0xFF5F646C)), c.y - t * 0.8f, c.y + t * 0.1f),
                Offset(c.x + len * 0.03f, c.y - t * 0.8f), Size(len * 0.47f, t * 0.9f), CornerRadius(t * 0.3f))
            if (flash > 0f) {
                val tip = Offset(c.x + len * 0.52f, c.y - t * 0.35f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF1C4).copy(alpha = flash), Lamp.copy(alpha = flash * 0.7f), Color.Transparent), tip, len * 0.22f),
                    len * 0.22f, tip)
            }
        }
    }

    /** A standing shell: neutral when unknown; red (live) / blue (blank) only when the player knows it. */
    fun DrawScope.shell(topLeft: Offset, w: Float, h: Float, band: Color?, spent: Boolean) {
        val body = when { spent -> Color(0xFF2E2E30); band == BlankBlue -> Color(0xFF3E5A70); band == Rust -> Color(0xFF8E2F25); else -> Color(0xFF4A4F57) }
        drawRoundRect(Brush.horizontalGradient(listOf(body, body.copy(alpha = 0.8f), body), topLeft.x, topLeft.x + w), topLeft, Size(w, h * 0.78f), CornerRadius(w * 0.35f))
        drawRect(Brush.horizontalGradient(listOf(Brass, Color(0xFFF2D27A), Brass), topLeft.x, topLeft.x + w), Offset(topLeft.x, topLeft.y + h * 0.74f), Size(w, h * 0.26f))
        if (band != null) drawRect(Bone.copy(alpha = 0.8f), Offset(topLeft.x, topLeft.y + h * 0.3f), Size(w, h * 0.08f))
        if (spent) drawLine(Color.Black.copy(alpha = 0.5f), topLeft, topLeft + Offset(w, h), w * 0.12f)
    }

    /** One item as a physical object filling a square of side [s] at [o]. */
    fun DrawScope.item(id: String, o: Offset, s: Float) = translate(o.x, o.y) {
        val ink = Color(0xFF1B1B1F)
        val st = Stroke(s * 0.035f)
        when (id) {
            "beer" -> { // soda can
                drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF2E7D6B), Color(0xFF7FD6C0), Color(0xFF2E7D6B)), s * 0.3f, s * 0.7f), Offset(s * 0.3f, s * 0.18f), Size(s * 0.4f, s * 0.68f), CornerRadius(s * 0.08f))
                drawRect(Color(0xFFB0B5BB), Offset(s * 0.32f, s * 0.14f), Size(s * 0.36f, s * 0.06f))
                drawRect(Bone, Offset(s * 0.3f, s * 0.45f), Size(s * 0.4f, s * 0.12f))
            }
            "cigarettes" -> { // bandage
                rotate(-30f, Offset(s / 2, s / 2)) {
                    drawRoundRect(Color(0xFFE8B98F), Offset(s * 0.12f, s * 0.38f), Size(s * 0.76f, s * 0.24f), CornerRadius(s * 0.12f))
                    drawRect(Color(0xFFF6DCC2), Offset(s * 0.38f, s * 0.38f), Size(s * 0.24f, s * 0.24f))
                    for (k in 0..2) drawCircle(Color(0xFFD29C6E), s * 0.018f, Offset(s * (0.44f + k * 0.06f), s * 0.5f))
                }
            }
            "magnifier" -> {
                drawLine(Color(0xFF6B4630), Offset(s * 0.58f, s * 0.58f), Offset(s * 0.85f, s * 0.85f), s * 0.1f, StrokeCap.Round)
                drawCircle(Color(0xFFBFD9E8).copy(alpha = 0.45f), s * 0.24f, Offset(s * 0.42f, s * 0.42f))
                drawCircle(Color(0xFFB8BEC6), s * 0.24f, Offset(s * 0.42f, s * 0.42f), style = Stroke(s * 0.06f))
            }
            "handcuffs" -> {
                drawCircle(Color(0xFFB8BEC6), s * 0.17f, Offset(s * 0.32f, s * 0.55f), style = Stroke(s * 0.07f))
                drawCircle(Color(0xFFB8BEC6), s * 0.17f, Offset(s * 0.68f, s * 0.55f), style = Stroke(s * 0.07f))
                drawLine(Color(0xFF8A9098), Offset(s * 0.45f, s * 0.42f), Offset(s * 0.55f, s * 0.42f), s * 0.05f)
            }
            "saw" -> {
                val blade = Path().apply {
                    moveTo(s * 0.12f, s * 0.42f); lineTo(s * 0.7f, s * 0.42f); lineTo(s * 0.7f, s * 0.62f)
                    for (k in 0..6) { lineTo(s * (0.66f - k * 0.08f), s * 0.7f); lineTo(s * (0.62f - k * 0.08f), s * 0.62f) }
                    close()
                }
                drawPath(blade, Brush.verticalGradient(listOf(Color(0xFFD5DADF), Color(0xFF8A9098)), s * 0.42f, s * 0.7f))
                drawRoundRect(Rust, Offset(s * 0.66f, s * 0.36f), Size(s * 0.24f, s * 0.3f), CornerRadius(s * 0.06f))
            }
            "inverter" -> {
                drawRoundRect(Color(0xFF3C4048), Offset(s * 0.22f, s * 0.22f), Size(s * 0.56f, s * 0.56f), CornerRadius(s * 0.1f))
                drawArc(Lamp, 200f, 140f, false, Offset(s * 0.32f, s * 0.32f), Size(s * 0.36f, s * 0.36f), style = Stroke(s * 0.05f))
                drawArc(BlankBlue, 20f, 140f, false, Offset(s * 0.32f, s * 0.32f), Size(s * 0.36f, s * 0.36f), style = Stroke(s * 0.05f))
            }
            "phone" -> { // radio
                drawRoundRect(Color(0xFF2B2D33), Offset(s * 0.3f, s * 0.24f), Size(s * 0.4f, s * 0.62f), CornerRadius(s * 0.07f))
                drawLine(Color(0xFF8A9098), Offset(s * 0.62f, s * 0.24f), Offset(s * 0.62f, s * 0.08f), s * 0.04f, StrokeCap.Round)
                drawRoundRect(Color(0xFF8BC34A).copy(alpha = 0.7f), Offset(s * 0.36f, s * 0.32f), Size(s * 0.28f, s * 0.14f), CornerRadius(s * 0.02f))
                for (k in 0..2) drawCircle(Color(0xFF6B6F77), s * 0.025f, Offset(s * (0.4f + k * 0.1f), s * 0.62f))
            }
            "medicine" -> { // pill bottle
                drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFB86B1F), Color(0xFFE9A15A), Color(0xFFB86B1F)), s * 0.32f, s * 0.68f), Offset(s * 0.32f, s * 0.3f), Size(s * 0.36f, s * 0.56f), CornerRadius(s * 0.06f))
                drawRoundRect(Bone, Offset(s * 0.3f, s * 0.2f), Size(s * 0.4f, s * 0.12f), CornerRadius(s * 0.03f))
                drawRect(Bone.copy(alpha = 0.85f), Offset(s * 0.32f, s * 0.48f), Size(s * 0.36f, s * 0.16f))
            }
            "remote" -> {
                drawRoundRect(Color(0xFF2B2D33), Offset(s * 0.36f, s * 0.14f), Size(s * 0.28f, s * 0.72f), CornerRadius(s * 0.08f))
                drawCircle(Rust, s * 0.05f, Offset(s * 0.5f, s * 0.28f))
                for (k in 0..2) drawCircle(Color(0xFF8A9098), s * 0.03f, Offset(s * 0.5f, s * (0.45f + k * 0.1f)))
            }
            else -> drawRoundRect(Color(0xFF3C4048), Offset(s * 0.25f, s * 0.25f), Size(s * 0.5f, s * 0.5f), CornerRadius(s * 0.06f))
        }
        drawRoundRect(ink.copy(alpha = 0f), Offset.Zero, Size(s, s), style = st)
    }

    /** A covered crate standing for a rival's hidden item. */
    fun DrawScope.crate(o: Offset, s: Float) {
        drawRoundRect(Color(0xFF5A4636), o, Size(s, s * 0.8f), CornerRadius(s * 0.1f))
        drawLine(Color(0xFF3B2E24), o + Offset(0f, s * 0.4f), o + Offset(s, s * 0.4f), s * 0.08f)
    }
}
