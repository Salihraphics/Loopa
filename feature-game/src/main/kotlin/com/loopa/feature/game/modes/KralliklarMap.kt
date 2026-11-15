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
import com.loopa.feature.game.modes.KrArt.prism
import com.loopa.feature.game.modes.KrArt.swords
import com.loopa.feature.game.modes.KrArt.troops
import kotlin.math.hypot

/**
 * What the map draws for one tile the server told us about (already filtered by fog of war).
 * [explored] = remembered terrain only: the server sends no owner, troops or building for it.
 * Tiles missing from the map are unexplored.
 */
internal data class KrTile(val i: Int, val t: String?, val owner: Int, val troops: Int, val building: String?, val life: Int, val capital: Boolean,
                           val explored: Boolean = false)

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
    /** nbr[i][k] = the tile across edge k of tile i (edge k joins vertices k and k + 1), or -1 at the coast. */
    val nbr: Array<IntArray> = run {
        val index = map.mapIndexed { i, c -> (c[0] to c[1]) to i }.toMap()
        Array(map.size) { i ->
            val out = IntArray(6) { -1 }
            val (q, r) = map[i][0] to map[i][1]
            val (x0, y0) = Hex.center(q, r, 1f)
            for ((dq, dr) in listOf(1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1)) {
                val j = index[q + dq to r + dr] ?: continue
                val (x1, y1) = Hex.center(q + dq, r + dr, 1f)
                val deg = Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
                val k = ((Math.round(deg / 60.0).toInt() % 6) + 6) % 6
                out[k] = j
            }
            out
        }
    }
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
    dark: Boolean = false,
) {
    val scene = if (dark) KrArt.DarkScenery else KrArt.LightScenery
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
        // the sea around the island (scenery: the map data has no water tiles)
        drawRect(Brush.verticalGradient(listOf(scene.seaTop, scene.seaBottom)))
        val s = proj.base(size) * cam.zoom
        val step = (s * 2.6f).coerceAtLeast(36f)
        val ox = ((cam.pan.x % step) + step) % step; val oy = ((cam.pan.y % step) + step) % step
        var row = 0
        var y = oy - step
        while (y < this.size.height + step) {
            var x = ox - step + if (row % 2 == 0) 0f else step / 2
            while (x < this.size.width + step) {
                drawArc(scene.wave, 200f, 140f, false, Offset(x, y), androidx.compose.ui.geometry.Size(step * 0.32f, step * 0.12f), style = Stroke(step * 0.025f, cap = StrokeCap.Round))
                x += step
            }
            y += step * 0.6f; row++
        }
        val detail = s >= 9f
        val p = pulse?.value ?: 0.5f
        val onScreen = proj.order.filter { i ->
            val c0 = proj.screen(i, size, cam)
            !(c0.x < -2 * s || c0.x > this.size.width + 2 * s || c0.y < -2 * s || c0.y > this.size.height + 3 * s)
        }
        fun height(t: KrTile?) = if (t == null) KrArt.FOG_H else KrArt.terrain(t.t).height
        fun top(i: Int) = proj.screen(i, size, cam) - Offset(0f, height(tiles[i]) * s * 0.5f)
        val live = { t: KrTile? -> t != null && !t.explored }

        // 0) surf along the coast
        if (detail) for (i in onScreen) if (proj.nbr[i].any { it < 0 }) {
            val c = proj.screen(i, size, cam) + Offset(0f, height(tiles[i]) * s * 0.5f)
            drawPath(KrArt.topPath(c, s * 1.12f), scene.foam, style = Stroke(s * 0.08f))
        }
        // 1) ground: terrain prisms (no owner colour on the land) or cloud banks
        for (i in onScreen) {
            val tile = tiles[i]
            val c = top(i)
            if (tile == null) { with(KrArt) { cloudTile(c, s * 0.97f, i, scene, detail) }; continue }
            val tr = KrArt.terrain(tile.t)
            prism(c, s * 0.97f, tr.height, tr.top, tr.side)
        }
        // 2) what stands on the land, back to front; explored tiles get terrain only and a veil
        for (i in onScreen) {
            val tile = tiles[i] ?: continue
            val c = top(i)
            if (tile.explored) {
                if (detail) details(tile.t, c, s, i, depleted = false)
                with(KrArt) { veil(c, s * 0.97f, i, scene, if (detail) KrArt.tallness(tile.t) else 0f) }
                continue
            }
            val owner = tile.owner
            val oc = if (owner > 0) colorOf(owner) else Color(0xFF8C86A0)
            if (detail) {
                when {
                    tile.capital -> castle(c, s, oc, grand = true)
                    tile.building == "barracks" -> barracks(c, s, oc)
                    tile.building == "market" -> market(c, s)
                    tile.building == "farm" -> barn(c, s)
                    else -> details(tile.t, c, s, i, depleted = tile.t != "plain" && tile.life <= 0)
                }
                if (owner > 0 && !tile.capital && tile.building == null && tile.troops <= 0 && s >= 18f) with(KrArt) { territoryFlag(c, s, oc) }
            }
            if (tile.troops > 0) {
                if (owner > 0) troops(c, s, oc, tile.troops, i == selected, if (i == selected) p else 0f)
                else if (detail) with(KrArt) { garrison(c, s, tile.troops) }
            }
            if (i in battles) swords(c + Offset(-s * 0.45f, -s * 0.35f), s * 0.8f, if (reduced) 1f else 0.6f + 0.4f * p)
        }
        // 3) borders: an edge is drawn where the owner changes (neighbour of another kingdom, neutral, unknown or coast)
        for (i in onScreen) {
            val tile = tiles[i]
            if (!live(tile) || tile!!.owner <= 0) continue
            val c = top(i); val oc = colorOf(tile.owner); val mine = tile.owner == me
            for (k in 0 until 6) {
                val j = proj.nbr[i][k]
                val other = if (j >= 0) tiles[j] else null
                if (live(other) && other!!.owner == tile.owner) continue
                val a = KrArt.vertex(c, s * 0.95f, k); val b = KrArt.vertex(c, s * 0.95f, (k + 1) % 6)
                drawLine(oc.copy(alpha = 0.28f), a, b, s * (if (mine) 0.3f else 0.24f), StrokeCap.Round)        // glow
                drawLine(Color.White.copy(alpha = 0.9f), a, b, s * (if (mine) 0.15f else 0.12f), StrokeCap.Round)
                drawLine(oc, a, b, s * (if (mine) 0.09f else 0.07f), StrokeCap.Round)
            }
        }
        // 4) markers: capitals (crown, visible at any zoom), selection, move target
        for (i in onScreen) {
            val tile = tiles[i]
            if (!live(tile) || !tile!!.capital || tile.owner <= 0) continue
            val c = top(i)
            with(KrArt) { crownBadge(c - Offset(0f, s * (if (detail) 2.25f else 0.4f)), maxOf(s * 0.22f, 7f), colorOf(tile.owner)) }
        }
        selected?.takeIf { it in proj.iso.indices }?.let { i ->
            val sp = KrArt.topPath(top(i), s * (1.02f + 0.05f * p))
            drawPath(sp, Color.White, style = Stroke(s * 0.15f))
            drawPath(sp, Color(0xFF8E74D6), style = Stroke(s * 0.08f))
        }
        target?.takeIf { it in proj.iso.indices }?.let { i ->
            val tp = KrArt.topPath(top(i), s * 1.02f)
            drawPath(tp, Color.White, style = Stroke(s * 0.14f))
            drawPath(tp, Color(0xFFF08A6C), style = Stroke(s * 0.09f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.3f, s * 0.2f))))
        }
        // routes on top of the tiles
        routes.forEach { r -> route(proj, size, cam, tiles, r, s, dash?.value ?: 0f) }
    }
}

