package com.loopa.feature.game.modes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.model.ModeState
import com.loopa.core.ui.feedback.Haptic
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.LocalReducedMotion
import com.loopa.feature.game.modes.Naval.coordinates
import com.loopa.feature.game.modes.Naval.explosion
import com.loopa.feature.game.modes.Naval.hitMark
import com.loopa.feature.game.modes.Naval.missMark
import com.loopa.feature.game.modes.Naval.ocean
import com.loopa.feature.game.modes.Naval.reconSweep
import com.loopa.feature.game.modes.Naval.reticle
import com.loopa.feature.game.modes.Naval.ship
import com.loopa.feature.game.modes.Naval.sinking
import com.loopa.feature.game.modes.Naval.sonarPing
import com.loopa.feature.game.modes.Naval.splash
import com.loopa.feature.game.modes.Naval.torpedoRun
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor
import kotlin.math.sin

// ====================================================================== model helpers
private fun shipOf(e: JsonElement?, cls: ShipClass) =
    NavalShip((e / "x").i() ?: 0, (e / "y").i() ?: 0, (e / "len").i() ?: 2, (e / "dir").s() != "v", cls)

/** A fleet list in its own order (placement order = the server's ship list order). */
private fun fleetOf(list: List<JsonElement>): List<NavalShip> {
    val lens = list.map { (it / "len").i() ?: 2 }
    return list.mapIndexed { i, e -> shipOf(e, ShipClass.of(lens, i)) }
}

private data class JokerDef(val t: String, val label: String, val short: String, val help: String)
private val JOKERS = listOf(
    JokerDef("sonar", "Sonar", "SONAR", "3×3 alana dokun: içinde vurulmamış gemi parçası var mı? Atış hakkın gitmez."),
    JokerDef("recon", "Keşif Uçağı", "KEŞİF", "Satır ya da sütun seç ve bir kareye dokun: o hatta vurulmamış gemi parçası var mı? Atış hakkın gitmez."),
    JokerDef("double", "Çifte Atış", "ÇİFTE", "İki farklı kare seç; ikisine birden ateş edilir. İsabet varsa yine sen atarsın."),
    JokerDef("torpedo", "Torpido", "TORPİDO", "Satır ya da sütun ve yön seç, hatta dokun: torpido ilerler, ilk gemi parçasını vurur. Boş suyu işaretlemez."),
    JokerDef("salvo", "Topçu Salvosu", "SALVO", "Yön seç ve başlangıç karesine dokun: düz bir hatta üç kareye ayrı ayrı ateş edilir."),
)
private fun jokerDef(t: String?) = JOKERS.firstOrNull { it.t == t } ?: JOKERS[0]

/** A visual effect, started on the first frame after it is created. */
private data class Fx(val kind: String, val side: Int, val cells: List<Pair<Int, Int>>, val ship: NavalShip? = null,
                      val axis: String? = null, val line: Int = 0, val rev: Boolean = false, val dur: Long, var born: Long = -1L)

private data class Intel(val t: String, val found: Boolean, val x: Int, val y: Int, val axis: String?, val i: Int)

// ====================================================================== screen
/**
 * Amiral Battı — naval tactical command. One chart at a time, following the turn: on your team's
 * turn the enemy waters (where you fire), on the enemy's turn your own fleet under fire. Every
 * result, turn change and joker comes from the server; the screen only draws confirmed events.
 */
@Composable
fun AmiralBattiScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val phase = (p / "phase").s()
    val n = (p / "size").i() ?: 10
    val ships = (p / "ships").ints()
    val teams = (p / "teams").a().map { it.strs() }
    val mySide = (mode.me / "side").i()?.takeIf { !ctx.isSpectator }
    val placed = (p / "placed").a().map { it.b() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Naval.Bg).testTag("ab_root")) {
        val wide = maxWidth >= 720.dp && maxWidth > maxHeight * 0.85f
        when {
            phase == "placement" && mySide != null && !placed.getOrElse(mySide) { false } && !mode.over ->
                Placement(n, ships, ui, wide, (p / "format").s() == "2v2", teams.getOrNull(mySide).orEmpty().filter { it != ctx.me }.map { ctx.name(it) })
            else -> Battle(ctx, mode, ui, wide, n, ships, teams, mySide, phase)
        }
    }
}

