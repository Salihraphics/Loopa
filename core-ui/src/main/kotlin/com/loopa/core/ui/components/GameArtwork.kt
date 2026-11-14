package com.loopa.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import com.loopa.core.ui.theme.LoopaPalette

/**
 * LOOPA game artwork system.
 *
 * Spec: flat geometric shapes with an editorial touch; one pastel background per game (its
 * [gameVisual] colour), at most three supporting palette colours, ink (#353344) for line work, no
 * gradients, no shadows, no text. Shapes are laid out on a unit grid so the art scales from
 * 40 dp thumbnails to full-width banners. A new game adds one entry to [artFor].
 */
@Composable
fun GameArtwork(modeId: String, modifier: Modifier = Modifier, label: String? = null) {
    val visual = gameVisual(modeId)
    Canvas(modifier.then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)) {
        drawRect(visual.color)
        // editorial grain: two soft discs in the background
        drawCircle(Color.White.copy(alpha = 0.22f), radius = size.minDimension * 0.42f, center = Offset(size.width * 0.86f, size.height * 0.12f))
        drawCircle(Color.White.copy(alpha = 0.14f), radius = size.minDimension * 0.26f, center = Offset(size.width * 0.08f, size.height * 0.95f))
        artFor(modeId)(this)
    }
}

private val Ink = LoopaPalette.Ink
private val White = Color.White

/** Square drawing area centred in the canvas (art keeps its proportions in wide banners). */
private inline fun DrawScope.square(block: DrawScope.(s: Float, ox: Float, oy: Float) -> Unit) {
    val s = size.minDimension
    val ox = (size.width - s) / 2f
    val oy = (size.height - s) / 2f
    block(s, ox, oy)
}

private fun artFor(id: String): DrawScope.() -> Unit = when (id) {
    "isim_sehir" -> { -> isimSehir() }
    "gizli_kelime" -> { -> gizliKelime() }
    "kelime_zinciri" -> { -> kelimeZinciri() }
    "bilgi_arenasi" -> { -> bilgiArenasi() }
    "iki_dogru_bir_yalan" -> { -> ikiDogru() }
    "ciz_tahmin" -> { -> cizTahmin() }
    "en_muhtemel_kim" -> { -> enMuhtemel() }
    "anlat_bakalim" -> { -> anlatBakalim() }
    "cizim_telefonu" -> { -> cizimTelefonu() }
    "sessiz_sinema" -> { -> sessizSinema() }
    else -> { -> isimSehir() }
}

private fun DrawScope.paper(x: Float, y: Float, w: Float, h: Float, s: Float, color: Color = White) {
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(s * 0.05f))
    drawRoundRect(Ink, Offset(x, y), Size(w, h), CornerRadius(s * 0.05f), style = Stroke(s * 0.022f))
}

/** A sheet with lines and a big letter tile. */
private fun DrawScope.isimSehir() = square { s, ox, oy ->
    paper(ox + s * 0.18f, oy + s * 0.2f, s * 0.5f, s * 0.62f, s)
    for (i in 0..3) {
        val y = oy + s * (0.36f + i * 0.11f)
        drawLine(Ink.copy(alpha = 0.55f), Offset(ox + s * 0.26f, y), Offset(ox + s * (0.6f - (i % 2) * 0.1f), y), s * 0.022f, StrokeCap.Round)
    }
    drawRoundRect(LoopaPalette.Mint, Offset(ox + s * 0.52f, oy + s * 0.14f), Size(s * 0.3f, s * 0.3f), CornerRadius(s * 0.07f))
    drawRoundRect(Ink, Offset(ox + s * 0.52f, oy + s * 0.14f), Size(s * 0.3f, s * 0.3f), CornerRadius(s * 0.07f), style = Stroke(s * 0.022f))
    // stylised "A"
    val p = Path().apply {
        moveTo(ox + s * 0.59f, oy + s * 0.37f); lineTo(ox + s * 0.67f, oy + s * 0.2f); lineTo(ox + s * 0.75f, oy + s * 0.37f)
        moveTo(ox + s * 0.62f, oy + s * 0.31f); lineTo(ox + s * 0.72f, oy + s * 0.31f)
    }
    drawPath(p, Ink, style = Stroke(s * 0.03f, cap = StrokeCap.Round))
    drawCircle(LoopaPalette.Peach, s * 0.07f, Offset(ox + s * 0.76f, oy + s * 0.74f))
}

