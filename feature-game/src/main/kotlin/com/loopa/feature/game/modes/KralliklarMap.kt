package com.loopa.feature.game.modes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.loopa.core.game.modes.Hex
import com.loopa.core.ui.theme.LocalReducedMotion
import com.loopa.feature.game.modes.KrArt.castle
import com.loopa.feature.game.modes.KrArt.barracks
import com.loopa.feature.game.modes.KrArt.market
import com.loopa.feature.game.modes.KrArt.barn
import com.loopa.feature.game.modes.KrArt.details
import com.loopa.feature.game.modes.KrArt.fog
import com.loopa.feature.game.modes.KrArt.prism
import com.loopa.feature.game.modes.KrArt.swords
import com.loopa.feature.game.modes.KrArt.troops
import kotlin.math.hypot

/** What the map draws for one visible tile (server data, already filtered by fog of war). */
internal data class KrTile(val i: Int, val t: String?, val owner: Int, val troops: Int, val building: String?, val life: Int, val capital: Boolean)

/** A route on the map: a planned order (dashed) or an army already marching (solid). */
internal data class KrRoute(val from: Int, val to: Int, val n: Int, val planned: Boolean, val label: String? = null)

/** Camera of the map: zoom and pan in pixels. Kept outside the canvas so buttons and tests can drive it. */
@Stable
internal class KrCamera {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var focused by mutableStateOf<Int?>(null)
    fun zoomBy(f: Float, around: Offset? = null) {
        val nz = (zoom * f).coerceIn(MIN, MAX)
        val real = nz / zoom
        val c = around ?: Offset(size.width / 2f, size.height / 2f)
        // keep the point under the fingers in place
        val centre = Offset(size.width / 2f, size.height / 2f)
        pan = (pan - (c - centre)) * real + (c - centre)
        zoom = nz
    }
    companion object { const val MIN = 0.8f; const val MAX = 5f }
}

/** Projection of the whole map: per-tile isometric centres in "unit" space and a fitting base scale. */
internal class KrProjection(map: List<List<Int>>) {
    val iso: List<Offset> = map.map { val (x, y) = Hex.center(it[0], it[1], 1f); Offset(x, y * KrArt.ISO) }
    private val minX = iso.minOfOrNull { it.x } ?: 0f
    private val maxX = iso.maxOfOrNull { it.x } ?: 1f
    private val minY = iso.minOfOrNull { it.y } ?: 0f
    private val maxY = iso.maxOfOrNull { it.y } ?: 1f
    val centre = Offset((minX + maxX) / 2, (minY + maxY) / 2)
    /** back to front: painter's order */
    val order: List<Int> = iso.indices.sortedWith(compareBy({ iso[it].y }, { iso[it].x }))
    fun base(size: IntSize): Float = if (size.width == 0) 1f else minOf(size.width * 0.92f / (maxX - minX + 2f), size.height * 0.92f / (maxY - minY + 2f))
    fun screen(i: Int, size: IntSize, cam: KrCamera): Offset {
        val s = base(size) * cam.zoom
        return (iso[i] - centre) * s + Offset(size.width / 2f, size.height / 2f) + cam.pan
    }
    fun focus(i: Int, size: IntSize, cam: KrCamera) {
        cam.pan = -((iso[i] - centre) * (base(size) * cam.zoom))
    }
}

/**
 * The isometric map. Drag to pan, pinch to zoom, tap to select. Tiles outside the screen are not
 * drawn and details are dropped when tiles get small, so 600-tile maps stay light.
 */