// ====================================================================== battle
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Battle(ctx: ModeContext, mode: ModeState, ui: ModeUi, wide: Boolean, n: Int, ships: List<Int>, teams: List<List<String>>, mySide: Int?, phase: String?) {
    val p = mode.pub
    val over = mode.over
    val turn = (p / "turn").s()
    val shooting = (p / "shooting").i() ?: 0
    val shots = (0..1).map { side -> (p / "shots").a().getOrNull(side).o().mapValues { it.value.s().orEmpty() } }
    val sunk = (0..1).map { side ->
        val list = (p / "sunk").a().getOrNull(side).a()
        list.mapIndexed { i, e -> val len = (e / "len").i() ?: 2; shipOf(e, ShipClass.byLen(len, list.take(i).count { (it / "len").i() == len })) }
    }
    val revealed = (p / "fleets").a().map { fleetOf(it.a()) }
    val myFleet = fleetOf((mode.me / "fleet").a())
    val jokers = (mode.me / "jokers").a()
    val intel = (mode.me / "intel").a().map {
        Intel((it / "t").s().orEmpty(), (it / "found").b(), (it / "x").i() ?: 0, (it / "y").i() ?: 0, (it / "axis").s(), (it / "i").i() ?: 0)
    }
    val jkCount = (p / "jk_count").o()
    val jkUsedNow = (p / "jk_turn").s() == ctx.me
    val log = (p / "log").a()
    val reduced = LocalReducedMotion.current
    val feedback = LocalFeedback.current
    val canFire = !over && phase == "battle" && mySide != null && turn == ctx.me && shooting == mySide
    val enemy = mySide?.let { 1 - it }

    // ---------------------------------------------------------------- which chart: follows the turn
    var overView by remember { mutableStateOf(true) }               // after the game: enemy (true) or own waters
    val desired = when {
        over -> if (mySide == null) 1 - ((p / "winner_side").i() ?: 0) else if (overView) enemy!! else mySide
        mySide == null -> 1 - shooting
        shooting == mySide -> enemy!!
        else -> mySide
    }
    var shown by remember { mutableIntStateOf(desired) }
    var bannerText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(desired, over) {
        if (desired == shown) return@LaunchedEffect
        if (!reduced && !over) delay(1100)                           // let the last shot's effect finish on the old chart
        shown = desired
        if (!over && mySide != null) {
            bannerText = if (desired == enemy) (if (turn == ctx.me) "SIRA SENDE" else "TAKIMIN ATEŞTE") else "RAKİP ATEŞ EDİYOR"
            feedback.haptic(Haptic.LIGHT)
            delay(if (reduced) 900 else 1300); bannerText = null
        }
    }

    // ---------------------------------------------------------------- targeting state
    var target by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var jkSel by remember { mutableStateOf<JsonElement?>(null) }
    var jkAxis by remember { mutableStateOf("row") }
    var jkRev by remember { mutableStateOf(false) }
    var jkDir by remember { mutableStateOf("h") }
    val jkCells = remember { mutableStateListOf<Pair<Int, Int>>() }
    LaunchedEffect(turn, canFire) { if (!canFire) { target = null; jkSel = null; jkCells.clear() } }
    val jt = (jkSel / "t").s()
    val enemyShots = enemy?.let { shots[it] }.orEmpty()
    fun free(c: Pair<Int, Int>) = "${c.first},${c.second}" !in enemyShots
    val preview: List<Pair<Int, Int>> = target?.let { (x, y) ->
        when (jt) {
            "sonar" -> (-1..1).flatMap { dx -> (-1..1).map { dy -> (x + dx) to (y + dy) } }.filter { (a, b) -> a in 0 until n && b in 0 until n }
            "recon", "torpedo" -> (0 until n).map { if (jkAxis == "row") it to y else x to it }
            "salvo" -> (0..2).map { if (jkDir == "h") (x + it) to y else x to (y + it) }
            else -> emptyList()
        }
    }.orEmpty() + if (jt == "double") jkCells.toList() else emptyList()
    val previewOk = when (jt) {
        null -> target?.let(::free) == true
        "salvo" -> preview.size == 3 && preview.all { (a, b) -> a < n && b < n } && preview.any(::free)
        "double" -> jkCells.size == 2
        else -> target != null
    }

    // ---------------------------------------------------------------- effects from confirmed server events
    val fx = remember { mutableStateListOf<Fx>() }
    var clock by remember { mutableLongStateOf(0L) }
    val shake = remember { Animatable(0f) }
    val prevShots = remember { mutableStateOf<List<Map<String, String>>?>(null) }
    val prevSunk = remember { mutableStateOf<List<Int>?>(null) }
    val prevLog = remember { mutableStateOf<List<JsonElement>?>(null) }
    val prevJokers = remember { mutableStateOf<Set<String>?>(null) }
    var ticker by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(p.toString(), mode.me.toString()) {
        val oldShots = prevShots.value; val oldSunk = prevSunk.value; val oldLog = prevLog.value; val oldJk = prevJokers.value
        prevShots.value = shots; prevSunk.value = sunk.map { it.size }; prevLog.value = log; prevJokers.value = jokers.mapNotNull { (it / "id").s() }.toSet()
        if (oldShots == null) { ticker = log.lastOrNull()?.let { describe(ctx, it) }; return@LaunchedEffect }
        // new log entries since last time (the log is a sliding window of 30)
        val fresh = oldLog?.lastOrNull()?.let { last -> val k = log.lastIndexOf(last); if (k >= 0) log.drop(k + 1) else log } ?: log
        fresh.lastOrNull()?.let { ticker = describe(ctx, it) }
        var big = false
        for (side in 0..1) {
            for ((cell, r) in shots[side]) if (cell !in oldShots[side]) {
                val (x, y) = cell.split(",").map { it.toInt() }
                if (!reduced) fx += Fx(if (r == "miss") "splash" else "boom", side, listOf(x to y), dur = if (r == "miss") 900 else 1100)
                if (r != "miss" && side == shown) big = true
            }
            if (sunk[side].size > (oldSunk?.getOrNull(side) ?: 0)) {
                sunk[side].drop(oldSunk?.getOrNull(side) ?: 0).forEach { s -> if (!reduced) fx += Fx("sink", side, s.cells(), ship = s, dur = 1900) }
                feedback.play(Sfx.REVEAL)
            }
        }
        fresh.forEach { e ->
            val u = (e / "u").s()
            val sideOfU = teams.indexOfFirst { u in it }.takeIf { it >= 0 } ?: 0
            when ((e / "e").s()) {
                "jk" -> if (!reduced) when ((e / "t").s()) {
                    "sonar" -> fx += Fx("sonar", 1 - sideOfU, listOf(((e / "x").i() ?: 0) to ((e / "y").i() ?: 0)), dur = 1400)
                    "recon" -> fx += Fx("recon", 1 - sideOfU, emptyList(), axis = (e / "axis").s(), line = (e / "i").i() ?: 0, dur = 1500)
                    "torpedo" -> fx += Fx("torpedo", 1 - sideOfU, listOfNotNull((e / "stop").s()?.split(",")?.map { it.toInt() }?.let { it[0] to it[1] }),
                        axis = (e / "axis").s(), line = (e / "i").i() ?: 0, rev = (e / "rev").b(), dur = 1300)
                }
                "joker" -> {
                    val newOnes = jokers.filter { (it / "id").s() !in oldJk.orEmpty() }
                    val msg = if (u == ctx.me && newOnes.isNotEmpty()) "Joker kazandın: ${jokerDef((newOnes.last() / "t").s()).label}"
                              else "${ctx.name(u)} bir joker kazandı"
                    ui.flash(msg); feedback.play(Sfx.JOKER)
                }
                "joker_full" -> if (u == ctx.me) ui.flash("Envanterin dolu (3/3): yeni joker verilmedi")
            }
        }
        if (big) {
            feedback.haptic(Haptic.MEDIUM)
            if (!reduced) { shake.snapTo(1f); shake.animateTo(0f, tween(420)) }
        }
    }
    LaunchedEffect(fx.size) {
        while (fx.isNotEmpty()) withFrameMillis { t ->
            fx.forEach { if (it.born < 0) it.born = t }
            clock = t
            fx.removeAll { t - it.born > it.dur }
        }
    }

    // ---------------------------------------------------------------- what the shown chart holds
    val boardShips = when {
        over -> revealed.getOrNull(shown).orEmpty().ifEmpty { if (shown == mySide) myFleet else emptyList() }
        shown == mySide -> myFleet
        else -> emptyList()
    }
    val wrecks = sunk[shown]
    val targeting = canFire && shown == enemy
    val fire: () -> Unit = {
        val (x, y) = target!!
        feedback.play(Sfx.SUBMIT)
        ui.act("fire", payload("x" to x, "y" to y)) { }
        target = null
    }
    val useJoker: () -> Unit = {
        val id = (jkSel / "id").i()
        val pl = when (jt) {
            "sonar" -> payload("id" to id, "x" to target!!.first, "y" to target!!.second)
            "recon" -> payload("id" to id, "axis" to jkAxis, "i" to if (jkAxis == "row") target!!.second else target!!.first)
            "torpedo" -> payload("id" to id, "axis" to jkAxis, "i" to if (jkAxis == "row") target!!.second else target!!.first, "rev" to jkRev)
            "salvo" -> payload("id" to id, "x" to target!!.first, "y" to target!!.second, "dir" to jkDir)
            else -> payload("id" to id, "cells" to jsonList(jkCells.map { jsonInts(listOf(it.first, it.second)) }))
        }
        feedback.play(Sfx.JOKER)
        ui.act("joker", pl) { }
        jkSel = null; target = null; jkCells.clear()
    }
    val onCell: ((Int, Int) -> Unit)? = if (targeting && ui.busy == null) { x, y ->
        feedback.play(Sfx.TAP)
        when (jt) {
            "double" -> if (free(x to y)) { if ((x to y) in jkCells) jkCells.remove(x to y) else if (jkCells.size < 2) jkCells.add(x to y) }
            null -> if (free(x to y)) target = x to y
            else -> target = x to y
        }
    } else null

    val board: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.offset { IntOffset((sin(shake.value * 40f) * 14f * shake.value).toInt(), 0) }) {
            NavalBoard(n, shots[shown], boardShips, wrecks, shownMine = shown == mySide,
                intel = if (shown == enemy && !over) intel.takeLast(3) else emptyList(),
                target = if (targeting && jt != "double") target else null, preview = if (targeting) preview else emptyList(), previewOk = previewOk,
                radar = targeting, fx = fx.filter { it.side == shown }, clock = clock, onCell = onCell,
                tag = if (shown == mySide) "ab_mine" else "ab_enemy",
                label = when { shown == mySide -> "SENİN FİLON"; else -> "RAKİP SULAR" })
            AnimatedVisibility(bannerText != null, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut()) {
                Text(bannerText.orEmpty(), color = Naval.Bg, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 3.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (bannerText == "RAKİP ATEŞ EDİYOR") Naval.Amber else Naval.Cyan)
                        .padding(horizontal = 20.dp, vertical = 8.dp).testTag("ab_turn_banner"))
            }
        }
    }
    val hud: @Composable () -> Unit = {
        CommandHud(ctx, teams, mySide, turn, shooting, over, (p / "winner_side").i(), (p / "format").s() == "2v2", jkCount, sunk, ships, myFleet.size, wide)
    }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (over && mySide != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Seg("Rakip sular", overView, "ab_view_enemy") { overView = true }
                Seg("Senin filon", !overView, "ab_view_mine") { overView = false }
            }
            if (!over && mySide != null) {
                if (jkSel != null) JokerPanel(jkDef = jokerDef(jt), axis = jkAxis, rev = jkRev, dir = jkDir, picked = jkCells.size,
                    ready = previewOk && ui.busy == null, onAxis = { jkAxis = it }, onRev = { jkRev = !jkRev }, onDir = { jkDir = it },
                    onUse = useJoker, onCancel = { jkSel = null; jkCells.clear() })
                else FireBar(canFire, target, previewOk && ui.busy == null, fire)
                JokerTray(jokers, enabled = canFire && !jkUsedNow && ui.busy == null, usedThisTurn = jkUsedNow && canFire, selectedId = (jkSel / "id").s()) { j ->
                    jkSel = j; target = null; jkCells.clear()
                }
            }
            ticker?.let { Ticker(it) }
        }
    }

    if (wide) {
        // tablet: the chart takes the height, a command column on the side
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) { board(Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                hud(); controls()
                if (shown == enemy && intel.isNotEmpty()) IntelList(intel.takeLast(4))
                LogList(ctx, log.takeLast(8))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hud()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight)
                board(Modifier.size(side))
            }
            controls()
        }
    }
}