/** Speech bubble with a keyhole. */
private fun DrawScope.gizliKelime() = square { s, ox, oy ->
    drawRoundRect(White, Offset(ox + s * 0.16f, oy + s * 0.2f), Size(s * 0.62f, s * 0.46f), CornerRadius(s * 0.14f))
    val tail = Path().apply { moveTo(ox + s * 0.3f, oy + s * 0.64f); lineTo(ox + s * 0.26f, oy + s * 0.82f); lineTo(ox + s * 0.44f, oy + s * 0.65f); close() }
    drawPath(tail, White)
    drawRoundRect(Ink, Offset(ox + s * 0.16f, oy + s * 0.2f), Size(s * 0.62f, s * 0.46f), CornerRadius(s * 0.14f), style = Stroke(s * 0.022f))
    drawCircle(Ink, s * 0.065f, Offset(ox + s * 0.47f, oy + s * 0.39f))
    val key = Path().apply { moveTo(ox + s * 0.44f, oy + s * 0.41f); lineTo(ox + s * 0.42f, oy + s * 0.55f); lineTo(ox + s * 0.52f, oy + s * 0.55f); lineTo(ox + s * 0.5f, oy + s * 0.41f); close() }
    drawPath(key, Ink)
    drawCircle(LoopaPalette.Butter, s * 0.09f, Offset(ox + s * 0.8f, oy + s * 0.76f))
}

/** Three interlocked links. */
private fun DrawScope.kelimeZinciri() = square { s, ox, oy ->
    val colors = listOf(White, LoopaPalette.Butter, White)
    for (i in 0..2) {
        val x = ox + s * (0.12f + i * 0.24f); val y = oy + s * (0.36f + (i % 2) * 0.1f)
        rotate(-18f, Offset(x + s * 0.17f, y + s * 0.1f)) {
            drawRoundRect(colors[i], Offset(x, y), Size(s * 0.34f, s * 0.2f), CornerRadius(s * 0.1f), style = Stroke(s * 0.06f))
            drawRoundRect(Ink, Offset(x, y), Size(s * 0.34f, s * 0.2f), CornerRadius(s * 0.1f), style = Stroke(s * 0.016f))
        }
    }
    drawRoundRect(LoopaPalette.Lavender, Offset(ox + s * 0.62f, oy + s * 0.14f), Size(s * 0.16f, s * 0.16f), CornerRadius(s * 0.04f))
}

/** Podium with a question disc. */
private fun DrawScope.bilgiArenasi() = square { s, ox, oy ->
    val base = oy + s * 0.82f
    listOf(Triple(0.16f, 0.22f, LoopaPalette.Sky), Triple(0.38f, 0.34f, White), Triple(0.6f, 0.16f, LoopaPalette.Peach)).forEach { (x, h, c) ->
        drawRect(c, Offset(ox + s * x, base - s * h), Size(s * 0.22f, s * h))
        drawRect(Ink, Offset(ox + s * x, base - s * h), Size(s * 0.22f, s * h), style = Stroke(s * 0.02f))
    }
    drawCircle(White, s * 0.13f, Offset(ox + s * 0.49f, oy + s * 0.3f))
    drawCircle(Ink, s * 0.13f, Offset(ox + s * 0.49f, oy + s * 0.3f), style = Stroke(s * 0.022f))
    val q = Path().apply {
        moveTo(ox + s * 0.44f, oy + s * 0.27f)
        quadraticTo(ox + s * 0.49f, oy + s * 0.19f, ox + s * 0.54f, oy + s * 0.26f)
        quadraticTo(ox + s * 0.55f, oy + s * 0.3f, ox + s * 0.49f, oy + s * 0.33f)
    }
    drawPath(q, Ink, style = Stroke(s * 0.026f, cap = StrokeCap.Round))
    drawCircle(Ink, s * 0.016f, Offset(ox + s * 0.49f, oy + s * 0.38f))
}