@Composable
internal fun KrIsoMap(
    proj: KrProjection,
    cam: KrCamera,
    tiles: Map<Int, KrTile>,
    colorOf: (Int) -> Color,
    me: Int?,
    selected: Int?,
    target: Int?,
    routes: List<KrRoute>,
    battles: Set<Int>,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val pulse = if (reduced) null else rememberInfiniteTransition(label = "map").animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    val dash = if (reduced) null else rememberInfiniteTransition(label = "dash").animateFloat(0f, 40f, infiniteRepeatable(tween(1200)), label = "d")
    Canvas(
        modifier.clipToBounds()
            .onSizeChanged { cam.size = it; val f = cam.focused; if (f != null) { proj.focus(f, it, cam); cam.focused = null } }
            .semantics { contentDescription = "Harita: yakınlık ${"%.1f".format(cam.zoom)}x, kaydırma ${cam.pan.x.toInt()},${cam.pan.y.toInt()}" }
            .testTag("kr_map")
            .pointerInput(proj) {
                detectTransformGestures { centroid, panBy, zoomBy, _ ->
                    if (zoomBy != 1f) cam.zoomBy(zoomBy, centroid)
                    cam.pan += panBy
                }
            }
            .pointerInput(proj, tiles) {
                detectTapGestures { pos -> hitTest(proj, cam, tiles, pos)?.let(onTap) }
            },
    ) {
        val size = IntSize(this.size.width.toInt(), this.size.height.toInt())
        // sea around the island (scenery only: the map data has no water tiles)
        drawRect(Brush.verticalGradient(listOf(KrArt.SeaDeep, KrArt.Sea, KrArt.SeaDeep)))
        val s = proj.base(size) * cam.zoom
        val detail = s >= 9f
        val p = pulse?.value ?: 0.5f
        for (i in proj.order) {
            val c0 = proj.screen(i, size, cam)
            if (c0.x < -2 * s || c0.x > this.size.width + 2 * s || c0.y < -2 * s || c0.y > this.size.height + 3 * s) continue
            val tile = tiles[i]
            if (tile == null) {
                val c = c0 - Offset(0f, 0.12f * s * 0.5f)
                prism(c, s * 0.97f, 0.12f, KrArt.FogTop, KrArt.FogSide)
                if (detail) fog(c, s, i)
                continue
            }
            val tr = KrArt.terrain(tile.t)
            val c = c0 - Offset(0f, tr.height * s * 0.5f)
            val owner = tile.owner
            val tint = if (owner > 0) colorOf(owner).copy(alpha = 0.22f) else null
            prism(c, s * 0.97f, tr.height, tr.top, tr.side, tint)
            if (owner > 0) {
                drawPath(KrArt.topPath(c, s * 0.9f), colorOf(owner), style = Stroke(s * (if (owner == me) 0.12f else 0.09f)))
                if (owner == me) drawPath(KrArt.topPath(c, s * 0.78f), KrArt.Parchment.copy(alpha = 0.5f), style = Stroke(s * 0.03f))
            }
            if (detail) {
                when {
                    tile.capital -> castle(c, s, if (owner > 0) colorOf(owner) else Color.Gray)
                    tile.building == "barracks" -> barracks(c, s, if (owner > 0) colorOf(owner) else Color.Gray)
                    tile.building == "market" -> market(c, s)
                    tile.building == "farm" -> barn(c, s)
                    else -> details(tile.t, c, s, i, depleted = tile.t != "plain" && tile.life <= 0)
                }
            }
            if (tile.troops > 0) {
                if (owner > 0) troops(c, s, colorOf(owner), tile.troops, i == selected, if (i == selected) p else 0f)
                else if (detail) with(KrArt) { garrison(c, s, tile.troops) }
            }
            if (i in battles) swords(c + Offset(-s * 0.45f, -s * 0.35f), s * 0.8f, if (reduced) 1f else 0.6f + 0.4f * p)
            if (i == selected) drawPath(KrArt.topPath(c, s * (1.02f + 0.05f * p)), Color.White, style = Stroke(s * 0.1f))
            if (i == target) drawPath(KrArt.topPath(c, s * 1.02f), Color(0xFFFFB25C), style = Stroke(s * 0.1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.3f, s * 0.2f))))
        }
        // routes on top of the tiles
        routes.forEach { r -> route(proj, size, cam, tiles, r, s, dash?.value ?: 0f) }
    }
}

private fun topCentre(proj: KrProjection, size: IntSize, cam: KrCamera, tiles: Map<Int, KrTile>, i: Int, s: Float): Offset {
    val h = tiles[i]?.let { KrArt.terrain(it.t).height } ?: 0.12f
    return proj.screen(i, size, cam) - Offset(0f, h * s * 0.5f)
}

private fun DrawScope.route(proj: KrProjection, size: IntSize, cam: KrCamera, tiles: Map<Int, KrTile>, r: KrRoute, s: Float, phase: Float) {
    if (r.from !in proj.iso.indices || r.to !in proj.iso.indices) return
    val a = topCentre(proj, size, cam, tiles, r.from, s) - Offset(0f, s * 0.3f)
    val b = topCentre(proj, size, cam, tiles, r.to, s) - Offset(0f, s * 0.3f)
    val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 - hypot(b.x - a.x, b.y - a.y) * 0.3f)
    val path = Path().apply { moveTo(a.x, a.y); quadraticTo(mid.x, mid.y, b.x, b.y) }
    val col = if (r.planned) Color(0xFFFFE08A) else Color.White
    drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(s * 0.16f, cap = StrokeCap.Round))
    drawPath(path, col, style = Stroke(s * 0.09f, cap = StrokeCap.Round,
        pathEffect = if (r.planned) PathEffect.dashPathEffect(floatArrayOf(s * 0.35f, s * 0.22f), phase) else null))
    // arrowhead pointing along the curve's end
    val dir = (b - mid).let { val l = hypot(it.x, it.y).coerceAtLeast(0.001f); Offset(it.x / l, it.y / l) }
    val n = Offset(-dir.y, dir.x)
    val tip = b; val back = tip - dir * (s * 0.42f)
    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(back.x + n.x * s * 0.24f, back.y + n.y * s * 0.24f); lineTo(back.x - n.x * s * 0.24f, back.y - n.y * s * 0.24f); close() }, col)
    // count (and arrival) bubble at the top of the arc
    val top = Offset((a.x + 2 * mid.x + b.x) / 4, (a.y + 2 * mid.y + b.y) / 4)
    val text = r.label?.let { "${r.n} · $it" } ?: "${r.n}"
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; textSize = (s * 0.42f).coerceIn(18f, 34f); isFakeBoldText = true
        color = android.graphics.Color.rgb(38, 34, 46)
    }
    val w = paint.measureText(text) + paint.textSize
    drawRoundRect(col, top - Offset(w / 2, paint.textSize * 0.85f), androidx.compose.ui.geometry.Size(w, paint.textSize * 1.3f),
        androidx.compose.ui.geometry.CornerRadius(paint.textSize * 0.65f))
    drawContext.canvas.nativeCanvas.drawText(text, top.x, top.y + paint.textSize * 0.1f, paint)
}

/** Nearest tile top to the touch, within one tile size. */
internal fun hitTest(proj: KrProjection, cam: KrCamera, tiles: Map<Int, KrTile>, pos: Offset): Int? {
    val size = cam.size
    val s = proj.base(size) * cam.zoom
    var best: Int? = null; var bestD = Float.MAX_VALUE
    for (i in proj.iso.indices) {
        val c = topCentre(proj, size, cam, tiles, i, s)
        val d = hypot(pos.x - c.x, (pos.y - c.y) / KrArt.ISO)
        if (d < bestD) { bestD = d; best = i }
    }
    return best.takeIf { bestD <= s }
}