private fun describe(ctx: ModeContext, e: JsonElement): String? {
    val u = ctx.name((e / "u").s())
    return when ((e / "e").s()) {
        "joker" -> "$u bir joker kazandı"
        "joker_full" -> "$u'nin envanteri dolu; joker verilmedi"
        "jk" -> "$u ${jokerDef((e / "t").s()).label} kullandı"
        null -> (e / "r").s()?.let { r ->
            "$u → ${Naval.cellName((e / "x").i() ?: 0, (e / "y").i() ?: 0)}: " + when (r) { "hit" -> "İSABET"; "sunk" -> "GEMİ BATTI"; else -> "ıska" } +
                ((e / "via").s()?.let { " (${jokerDef(it).label})" } ?: "")
        } ?: if ((e / "placed").b()) "Bir filo konuşlandı" else null
        else -> null
    }
}

// ====================================================================== chart
@Composable
private fun NavalBoard(n: Int, shots: Map<String, String>, ships: List<NavalShip>, wrecks: List<NavalShip>, shownMine: Boolean, intel: List<Intel>,
                       target: Pair<Int, Int>?, preview: List<Pair<Int, Int>>, previewOk: Boolean, radar: Boolean, fx: List<Fx>, clock: Long,
                       onCell: ((Int, Int) -> Unit)?, tag: String, label: String,
                       ghost: NavalShip? = null, ghostOk: Boolean = true, selectedShip: NavalShip? = null, onBounds: (Rect) -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    val inf = rememberInfiniteTransition(label = "sea")
    val t = if (reduced) null else inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "t")
    val pulse = if (reduced) null else inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    val hits = shots.count { it.value != "miss" }; val misses = shots.size - hits
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Naval.Surface).border(1.dp, Naval.Rim, RoundedCornerShape(14.dp))) {
        Canvas(Modifier.fillMaxSize().onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .semantics { contentDescription = "$label: $hits isabet, $misses ıska" }.testTag(tag)
            .pointerInput(onCell, n) {
                if (onCell != null) detectTapGestures { pos ->
                    val b = chartRect(size.width.toFloat(), size.height.toFloat())
                    val cell = b.width / n
                    val x = floor((pos.x - b.left) / cell).toInt(); val y = floor((pos.y - b.top) / cell).toInt()
                    if (x in 0 until n && y in 0 until n) onCell(x, y)
                }
            }) {
            val b = chartRect(size.width, size.height)
            ocean(b, n, t?.value ?: 0f, radar && !reduced)
            coordinates(b, n)
            // intel overlays (team-private): sonar areas and recon lines
            intel.forEach { iv ->
                val area = if (iv.t == "sonar") Rect(Naval.cellRect(b, n, (iv.x - 1).coerceAtLeast(0), (iv.y - 1).coerceAtLeast(0)).topLeft,
                    Naval.cellRect(b, n, (iv.x + 1).coerceAtMost(n - 1), (iv.y + 1).coerceAtMost(n - 1)).bottomRight)
                else if (iv.axis == "row") Rect(b.left, Naval.cellRect(b, n, 0, iv.i).top, b.right, Naval.cellRect(b, n, 0, iv.i).bottom)
                else Rect(Naval.cellRect(b, n, iv.i, 0).left, b.top, Naval.cellRect(b, n, iv.i, 0).right, b.bottom)
                val col = if (iv.found) Naval.Amber else Naval.Soft
                drawRect(col.copy(alpha = if (iv.found) 0.14f else 0.07f), area.topLeft, area.size)
                drawRect(col.copy(alpha = 0.8f), area.topLeft, area.size, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
            // ships: own / revealed, then wrecks
            val sinkingNow = fx.filter { it.kind == "sink" }.mapNotNull { it.ship }.toSet()
            ships.forEach { s ->
                if (s in sinkingNow) return@forEach
                val wreck = wrecks.any { w -> w.cells() == s.cells() }
                ship(b, n, s, tint = if (wreck) Naval.HitDeep else null, alpha = if (wreck) 0.75f else 1f)
            }
            if (!shownMine) wrecks.forEach { w -> if (w !in sinkingNow && ships.none { it.cells() == w.cells() }) ship(b, n, w, tint = Naval.HitDeep, alpha = 0.8f) }
            // permanent markers
            for ((key, r) in shots) {
                val (x, y) = key.split(",").map { it.toInt() }
                val rect = Naval.cellRect(b, n, x, y)
                if (r == "miss") missMark(rect) else hitMark(rect, pulse?.value ?: 0.5f)
            }
            // selection preview and reticle
            preview.forEach { (x, y) ->
                if (x !in 0 until n || y !in 0 until n) return@forEach
                val r = Naval.cellRect(b, n, x, y)
                drawRect((if (previewOk) Naval.Cyan else Naval.HitDeep).copy(alpha = 0.22f), r.topLeft, r.size)
                drawRect((if (previewOk) Naval.Cyan else Naval.HitDeep).copy(alpha = 0.7f), r.topLeft, r.size, style = Stroke(1.5f))
            }
            target?.let { (x, y) -> reticle(Naval.cellRect(b, n, x, y), pulse?.value ?: 0.25f, if (previewOk) Naval.Cyan else Naval.HitDeep) }
            selectedShip?.let { s -> s.cells().forEach { (x, y) -> val r = Naval.cellRect(b, n, x, y); drawRect(Naval.Cyan, r.topLeft, r.size, style = Stroke(2.5f)) } }
            ghost?.let { g -> ship(b, n, g, tint = if (ghostOk) Naval.Cyan else Naval.HitDeep, alpha = 0.8f) }
            // effects
            fx.forEach { e -> drawFx(e, b, n, ((clock - e.born).toFloat() / e.dur).coerceIn(0f, 1f).takeIf { e.born >= 0 } ?: 0f) }
        }
    }
}

/** The chart inside its frame, leaving room for the coordinates on the top and left. */
private fun chartRect(w: Float, h: Float): Rect {
    val s = minOf(w, h); val m = s * 0.075f
    val side = s - m - s * 0.02f
    return Rect(m, m, m + side, m + side)
}

private fun DrawScope.drawFx(e: Fx, b: Rect, n: Int, f: Float) {
    when (e.kind) {
        "splash" -> e.cells.forEach { (x, y) -> splash(Naval.cellRect(b, n, x, y), f) }
        "boom" -> e.cells.forEach { (x, y) -> explosion(Naval.cellRect(b, n, x, y), f) }
        "sink" -> e.ship?.let { sinking(b, n, it, f) }
        "sonar" -> e.cells.firstOrNull()?.let { (x, y) -> sonarPing(Naval.cellRect(b, n, x, y), f) }
        "recon" -> {
            val line = if (e.axis == "row") Rect(b.left, Naval.cellRect(b, n, 0, e.line).top, b.right, Naval.cellRect(b, n, 0, e.line).bottom)
                       else Rect(Naval.cellRect(b, n, e.line, 0).left, b.top, Naval.cellRect(b, n, e.line, 0).right, b.bottom)
            reconSweep(line, e.axis == "row", f)
        }
        "torpedo" -> {
            val cell = b.width / n
            val start = if (e.axis == "row") Naval.cellRect(b, n, if (e.rev) n - 1 else 0, e.line).center else Naval.cellRect(b, n, e.line, if (e.rev) n - 1 else 0).center
            val stop = e.cells.firstOrNull()?.let { (x, y) -> Naval.cellRect(b, n, x, y).center }
                ?: if (e.axis == "row") Naval.cellRect(b, n, if (e.rev) 0 else n - 1, e.line).center else Naval.cellRect(b, n, e.line, if (e.rev) 0 else n - 1).center
            val from = start - (stop - start).let { d -> val l = d.getDistance().coerceAtLeast(1f); Offset(d.x / l * cell * 0.6f, d.y / l * cell * 0.6f) }
            torpedoRun(from, stop, f, cell)
        }
    }
}

// ====================================================================== HUD and controls
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandHud(ctx: ModeContext, teams: List<List<String>>, mySide: Int?, turn: String?, shooting: Int, over: Boolean, winner: Int?, duo: Boolean,
                       jkCount: JsonObject, sunk: List<List<NavalShip>>, ships: List<Int>, fleetSize: Int, wide: Boolean) {
    val status = when {
        over -> if (mySide == null) "OYUN BİTTİ" else if (winner == mySide) "ZAFER — DÜŞMAN FİLOSU BATIRILDI" else "FİLON BATTI"
        mySide == null -> "İZLEYİCİ · ${ctx.name(turn)} ateşte"
        turn == ctx.me -> "SIRA SENDE · HEDEF SEÇ"
        shooting == mySide -> "TAKIM ARKADAŞIN ATEŞTE · ${ctx.name(turn)}"
        else -> "RAKİP ATEŞ EDİYOR · ${ctx.name(turn)}"
    }
    val statusColor = when { over && winner == mySide -> Naval.Good; over -> Naval.HitDeep; turn == ctx.me -> Naval.Cyan; shooting == mySide -> Naval.Ice; else -> Naval.Amber }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Naval.Card).border(1.dp, Naval.Rim, RoundedCornerShape(12.dp)).padding(10.dp).testTag("ab_hud"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Text(status, color = statusColor, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("ab_status"))
            Text(if (duo) "2v2" else "1v1", color = Naval.Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            teams.forEachIndexed { side, members ->
                val col = if (side == 0) Naval.TeamA else Naval.TeamB
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (shooting == side && !over) col.copy(alpha = 0.16f) else Naval.Surface)
                    .border(1.dp, if (shooting == side && !over) col else Naval.Rim, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("ab_team_$side"), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (side == mySide) "SİZ" else "RAKİP", color = col, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    members.forEach { u ->
                        Spacer(Modifier.width(6.dp))
                        Text((if (u == turn && !over) "▶ " else "") + ctx.name(u) + if (u == ctx.me) " (sen)" else "",
                            color = if (u == turn && !over) Naval.Text else Naval.Soft, fontSize = 12.sp, fontWeight = if (u == turn) FontWeight.Black else FontWeight.Medium, maxLines = 1)
                        val k = (jkCount / u).i() ?: 0
                        if (k > 0) Text(" ◆$k", color = Naval.Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "$k joker" })
                    }
                }
            }
        }
        if (mySide != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FleetPips("DÜŞMAN", ships.size, sunk.getOrNull(1 - mySide).orEmpty().size, Naval.Amber)
            FleetPips("FİLON", ships.size, sunk.getOrNull(mySide).orEmpty().size, Naval.Cyan)
        }
    }
}