private fun topCentre(proj: KrProjection, size: IntSize, cam: KrCamera, tiles: Map<Int, KrTile>, i: Int, s: Float): Offset {
    val h = tiles[i]?.let { KrArt.terrain(it.t).height } ?: KrArt.FOG_H
    return proj.screen(i, size, cam) - Offset(0f, h * s * 0.5f)
}

private fun DrawScope.route(proj: KrProjection, size: IntSize, cam: KrCamera, tiles: Map<Int, KrTile>, r: KrRoute, s: Float, phase: Float) {
    if (r.from !in proj.iso.indices || r.to !in proj.iso.indices) return
    val a = topCentre(proj, size, cam, tiles, r.from, s) - Offset(0f, s * 0.3f)
    val b = topCentre(proj, size, cam, tiles, r.to, s) - Offset(0f, s * 0.3f)
    val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2 - hypot(b.x - a.x, b.y - a.y) * 0.3f)
    val path = Path().apply { moveTo(a.x, a.y); quadraticTo(mid.x, mid.y, b.x, b.y) }
    val col = if (r.planned) Color(0xFF8E74D6) else KrArt.Ink
    drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(s * 0.17f, cap = StrokeCap.Round))
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
        color = android.graphics.Color.WHITE
    }
    val w = paint.measureText(text) + paint.textSize
    drawRoundRect(Color.White, top - Offset(w / 2 + 3f, paint.textSize * 0.85f + 3f), androidx.compose.ui.geometry.Size(w + 6f, paint.textSize * 1.3f + 6f),
        androidx.compose.ui.geometry.CornerRadius(paint.textSize * 0.65f + 3f))
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