/** Three cards, one marked. */
private fun DrawScope.ikiDogru() = square { s, ox, oy ->
    listOf(-12f to LoopaPalette.Mint, 0f to White, 12f to LoopaPalette.Butter).forEachIndexed { i, (rot, c) ->
        val x = ox + s * (0.16f + i * 0.2f); val y = oy + s * 0.24f
        rotate(rot, Offset(x + s * 0.13f, y + s * 0.25f)) { paper(x, y, s * 0.26f, s * 0.48f, s, c) }
    }
    // X on the middle card
    val cx = ox + s * 0.49f; val cy = oy + s * 0.48f; val d = s * 0.06f
    drawLine(LoopaPalette.Error, Offset(cx - d, cy - d), Offset(cx + d, cy + d), s * 0.035f, StrokeCap.Round)
    drawLine(LoopaPalette.Error, Offset(cx + d, cy - d), Offset(cx - d, cy + d), s * 0.035f, StrokeCap.Round)
}

/** Pencil and a squiggle. */
private fun DrawScope.cizTahmin() = square { s, ox, oy ->
    val squiggle = Path().apply {
        moveTo(ox + s * 0.14f, oy + s * 0.7f)
        cubicTo(ox + s * 0.26f, oy + s * 0.4f, ox + s * 0.36f, oy + s * 0.9f, ox + s * 0.5f, oy + s * 0.6f)
        cubicTo(ox + s * 0.6f, oy + s * 0.4f, ox + s * 0.66f, oy + s * 0.78f, ox + s * 0.8f, oy + s * 0.62f)
    }
    drawPath(squiggle, Ink, style = Stroke(s * 0.035f, cap = StrokeCap.Round))
    rotate(40f, Offset(ox + s * 0.55f, oy + s * 0.33f)) {
        drawRect(LoopaPalette.Butter, Offset(ox + s * 0.33f, oy + s * 0.28f), Size(s * 0.36f, s * 0.11f))
        drawRect(Ink, Offset(ox + s * 0.33f, oy + s * 0.28f), Size(s * 0.36f, s * 0.11f), style = Stroke(s * 0.02f))
        val tip = Path().apply { moveTo(ox + s * 0.69f, oy + s * 0.28f); lineTo(ox + s * 0.79f, oy + s * 0.335f); lineTo(ox + s * 0.69f, oy + s * 0.39f); close() }
        drawPath(tip, White); drawPath(tip, Ink, style = Stroke(s * 0.02f))
        drawRect(LoopaPalette.Peach, Offset(ox + s * 0.27f, oy + s * 0.28f), Size(s * 0.06f, s * 0.11f))
    }
}

/** Three heads, the middle one crowned. */
private fun DrawScope.enMuhtemel() = square { s, ox, oy ->
    listOf(0.24f to LoopaPalette.Mint, 0.5f to White, 0.76f to LoopaPalette.Peach).forEachIndexed { i, (x, c) ->
        val cy = oy + s * (if (i == 1) 0.5f else 0.58f); val r = s * (if (i == 1) 0.13f else 0.1f)
        drawCircle(c, r, Offset(ox + s * x, cy)); drawCircle(Ink, r, Offset(ox + s * x, cy), style = Stroke(s * 0.02f))
        drawRoundRect(c, Offset(ox + s * x - r, cy + r * 1.15f), Size(r * 2, r * 1.3f), CornerRadius(r))
        drawRoundRect(Ink, Offset(ox + s * x - r, cy + r * 1.15f), Size(r * 2, r * 1.3f), CornerRadius(r), style = Stroke(s * 0.02f))
    }
    val crown = Path().apply {
        val x = ox + s * 0.41f; val y = oy + s * 0.33f
        moveTo(x, y); lineTo(x + s * 0.03f, y - s * 0.09f); lineTo(x + s * 0.09f, y - s * 0.03f); lineTo(x + s * 0.15f, y - s * 0.1f)
        lineTo(x + s * 0.18f, y); close()
    }
    drawPath(crown, LoopaPalette.ButterDeep); drawPath(crown, Ink, style = Stroke(s * 0.018f))
}