@Composable
private fun FleetPips(label: String, total: Int, lost: Int, col: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("ab_pips_$label")) {
        Text("$label ${total - lost}/$total", color = Naval.Soft, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(5.dp))
        repeat(total) { k -> Box(Modifier.padding(end = 2.dp).size(width = 9.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(if (k < total - lost) col else Naval.Rim)) }
    }
}

@Composable
private fun FireBar(canFire: Boolean, target: Pair<Int, Int>?, ok: Boolean, onFire: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Naval.Card).border(1.dp, Naval.Rim, RoundedCornerShape(12.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("HEDEF", color = Naval.Soft, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(target?.let { Naval.cellName(it.first, it.second) } ?: if (canFire) "Rakip sularda bir kare seç" else "Bekleniyor",
                color = if (target != null) Naval.Cyan else Naval.Soft, fontWeight = FontWeight.Black, fontSize = if (target != null) 20.sp else 13.sp,
                fontFamily = if (target != null) FontFamily.Monospace else FontFamily.Default, modifier = Modifier.testTag("ab_target"))
        }
        CmdButton("ATEŞ", Naval.HitDeep, enabled = canFire && ok, tag = "ab_fire", onClick = onFire)
    }
}

@Composable
private fun CmdButton(text: String, col: Color, enabled: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) col else Naval.CardHigh)
        .border(1.dp, if (enabled) col else Naval.Rim, RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else Naval.Soft, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 2.sp, maxLines = 1)
    }
}

@Composable
private fun Seg(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Text(text, color = if (on) Naval.Bg else Naval.Soft, fontWeight = FontWeight.Black, fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Naval.Cyan else Naval.Card).border(1.dp, if (on) Naval.Cyan else Naval.Rim, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp).testTag(tag))
}

@Composable
private fun JokerTray(jokers: List<JsonElement>, enabled: Boolean, usedThisTurn: Boolean, selectedId: String?, onPick: (JsonElement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (usedThisTurn) "JOKER · bu tur kullanıldı" else "JOKER · ${jokers.size}/3", color = Naval.Soft, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("ab_jokers")) {
            repeat(3) { k ->
                val j = jokers.getOrNull(k)
                val t = (j / "t").s()
                val sel = j != null && (j / "id").s() == selectedId
                Column(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Naval.Cyan.copy(alpha = 0.18f) else Naval.Card)
                    .border(1.dp, if (sel) Naval.Cyan else if (j != null) Naval.Rim else Naval.Rim.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .clickable(enabled = j != null && enabled) { onPick(j!!) }.padding(6.dp)
                    .testTag(if (t != null) "ab_jk_$t" else "ab_jk_empty_$k"), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (t != null) {
                        Canvas(Modifier.size(22.dp)) { jokerIcon(t, if (enabled) Naval.Cyan else Naval.Soft) }
                        Text(jokerDef(t).short, color = if (enabled) Naval.Text else Naval.Soft, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1)
                    } else Text("—", color = Naval.Rim, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

private fun DrawScope.jokerIcon(t: String, c: Color) {
    val w = size.width; val ctr = Offset(w / 2, w / 2)
    when (t) {
        "sonar" -> { for (k in 1..3) drawCircle(c.copy(alpha = 1f - k * 0.25f), w * 0.15f * k, ctr, style = Stroke(2f)); drawCircle(c, w * 0.08f, ctr) }
        "recon" -> { drawLine(c, Offset(w * 0.1f, w / 2), Offset(w * 0.9f, w / 2), w * 0.1f); drawLine(c, Offset(w * 0.5f, w * 0.15f), Offset(w * 0.5f, w * 0.85f), w * 0.1f); drawLine(c, Offset(w * 0.2f, w * 0.75f), Offset(w * 0.2f, w * 0.25f), w * 0.07f) }
        "double" -> { drawCircle(c, w * 0.18f, Offset(w * 0.3f, w * 0.5f), style = Stroke(2f)); drawCircle(c, w * 0.18f, Offset(w * 0.7f, w * 0.5f), style = Stroke(2f)) }
        "torpedo" -> {
            drawLine(c, Offset(w * 0.22f, w * 0.5f), Offset(w * 0.86f, w * 0.5f), w * 0.2f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(c, Offset(w * 0.2f, w * 0.5f), Offset(w * 0.06f, w * 0.3f), w * 0.07f); drawLine(c, Offset(w * 0.2f, w * 0.5f), Offset(w * 0.06f, w * 0.7f), w * 0.07f)
            drawCircle(Naval.Bg, w * 0.04f, Offset(w * 0.74f, w * 0.5f))
        }
        else -> for (k in 0..2) drawCircle(c, w * 0.11f, Offset(w * (0.22f + k * 0.28f), w * 0.5f))
    }
}

@Composable
private fun JokerPanel(jkDef: JokerDef, axis: String, rev: Boolean, dir: String, picked: Int, ready: Boolean,
                       onAxis: (String) -> Unit, onRev: () -> Unit, onDir: (String) -> Unit, onUse: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Naval.Card).border(1.dp, Naval.Cyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
        .padding(10.dp).testTag("ab_jk_panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(jkDef.label.uppercase(), color = Naval.Cyan, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.sp)
        Text(jkDef.help + if (jkDef.t == "double") " ($picked/2)" else "", color = Naval.Soft, fontSize = 12.sp)
        if (jkDef.t in setOf("recon", "torpedo", "salvo")) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (jkDef.t == "salvo") { Seg("Yatay", dir == "h", "ab_jk_dir_h") { onDir("h") }; Seg("Dikey", dir == "v", "ab_jk_dir_v") { onDir("v") } }
            else { Seg("Satır", axis == "row", "ab_jk_axis_row") { onAxis("row") }; Seg("Sütun", axis == "col", "ab_jk_axis_col") { onAxis("col") } }
            if (jkDef.t == "torpedo") Seg(if (axis == "row") (if (rev) "Sağdan ◀" else "Soldan ▶") else (if (rev) "Alttan ▲" else "Üstten ▼"), true, "ab_jk_rev", onRev)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CmdButton("VAZGEÇ", Naval.CardHigh, enabled = true, tag = "ab_jk_cancel", modifier = Modifier.weight(1f), onClick = onCancel)
            CmdButton(if (jkDef.t in setOf("sonar", "recon")) "TARA" else "ATEŞ", if (jkDef.t in setOf("sonar", "recon")) Color(0xFF1C8FA0) else Naval.HitDeep,
                enabled = ready, tag = "ab_jk_use", modifier = Modifier.weight(1f), onClick = onUse)
        }
    }
}

@Composable
private fun Ticker(text: String) {
    Text("› $text", color = Naval.Ice, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Naval.Surface).padding(horizontal = 10.dp, vertical = 6.dp).testTag("ab_ticker"))
}

@Composable
private fun IntelList(intel: List<Intel>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Naval.Card).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("İSTİHBARAT", color = Naval.Soft, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        intel.reversed().forEach { iv ->
            val where = if (iv.t == "sonar") "${Naval.cellName(iv.x, iv.y)} çevresi" else if (iv.axis == "row") "${iv.i + 1}. satır" else "${Naval.colLabel(iv.i)} sütunu"
            Text("${jokerDef(iv.t).label} · $where: " + if (iv.found) "TEMAS VAR" else "temiz", color = if (iv.found) Naval.Amber else Naval.Soft,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun LogList(ctx: ModeContext, log: List<JsonElement>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Naval.Card).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("SEYİR DEFTERİ", color = Naval.Soft, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        log.reversed().mapNotNull { describe(ctx, it) }.forEach { Text(it, color = Naval.Text.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// ====================================================================== placement
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Placement(n: Int, lens: List<Int>, ui: ModeUi, wide: Boolean, duo: Boolean, mates: List<String>) {
    val placed = remember(n, lens) { mutableStateMapOf<Int, NavalShip>() }
    var selected by remember { mutableStateOf<Int?>(0) }
    var nextH by remember { mutableStateOf(true) }
    var drag by remember { mutableStateOf<Pair<Int, Offset>?>(null) }      // ship index and pointer (container coords)
    var dragGrab by remember { mutableIntStateOf(0) }
    var dragH by remember { mutableStateOf(true) }
    var container by remember { mutableStateOf(Rect.Zero) }
    var lastDown by remember { mutableStateOf(Offset.Zero) }           // where the finger touched (drag starts report the slop point)
    var boardBounds by remember { mutableStateOf(Rect.Zero) }
    val traySlots = remember { mutableStateMapOf<Int, Rect>() }
    val feedback = LocalFeedback.current
    fun cls(i: Int) = ShipClass.of(lens, i)
    fun valid(s: NavalShip, except: Int?): Boolean {
        val others = placed.filterKeys { it != except }.values.flatMap { it.cells() }.toSet()
        return s.cells().all { (x, y) -> x in 0 until n && y in 0 until n && (x to y) !in others }
    }
    /** Board cell under a container point, or null. */
    fun cellAt(pos: Offset): Pair<Int, Int>? {
        val local = pos + container.topLeft - boardBounds.topLeft
        val b = chartRect(boardBounds.width, boardBounds.height)
        val cell = b.width / n
        val x = floor((local.x - b.left) / cell).toInt(); val y = floor((local.y - b.top) / cell).toInt()
        return if (x in 0 until n && y in 0 until n) x to y else null
    }
    fun trayAt(pos: Offset): Int? = traySlots.entries.firstOrNull { (i, r) -> i !in placed && r.translate(-container.topLeft).contains(pos) }?.key
    fun shipAt(c: Pair<Int, Int>): Int? = placed.entries.firstOrNull { c in it.value.cells() }?.key
    val ghost = drag?.let { (i, pos) -> cellAt(pos)?.let { (cx, cy) -> NavalShip(if (dragH) cx - dragGrab else cx, if (dragH) cy else cy - dragGrab, lens[i], dragH, cls(i)) } }
    val unplaced = lens.indices.filter { it !in placed }

    val board: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            NavalBoard(n, emptyMap(), placed.values.toList(), emptyList(), shownMine = true, intel = emptyList(), target = null, preview = emptyList(), previewOk = true,
                radar = false, fx = emptyList(), clock = 0L, onCell = null, tag = "ab_place", label = "KONUŞLANMA",
                ghost = ghost, ghostOk = ghost?.let { valid(it, drag?.first) } ?: true,
                selectedShip = selected?.let { placed[it] }, onBounds = { boardBounds = it })
        }
    }
    val tray: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (unplaced.isEmpty()) "Filo hazır. Gemiyi seçip döndürebilir ya da sürükleyerek taşıyabilirsin." else "Gemiyi haritaya sürükle ya da seçip haritada bir kareye dokun.",
                color = Naval.Soft, fontSize = 12.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lens.indices.forEach { i ->
                    val isPlaced = i in placed
                    val sel = selected == i
                    Column(Modifier.width(maxOf(72, lens[i] * 20 + 22).dp).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Naval.Cyan.copy(alpha = 0.16f) else Naval.Card)
                        .border(1.dp, if (sel) Naval.Cyan else Naval.Rim, RoundedCornerShape(10.dp))
                        .onGloballyPositioned { traySlots[i] = it.boundsInRoot() }.padding(6.dp).testTag("ab_tray_$i")) {
                        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                            val cell = minOf(size.width / lens[i], size.height)
                            ship(Rect(0f, 0f, cell * lens[i], cell * lens[i]), lens[i], NavalShip(0, 0, lens[i], true, cls(i)), alpha = if (isPlaced) 0.35f else 1f)
                        }
                        Text("${cls(i).label} · ${lens[i]}", color = if (isPlaced) Naval.Soft else Naval.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CmdButton("DÖNDÜR ⟳", Naval.CardHigh, enabled = selected != null, tag = "ab_rotate", modifier = Modifier.weight(1f)) {
                    val i = selected ?: return@CmdButton
                    val s = placed[i]
                    if (s == null) nextH = !nextH
                    else { val r = s.copy(h = !s.h); if (valid(r, i)) placed[i] = r else ui.showError("Döndürmek için yer yok.") }
                    feedback.play(Sfx.TAP)
                }
                CmdButton("TEMİZLE", Naval.CardHigh, enabled = placed.isNotEmpty(), tag = "ab_clear", modifier = Modifier.weight(1f)) { placed.clear(); selected = 0 }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CmdButton("RASTGELE", Color(0xFF1C8FA0), enabled = ui.busy == null, tag = "ab_random", modifier = Modifier.weight(1f)) { ui.act("place", payload("random" to true)) {} }
                CmdButton("HAZIR", Naval.Good.copy(alpha = 0.9f), enabled = unplaced.isEmpty() && ui.busy == null, tag = "ab_confirm", modifier = Modifier.weight(1f)) {
                    feedback.play(Sfx.READY)
                    ui.act("place", payload("fleet" to jsonList(lens.indices.map { i -> placed.getValue(i).let { s ->
                        payload("x" to s.x, "y" to s.y, "len" to s.len, "dir" to if (s.h) "h" else "v") } }))) {}
                }
            }
            if (duo) Text("2v2: takımın tek bir filo kurar; ilk onaylayan takım arkadaşının filosu geçerli olur." +
                (if (mates.isNotEmpty()) " Takım arkadaşın: ${mates.joinToString()}" else ""), color = Naval.Soft, fontSize = 11.sp)
            Text(if (selected != null && selected !in placed) "Seçili: ${cls(selected!!).label} · ${if (nextH) "yatay" else "dikey"}" else "", color = Naval.Cyan, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("ab_place_hint"))
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { container = it.boundsInRoot() }.testTag("ab_place_area")
        .pointerInput(n, lens) {
            detectTapGestures(onPress = { lastDown = it }) { pos ->
                val c = cellAt(pos)
                val tr = trayAt(pos)
                when {
                    tr != null -> { selected = tr; feedback.play(Sfx.TAP) }
                    c != null && shipAt(c) != null -> selected = shipAt(c)
                    c != null && selected != null && selected !in placed -> {
                        val i = selected!!
                        val s = NavalShip(c.first, c.second, lens[i], nextH, cls(i))
                        if (valid(s, i)) { placed[i] = s; feedback.play(Sfx.TAP); selected = lens.indices.firstOrNull { it !in placed } ?: i }
                        else { ui.showError("Gemi oraya sığmıyor ya da başka bir gemiyle çakışıyor."); feedback.haptic(Haptic.WARNING) }
                    }
                }
            }
        }
        .pointerInput(n, lens) {
            detectDragGestures(
                onDragStart = { now ->
                    val pos = lastDown
                    val c = cellAt(pos)
                    val onBoard = c?.let { shipAt(it) }
                    if (onBoard != null) {
                        val s = placed.getValue(onBoard)
                        dragGrab = if (s.h) c.first - s.x else c.second - s.y; dragH = s.h
                        drag = onBoard to now; selected = onBoard; placed.remove(onBoard)
                    } else trayAt(pos)?.let { i -> dragGrab = lens[i] / 2; dragH = nextH; drag = i to now; selected = i }
                },
                onDrag = { change, amount -> change.consume(); drag = drag?.let { (i, p0) -> i to (p0 + amount) } },
                onDragEnd = {
                    val (i, pos) = drag ?: return@detectDragGestures
                    val g = cellAt(pos)?.let { (cx, cy) -> NavalShip(if (dragH) cx - dragGrab else cx, if (dragH) cy else cy - dragGrab, lens[i], dragH, cls(i)) }
                    if (g != null && valid(g, i)) { placed[i] = g; feedback.play(Sfx.TAP) } else if (g != null) ui.showError("Geçersiz konum: gemiler harita dışına taşamaz ve çakışamaz.")
                    drag = null
                },
                onDragCancel = { drag = null },
            )
        }) {
        if (wide) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) { board(Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { PlacementTitle(); tray() }
        } else Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlacementTitle()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { board(Modifier.size(minOf(maxWidth, maxHeight))) }
            tray()
        }
        // the ship follows the finger while it is off the chart
        drag?.let { (i, pos) ->
            if (cellAt(pos) == null) Canvas(Modifier.offset { IntOffset(pos.x.toInt() - 60, pos.y.toInt() - 20) }.size(width = (lens[i] * 26).dp, height = 26.dp)) {
                val cell = size.height
                ship(Rect(0f, 0f, cell * lens[i], cell * lens[i]), lens[i], NavalShip(0, 0, lens[i], true, cls(i)), tint = Naval.Cyan, alpha = 0.8f)
            }
        }
    }
}

@Composable
private fun PlacementTitle() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Naval.Card).border(1.dp, Naval.Rim, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text("FİLO KONUŞLANMASI", color = Naval.Cyan, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 2.sp)
        Text("Gemiler harita dışına taşamaz ve üst üste gelemez. Süre biterse filo rastgele konuşlanır.", color = Naval.Soft, fontSize = 11.sp, textAlign = TextAlign.Start)
    }
}