/** Two bubbles, one with a crossed word. */
private fun DrawScope.anlatBakalim() = square { s, ox, oy ->
    drawRoundRect(White, Offset(ox + s * 0.12f, oy + s * 0.18f), Size(s * 0.48f, s * 0.3f), CornerRadius(s * 0.12f))
    drawRoundRect(Ink, Offset(ox + s * 0.12f, oy + s * 0.18f), Size(s * 0.48f, s * 0.3f), CornerRadius(s * 0.12f), style = Stroke(s * 0.02f))
    drawLine(Ink.copy(alpha = 0.6f), Offset(ox + s * 0.22f, oy + s * 0.33f), Offset(ox + s * 0.5f, oy + s * 0.33f), s * 0.03f, StrokeCap.Round)
    drawLine(LoopaPalette.Error, Offset(ox + s * 0.2f, oy + s * 0.4f), Offset(ox + s * 0.52f, oy + s * 0.26f), s * 0.03f, StrokeCap.Round)
    drawRoundRect(LoopaPalette.Butter, Offset(ox + s * 0.38f, oy + s * 0.52f), Size(s * 0.48f, s * 0.28f), CornerRadius(s * 0.12f))
    drawRoundRect(Ink, Offset(ox + s * 0.38f, oy + s * 0.52f), Size(s * 0.48f, s * 0.28f), CornerRadius(s * 0.12f), style = Stroke(s * 0.02f))
    for (i in 0..2) drawCircle(Ink, s * 0.025f, Offset(ox + s * (0.52f + i * 0.1f), oy + s * 0.66f))
}

/** Two frames passing a squiggle along. */
private fun DrawScope.cizimTelefonu() = square { s, ox, oy ->
    paper(ox + s * 0.1f, oy + s * 0.26f, s * 0.32f, s * 0.42f, s)
    paper(ox + s * 0.58f, oy + s * 0.32f, s * 0.32f, s * 0.42f, s, LoopaPalette.Mint)
    drawCircle(LoopaPalette.Peach, s * 0.07f, Offset(ox + s * 0.26f, oy + s * 0.44f))
    val wave = Path().apply {
        moveTo(ox + s * 0.66f, oy + s * 0.56f); quadraticTo(ox + s * 0.72f, oy + s * 0.44f, ox + s * 0.78f, oy + s * 0.56f)
        quadraticTo(ox + s * 0.82f, oy + s * 0.64f, ox + s * 0.85f, oy + s * 0.52f)
    }
    drawPath(wave, Ink, style = Stroke(s * 0.025f, cap = StrokeCap.Round))
    val arrow = Path().apply {
        moveTo(ox + s * 0.36f, oy + s * 0.2f); quadraticTo(ox + s * 0.5f, oy + s * 0.08f, ox + s * 0.66f, oy + s * 0.24f)
    }
    drawPath(arrow, Ink, style = Stroke(s * 0.022f, cap = StrokeCap.Round))
    drawLine(Ink, Offset(ox + s * 0.66f, oy + s * 0.24f), Offset(ox + s * 0.6f, oy + s * 0.23f), s * 0.022f, StrokeCap.Round)
    drawLine(Ink, Offset(ox + s * 0.66f, oy + s * 0.24f), Offset(ox + s * 0.65f, oy + s * 0.17f), s * 0.022f, StrokeCap.Round)
}

/** Curtains and a mask. */
private fun DrawScope.sessizSinema() = square { s, ox, oy ->
    drawRect(LoopaPalette.PeachDeep.copy(alpha = 0.55f), Offset(ox + s * 0.08f, oy + s * 0.1f), Size(s * 0.84f, s * 0.08f))
    listOf(0.08f, 0.74f).forEach { x ->
        val p = Path().apply {
            moveTo(ox + s * x, oy + s * 0.16f); lineTo(ox + s * (x + 0.18f), oy + s * 0.16f)
            quadraticTo(ox + s * (x + 0.06f), oy + s * 0.5f, ox + s * (x + 0.18f), oy + s * 0.86f); lineTo(ox + s * x, oy + s * 0.86f); close()
        }
        drawPath(p, LoopaPalette.PeachDeep.copy(alpha = 0.75f))
    }
    val c = Offset(ox + s * 0.5f, oy + s * 0.5f)
    drawCircle(White, s * 0.18f, c); drawCircle(Ink, s * 0.18f, c, style = Stroke(s * 0.022f))
    drawCircle(Ink, s * 0.025f, Offset(c.x - s * 0.06f, c.y - s * 0.04f)); drawCircle(Ink, s * 0.025f, Offset(c.x + s * 0.06f, c.y - s * 0.04f))
    drawLine(Ink, Offset(c.x - s * 0.06f, c.y + s * 0.07f), Offset(c.x + s * 0.06f, c.y + s * 0.07f), s * 0.025f, StrokeCap.Round)
    drawLine(Ink, Offset(c.x - s * 0.02f, c.y + s * 0.04f), Offset(c.x + s * 0.02f, c.y + s * 0.1f), s * 0.018f, StrokeCap.Round)
}
