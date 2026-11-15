package com.loopa.feature.game.modes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.Hex
import com.loopa.core.model.ModeState
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LocalReducedMotion
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.feature.game.modes.KrArt.crest
import com.loopa.feature.game.modes.KrArt.prism
import com.loopa.feature.game.modes.KrArt.resourceIcon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Krallıklar panels in the LOOPA Pastel Party language: light, airy surfaces with lavender, mint,
 * peach, butter and sky accents. Text colours are the deep variants so small labels keep contrast.
 * The dark theme uses LOOPA's soft plum night instead of navy.
 */
@Immutable
private data class KrPalette(
    val bg: Color, val card: Color, val cardHigh: Color, val rim: Color, val ink: Color, val soft: Color,
    val accent: Color, val accentText: Color, val warm: Color, val warmText: Color,
    val good: Color, val goodText: Color, val bad: Color, val badText: Color, val dark: Boolean,
)
private val LightKr = KrPalette(
    bg = Color(0xFFF7F3FC), card = Color.White, cardHigh = Color(0xFFF1ECFA), rim = Color(0xFFE6DFF2),
    ink = LoopaPalette.Ink, soft = Color(0xFF6E6880),
    accent = LoopaPalette.Lavender, accentText = Color(0xFF6A51BE), warm = LoopaPalette.Butter, warmText = Color(0xFF94650A),
    good = LoopaPalette.Mint, goodText = Color(0xFF2E8462), bad = LoopaPalette.Peach, badText = Color(0xFFC0473E), dark = false,
)
private val DarkKr = KrPalette(
    bg = LoopaPalette.Night, card = LoopaPalette.NightCard, cardHigh = LoopaPalette.NightCardHigh, rim = Color(0xFF433D59),
    ink = LoopaPalette.NightText, soft = LoopaPalette.NightTextSoft,
    accent = LoopaPalette.Lavender, accentText = LoopaPalette.Lavender, warm = LoopaPalette.Butter, warmText = LoopaPalette.Butter,
    good = LoopaPalette.Mint, goodText = LoopaPalette.Mint, bad = LoopaPalette.Peach, badText = Color(0xFFFFA594), dark = true,
)
private val LocalKr = staticCompositionLocalOf { LightKr }
private val Panel: Color @Composable get() = LocalKr.current.bg
private val PanelHigh: Color @Composable get() = LocalKr.current.card
private val Tint: Color @Composable get() = LocalKr.current.cardHigh
private val Rim: Color @Composable get() = LocalKr.current.rim
private val Cream: Color @Composable get() = LocalKr.current.ink
private val Soft: Color @Composable get() = LocalKr.current.soft
private val Amber: Color @Composable get() = LocalKr.current.accentText
private val Warm: Color @Composable get() = LocalKr.current.warmText
private val Good: Color @Composable get() = LocalKr.current.goodText
private val Bad: Color @Composable get() = LocalKr.current.badText
private val InkOn = LoopaPalette.Ink
private val CardShape = RoundedCornerShape(20.dp)

/** Soft card: white surface, hairline rim, a faint lavender shadow (the LOOPA card language). */
@Composable
private fun Modifier.krCard(shape: androidx.compose.ui.graphics.Shape = CardShape, color: Color = PanelHigh, rim: Color = Rim): Modifier =
    this.shadow(if (LocalKr.current.dark) 0.dp else 3.dp, shape, ambientColor = LoopaPalette.Lavender.copy(alpha = 0.3f), spotColor = LoopaPalette.Lavender.copy(alpha = 0.3f))
        .clip(shape).background(color).border(1.dp, rim, shape)

internal fun terrainName(t: String?): String = KrArt.terrain(t).name
internal fun buildingName(b: String?): String = when (b) { "barracks" -> "Kışla"; "market" -> "Pazar"; "farm" -> "Çiftlik"; else -> "—" }
private val resNames = listOf("wood" to "Odun", "iron" to "Demir", "gold" to "Altın", "food" to "Yiyecek")
private fun resName(k: String) = resNames.firstOrNull { it.first == k }?.second ?: k

private data class Move(val from: Int, val to: Int, val n: Int)
private data class Build(val tile: Int, val type: String)

/**
 * Krallıklar: a stylized isometric strategy screen. The server's hex grid, fog of war, orders,
 * letters and offers are unchanged; this only draws and sends the same requests.
 */
@Composable
fun KralliklarScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val me = mode.me
    val over = mode.over
    val turnNo = (p / "turn_no").i() ?: 1
    val map = (p / "map").a().map { it.ints() }
    val kingdoms = (p / "kingdoms").a()
    val myK = (me / "k").i()
    val alive = (me / "alive").b()
    val canAct = alive && !over && !ctx.isSpectator && myK != null
    val tiles: Map<Int, KrTile> = remember(mode.round?.public, me) {
        val src = if (over) (p / "tiles").a().mapIndexed { i, t -> i to t } else (me / "tiles").a().map { ((it / "i").i() ?: 0) to it }
        // capitals are flagged per visible tile by the server ("cap")
        src.associate { (i, t) -> i to KrTile(i, (t / "t").s(), (t / "o").i() ?: 0, (t / "x").i() ?: 0, (t / "b").s(), (t / "l").i() ?: 0, (t / "cap").b()) }
    }
    val kColor: (Int?) -> Color = { k -> kingdoms.firstOrNull { (it / "k").i() == k }?.let { parseColor((it / "color").s()) } ?: Color(0xFF8A8F99) }
    val kName: (Int?) -> String = { k -> kingdoms.firstOrNull { (it / "k").i() == k }?.let { (it / "name").s() } ?: "Tarafsız" }
    val proj = remember(map) { KrProjection(map) }
    val cam = remember(map.size) { KrCamera().apply { zoom = 3.2f; focused = (me / "capital").i() } }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var sending by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Int?>(null) }
    val moves = remember(turnNo) { mutableStateListOf<Move>().apply { addAll((me / "orders" / "moves").a().map { Move((it / "from").i() ?: 0, (it / "to").i() ?: 0, (it / "n").i() ?: 1) }) } }
    val builds = remember(turnNo) { mutableStateListOf<Build>().apply { addAll((me / "orders" / "builds").a().map { Build((it / "tile").i() ?: 0, (it / "type").s().orEmpty()) }) } }
    val serverMoves = (me / "orders" / "moves").a().map { Move((it / "from").i() ?: 0, (it / "to").i() ?: 0, (it / "n").i() ?: 1) }
    val serverBuilds = (me / "orders" / "builds").a().map { Build((it / "tile").i() ?: 0, (it / "type").s().orEmpty()) }
    val synced = moves.toList() == serverMoves && builds.toList() == serverBuilds
    val battles = remember(me, turnNo) {
        (me / "events").a().filter { (it / "e").s() == "battle" && (it / "turn").i() == turnNo - 1 }.mapNotNull { (it / "tile").i() }.toSet()
    }
    val routes = moves.map { KrRoute(it.from, it.to, it.n, planned = true) } +
        (me / "armies").a().map { a -> KrRoute((a / "from").i() ?: 0, (a / "to").i() ?: 0, (a / "n").i() ?: 0, planned = false, label = "${(a / "arrive").i()}. tur") }
    val incoming = (me / "offers").a().count { (it / "to").i() == myK && (it / "status").s() == "pending" }

    fun select(i: Int) {
        if (sending && selected != null && i != selected) { target = i; return }
        selected = i; sending = false; target = null
    }

    CompositionLocalProvider(LocalKr provides if (Loopa.colors.isDark) DarkKr else LightKr) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Panel)) {
        val compact = maxHeight < 640.dp
        Column(Modifier.fillMaxSize()) {
            KingdomHeader(ctx, kingdoms, myK, turnNo, me / "res", over, alive, kName, compact)
            Tabs(tab, incoming) { tab = it }
            when (tab) {
                0 -> {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        KrIsoMap(proj, cam, tiles, { kColor(it) }, myK, selected, target, routes, battles, onTap = ::select, modifier = Modifier.fillMaxSize(), dark = LocalKr.current.dark)
                        MapControls(cam, onCapital = { (me / "capital").i()?.let { c -> proj.focus(c, cam.size, cam); select(c) } }, Modifier.align(Alignment.CenterEnd))
                        if (sending && target == null) Hint("Hedef bölgeye dokun", Modifier.align(Alignment.TopCenter))
                        TurnBanner(turnNo, Modifier.align(Alignment.Center))
                        if (over) Hint((p / "winner").i()?.let { if (it == myK) "Krallığın ayakta kalan son krallık!" else "${kName(it)} kazandı" } ?: "Oyun bitti",
                            Modifier.align(Alignment.TopCenter))
                        else if (!alive && myK != null) Hint("Krallığın düştü — izlemeye devam edebilirsin", Modifier.align(Alignment.TopCenter))
                    }
                    Dock(selected, tiles, map, proj, me, myK, canAct, sending, target, moves, builds, kName, kColor, compact,
                        onClose = { selected = null; sending = false; target = null },
                        onSend = { sending = true; target = null },
                        onPickTarget = { target = it },
                        onAddMove = { mv -> moves.add(mv); sending = false; target = null },
                        onBuild = { b -> builds.removeAll { it.tile == b.tile }; builds.add(b) },
                        onFocus = { i -> proj.focus(i, cam.size, cam); select(i) })
                    if (canAct) OrdersBar(moves, builds, synced, (me / "ready").b(), ui, tiles, kingdoms,
                        onFocus = { i -> proj.focus(i, cam.size, cam); selected = i; sending = false })
                }
                1 -> Diplomacy(ctx, p, me, myK, kingdoms, kName, kColor, canAct, turnNo, ui)
                2 -> Journal(me, p, myK, kName, kColor, map) { i -> tab = 0; cam.focused = null; proj.focus(i, cam.size, cam); select(i) }
            }
        }
    }
    }
}

// ====================================================================== header
@Composable
private fun KingdomHeader(ctx: ModeContext, kingdoms: List<JsonElement>, myK: Int?, turnNo: Int, res: JsonElement?, over: Boolean, alive: Boolean, kName: (Int?) -> String,
                          compact: Boolean) {
    val mine = kingdoms.firstOrNull { (it / "k").i() == myK }
    val color = parseColor((mine / "color").s())
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(LoopaPalette.Lavender.copy(alpha = if (LocalKr.current.dark) 0.14f else 0.22f), Panel))).padding(horizontal = 14.dp, vertical = if (compact) 4.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(if (compact) 24.dp else 34.dp, if (compact) 28.dp else 40.dp).testTag("kr_crest")) { crest(Offset(size.width * 0.04f, 0f), size.width * 0.92f, color, myK ?: 1) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (myK != null) kName(myK) else "İzleyici", color = Cream, fontWeight = FontWeight.Black, fontSize = if (compact) 15.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("kr_kingdom_name"))
                Text(buildString { append("Tur $turnNo"); if (!alive && myK != null) append(" · düştü"); if (over) append(" · oyun bitti") }, color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            // who is ready: a crest per living kingdom with a check
            val living = kingdoms.filter { (it / "alive").b() }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("kr_ready_strip")) {
                // many kingdoms: a few crests (ready ones first) and a counter, so the name keeps its room
                val shown = if (living.size <= 6) living else living.sortedByDescending { (it / "ready").b() }.take(4)
                if (living.size > 6) Text("${living.count { (it / "ready").b() }}/${living.size} hazır", color = Soft, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 4.dp))
                shown.forEach { k ->
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Canvas(Modifier.size(18.dp, 21.dp)) { crest(Offset.Zero, size.width, parseColor((k / "color").s()), (k / "k").i() ?: 1) }
                        if ((k / "ready").b()) Box(Modifier.size(11.dp).clip(CircleShape).background(Color.White).padding(1.dp).clip(CircleShape).background(LoopaPalette.MintDeep), contentAlignment = Alignment.Center) {
                            Text("✓", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        if (myK != null) ResourceBar(res, turnNo, compact)
    }
}

/** Four resources with drawn icons; the change since last turn under each value. */
@Composable
private fun ResourceBar(res: JsonElement?, turnNo: Int, compact: Boolean) {
    val lastByTurn = remember { mutableStateMapOf<Int, Map<String, Int>>() }
    val now = resNames.associate { (k, _) -> k to ((res / k).i() ?: 0) }
    LaunchedEffect(turnNo, now) { lastByTurn[turnNo] = now }
    val prev = lastByTurn[turnNo - 1]
    Row(Modifier.fillMaxWidth().padding(top = if (compact) 3.dp else 6.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        resNames.forEach { (k, n) ->
            val v = now[k] ?: 0
            val shown by animateIntAsState(v, if (LocalReducedMotion.current) tween(0) else tween(600), label = k)
            val d = prev?.get(k)?.let { v - it }
            val tint = when (k) { "wood" -> LoopaPalette.Peach; "iron" -> LoopaPalette.Sky; "gold" -> LoopaPalette.Butter; else -> LoopaPalette.Mint }
            Row(Modifier.weight(1f).krCard(RoundedCornerShape(16.dp), rim = tint.copy(alpha = 0.6f))
                .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = if (compact) 2.dp else 4.dp).testTag("kr_res_$k"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (compact) 18.dp else 26.dp).clip(CircleShape).background(tint.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(if (compact) 13.dp else 18.dp)) { resourceIcon(k, Offset.Zero, size.minDimension) }
                }
                Spacer(Modifier.width(3.dp))
                val dText = if (d == null || d == 0) null else (if (d > 0) "+$d" else "$d")
                val dColor = when { d == null || d == 0 -> Soft; d > 0 -> Good; else -> Bad }
                if (compact) {
                    Text("$shown", color = Cream, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    dText?.let { Text(" $it", color = dColor, fontSize = 9.sp, maxLines = 1) }
                } else Column {
                    Text("$shown", color = Cream, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
                    Text(dText ?: n, color = dColor, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Tabs(tab: Int, incoming: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).background(Tint).padding(3.dp)) {
        listOf("Harita", "Diplomasi", "Raporlar").forEachIndexed { i, t ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (tab == i) LoopaPalette.Lavender else Color.Transparent).clickable { onTab(i) }
                .padding(vertical = 6.dp).testTag("kr_tab_$i"), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t, color = if (tab == i) InkOn else Soft, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    if (i == 1 && incoming > 0) Box(Modifier.padding(start = 5.dp).size(16.dp).clip(CircleShape).background(LoopaPalette.PeachDeep), contentAlignment = Alignment.Center) {
                        Text("$incoming", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

// ====================================================================== map overlays
@Composable
private fun MapControls(cam: KrCamera, onCapital: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundBtn("+", "kr_zoom_in") { cam.zoomBy(1.3f) }
        RoundBtn("−", "kr_zoom_out") { cam.zoomBy(1 / 1.3f) }
        RoundBtn("⌂", "kr_to_capital", onCapital)
    }
}

@Composable
private fun RoundBtn(label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).krCard(CircleShape, PanelHigh.copy(alpha = 0.94f)).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center) { Text(label, color = Amber, fontSize = 19.sp, fontWeight = FontWeight.Black) }
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(text, color = InkOn, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = modifier.padding(top = 10.dp).krCard(RoundedCornerShape(50), LoopaPalette.Butter, LoopaPalette.ButterDeep.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 7.dp).testTag("kr_hint"))
}

/** "TUR 8" for a moment when a new turn starts (not with reduced motion). */
@Composable
private fun TurnBanner(turnNo: Int, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    var shownFor by remember { mutableIntStateOf(turnNo) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(turnNo) {
        if (turnNo != shownFor && !reduced) { shownFor = turnNo; visible = true; kotlinx.coroutines.delay(1_400); visible = false } else shownFor = turnNo
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut()) {
        Text("TUR $turnNo", color = InkOn, fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = 4.sp,
            modifier = Modifier.krCard(RoundedCornerShape(24.dp), Color.White.copy(alpha = 0.92f), LoopaPalette.Lavender).padding(horizontal = 28.dp, vertical = 10.dp).testTag("kr_turn_banner"))
    }
}

// ====================================================================== selected tile dock
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dock(selected: Int?, tiles: Map<Int, KrTile>, map: List<List<Int>>, proj: KrProjection, me: JsonElement?, myK: Int?, canAct: Boolean,
                 sending: Boolean, target: Int?, moves: List<Move>, builds: List<Build>, kName: (Int?) -> String, kColor: (Int?) -> Color, compact: Boolean,
                 onClose: () -> Unit, onSend: () -> Unit, onPickTarget: (Int) -> Unit, onAddMove: (Move) -> Unit, onBuild: (Build) -> Unit, onFocus: (Int) -> Unit) {
    if (selected == null) {
        if (!compact) Text("Bir bölgeye dokun: arazi, birlik ve eylemler burada görünür.", color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 6.dp).testTag("kr_dock_hint"))
        return
    }
    val t = tiles[selected]
    val qr = map.getOrNull(selected)
    val bal = me / "balance"
    Column(Modifier.fillMaxWidth().krCard(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        .heightIn(max = if (compact) 190.dp else 300.dp).verticalScroll(rememberScrollState()).padding(if (compact) 8.dp else 12.dp).animateContentSize().testTag("kr_tile_info"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(52.dp, 44.dp)) {
                val s = size.width * 0.42f; val c = Offset(size.width / 2, size.height * 0.42f)
                if (t == null) prism(c, s, 0.12f, KrArt.FogTop, KrArt.FogSide)
                else { val tr = KrArt.terrain(t.t); prism(c, s, tr.height, tr.top, tr.side, if (t.owner > 0) kColor(t.owner).copy(alpha = 0.08f) else null, band = if (t.owner > 0) kColor(t.owner) else null) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (t == null) "Sis" else (if (t.capital) "Başkent · " else "") + terrainName(t.t), color = Cream, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("Bölge #$selected · (${qr?.getOrNull(0)}, ${qr?.getOrNull(1)})", color = Soft, fontSize = 11.sp)
            }
            if (t != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.owner > 0) Canvas(Modifier.size(16.dp, 19.dp)) { crest(Offset.Zero, size.width, kColor(t.owner), t.owner) }
                Spacer(Modifier.width(4.dp))
                Text(if (t.owner == myK) "Senin" else kName(t.owner.takeIf { it > 0 }), color = if (t.owner > 0) kColor(t.owner) else Soft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            TextButton(onClose, modifier = Modifier.testTag("kr_dock_close")) { Text("✕", color = Soft) }
        }
        if (t == null) { Text("Bu bölge görüş alanının dışında. Krallığına komşu olan bölgeler görünür.", color = Soft, fontSize = 12.sp); return@Column }
        // facts
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Fact("troops", "${t.troops} asker")
            if (t.building != null) Fact(null, buildingName(t.building))
            (bal / "resource" / (t.t ?: "")).takeIf { it != null && t.t != "plain" }?.let { r ->
                val k = (r / "res").s().orEmpty()
                Fact(k, if (t.life > 0) "+${(r / "amount").i()} ${resName(k)}/tur · ${t.life} tur" else "${resName(k)} tükendi")
            }
            val def = ((bal / "defense_bonus" / (t.t ?: "")).d() ?: 0.0) + if (t.capital) ((bal / "capital_bonus").d() ?: 0.0) else 0.0
            if (def > 0) Fact(null, "+%${(def * 100).toInt()} savunma")
        }
        // actions (only valid ones)
        val mine = canAct && t.owner == myK
        val queuedOut = moves.filter { it.from == selected }.sumOf { it.n }
        val available = t.troops - queuedOut
        if (mine && !sending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (available > 0) Action("Asker gönder", LoopaPalette.Lavender, "kr_send", onClick = onSend)
            }
            if (t.building == null && !t.capital && builds.none { it.tile == selected }) {
                val stock = resNames.associate { (k, _) -> k to ((me / "res" / k).i() ?: 0) }.toMutableMap()
                builds.forEach { b -> (bal / "buildings" / b.type / "cost").o().forEach { (k, v) -> stock[k] = (stock[k] ?: 0) - (v.i() ?: 0) } }
                val options = listOf("barracks", "market", "farm").filter { type ->
                    (bal / "buildings" / type / "cost").o().all { (k, v) -> (stock[k] ?: 0) >= (v.i() ?: 0) }
                }
                if (options.isNotEmpty()) {
                    Text("İnşa et", color = Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { type -> BuildOption(type, bal / "buildings" / type) { onBuild(Build(selected, type)) } }
                    }
                } else Text("İnşa için kaynak yetersiz.", color = Soft, fontSize = 11.sp)
            }
            if (builds.any { it.tile == selected }) Text("Bu bölge için inşa emri verdin.", color = Good, fontSize = 11.sp)
        }
        if (mine && sending) MoveComposer(selected, target, available, map, tiles, me, kName, onPickTarget, onAddMove)
        if (!mine && canAct && t.owner > 0 && t.owner != myK) Text("Bu bölgeye asker göndermek için kendi bölgelerinden birini seç.", color = Soft, fontSize = 11.sp)
    }
}

@Composable
private fun Fact(icon: String?, text: String) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Tint).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Canvas(Modifier.size(16.dp)) { resourceIcon(icon, Offset.Zero, size.minDimension) }; Spacer(Modifier.width(4.dp)) }
        Text(text, color = Cream, fontSize = 12.sp)
    }
}

/** Primary action: the shared LOOPA button (pastel fill, dark ink, pressed depth). */
@Composable
private fun Action(text: String, color: Color, tag: String, content: Color = InkOn, onClick: () -> Unit) {
    LoopaButton(text, onClick, Modifier.testTag(tag), color = color, contentColor = content, compact = true)
}

@Composable
private fun InfoPill(text: String) {
    Text(text, color = Cream, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Tint).padding(horizontal = 12.dp, vertical = 5.dp))
}

private fun offerHue(t: String?) = when (t) {
    "nap" -> LoopaPalette.Sky; "alliance" -> LoopaPalette.Mint; "trade" -> LoopaPalette.Butter
    "joint_enemy" -> LoopaPalette.Peach; else -> LoopaPalette.Lavender
}

@Composable
private fun BuildOption(type: String, def: JsonElement?, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).krCard(RoundedCornerShape(18.dp), Tint).clickable(onClick = onClick)
        .padding(8.dp).testTag("kr_build_$type"), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(44.dp, 36.dp)) {
            val c = Offset(size.width / 2, size.height * 0.75f); val s = size.width * 0.6f
            when (type) { "barracks" -> with(KrArt) { barracks(c, s, LoopaPalette.LavenderDeep) }; "market" -> with(KrArt) { market(c, s) }; else -> with(KrArt) { barn(c, s) } }
        }
        Text(buildingName(type), color = Cream, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(when (type) {
            "barracks" -> "+${(def / "troops").i()} asker/tur"
            "market" -> "+${(def / "gold").i()} altın/tur"
            else -> "+${(def / "food").i()} yiyecek/tur"
        }, color = Good, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            (def / "cost").o().forEach { (k, v) ->
                Canvas(Modifier.size(12.dp)) { resourceIcon(k, Offset.Zero, size.minDimension) }
                Text("${v.i()}", color = Soft, fontSize = 10.sp)
            }
        }
    }
}

/** Pick a target (tap the map, or a neighbour below), the number of soldiers, then add the order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoveComposer(from: Int, target: Int?, available: Int, map: List<List<Int>>, tiles: Map<Int, KrTile>, me: JsonElement?, kName: (Int?) -> String,
                         onPickTarget: (Int) -> Unit, onAdd: (Move) -> Unit) {
    val (q, r) = map[from].let { it[0] to it[1] }
    val dirs = listOf(Triple(1, 0, "Doğu"), Triple(1, -1, "Kuzeydoğu"), Triple(0, -1, "Kuzeybatı"), Triple(-1, 0, "Batı"), Triple(-1, 1, "Güneybatı"), Triple(0, 1, "Güneydoğu"))
    val index = remember(map) { map.mapIndexed { i, c -> (c[0] to c[1]) to i }.toMap() }
    Text("Hedefe dokun ya da bir komşu seç", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        dirs.forEach { (dq, dr, name) ->
            val i = index[q + dq to r + dr] ?: return@forEach
            val t = tiles[i]
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (target == i) LoopaPalette.Peach else Tint)
                .border(1.dp, if (target == i) LoopaPalette.PeachDeep else Rim, RoundedCornerShape(50))
                .clickable { onPickTarget(i) }.padding(horizontal = 10.dp, vertical = 6.dp).testTag("kr_neighbour_$i")) {
                Text("$name · ${t?.let { terrainName(it.t) } ?: "Sis"}", color = if (target == i) InkOn else Cream, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (target != null && available >= 1) {
        var count by remember(from, target) { mutableFloatStateOf(available.toFloat()) }
        val d = map.getOrNull(target)?.let { Hex.distance(q, r, it[0], it[1]) } ?: 1
        val speed = (me / "balance" / "speed").i() ?: 2
        val turns = Hex.turnsToArrive(d, speed)
        Text("${count.toInt()} asker → ${tiles[target]?.let { terrainName(it.t) + if (it.owner > 0) " (${kName(it.owner)})" else "" } ?: "sisli bölge"} · mesafe $d · " +
            if (turns == 1) "bu tur sonunda varır" else "$turns tur sonunda varır", color = Cream, fontSize = 12.sp, modifier = Modifier.testTag("kr_move_summary"))
        if (available > 1) Slider(count, { count = it }, valueRange = 1f..available.toFloat(), steps = (available - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = LoopaPalette.LavenderDeep, activeTrackColor = LoopaPalette.Lavender, inactiveTrackColor = Tint))
        Action("Emre ekle", LoopaPalette.Mint, "kr_add_move") { onAdd(Move(from, target, count.toInt())) }
    }
}

// ====================================================================== orders
@Composable
private fun OrdersBar(moves: MutableList<Move>, builds: MutableList<Build>, synced: Boolean, ready: Boolean, ui: ModeUi, tiles: Map<Int, KrTile>,
                      kingdoms: List<JsonElement>, onFocus: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val n = moves.size + builds.size
    Column(Modifier.fillMaxWidth().background(PanelHigh).border(1.dp, Rim, RoundedCornerShape(topStart = 0.dp)).padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize().testTag("kr_orders")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp).testTag("kr_orders_toggle"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "▾" else "▸", color = Cream, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text("Emirler ($n)", color = Cream, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                if (n > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (synced) LoopaPalette.MintDeep else LoopaPalette.PeachDeep))
                    Text(if (synced) " kaydedildi" else " gönderilmedi", color = if (synced) Good else Bad, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            BarButton(if (synced) "Gönderildi ✓" else "Emirleri gönder", if (synced) Tint else LoopaPalette.Mint, "kr_submit", enabled = ui.busy == null && !synced) {
                ui.act("orders", payload(
                    "moves" to jsonList(moves.map { payload("from" to it.from, "to" to it.to, "n" to it.n) }),
                    "builds" to jsonList(builds.map { payload("tile" to it.tile, "type" to it.type) }))) { ui.flash("Emirler kaydedildi") }
            }
            Spacer(Modifier.width(6.dp))
            BarButton(if (ready) "Hazır ✓" else "Hazırım", if (ready) LoopaPalette.Butter else LoopaPalette.Lavender, "kr_ready", enabled = ui.busy == null) {
                ui.act("ready", payload("ready" to !ready)) {}
            }
        }
        if (open) {
            if (n == 0) Text("Bu tur için emir yok. Kendi bölgene dokun → Asker gönder ya da İnşa et.", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                moves.toList().forEachIndexed { k, m ->
                    OrderCard("⚔", "${m.n} asker", "#${m.from} ${terrainName(tiles[m.from]?.t)} → #${m.to} ${tiles[m.to]?.let { terrainName(it.t) } ?: "sis"}",
                        "kr_order_move_$k", onFocus = { onFocus(m.to) }) { moves.removeAt(k) }
                }
                builds.toList().forEachIndexed { k, b ->
                    OrderCard("🔨", buildingName(b.type), "#${b.tile} ${terrainName(tiles[b.tile]?.t)}", "kr_order_build_$k", onFocus = { onFocus(b.tile) }) { builds.removeAt(k) }
                }
            }
            Text("Tur 60 saniye; herkes hazır olunca erken biter. Savaşlar tur sonunda sunucuda çözülür.", color = Soft, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Compact pill button in the LOOPA style for the orders bar (fits narrow phones). */
@Composable
private fun BarButton(text: String, color: Color, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.shadow(if (enabled && !LocalKr.current.dark) 3.dp else 0.dp, shape, ambientColor = com.loopa.core.ui.components.darken(color), spotColor = com.loopa.core.ui.components.darken(color))
        .clip(shape).background(color).border(1.5.dp, com.loopa.core.ui.components.darken(color).copy(alpha = 0.35f), shape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, color = if (color == Tint) Soft else InkOn, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun OrderCard(icon: String, title: String, sub: String, tag: String, onFocus: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tint).clickable(onClick = onFocus)
        .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Cream, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(sub, color = Soft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(30.dp).clip(CircleShape).background(LoopaPalette.Peach.copy(alpha = 0.45f)).clickable(onClick = onDelete).testTag("${tag}_delete"), contentAlignment = Alignment.Center) {
            Text("✕", color = Bad, fontWeight = FontWeight.Black)
        }
    }
}

// ====================================================================== diplomacy
private fun offerName(t: String?) = when (t) { "nap" -> "Saldırmazlık"; "alliance" -> "İttifak"; "trade" -> "Ticaret"; "joint_enemy" -> "Ortak düşman"; "peace" -> "Barış"; else -> t.orEmpty() }
private fun offerIcon(t: String?) = when (t) { "nap" -> "🕊"; "alliance" -> "🤝"; "trade" -> "⚖"; "joint_enemy" -> "🎯"; "peace" -> "🏳"; else -> "📜" }
private fun offerAbout(t: String) = when (t) {
    "nap" -> "Belirli bir süre birbirinize saldırmama sözü. Bozan herkese duyurulur."
    "alliance" -> "Görüşünüzü paylaşırsınız. En fazla 3 krallık; müttefikine saldıran ittifaktan çıkar."
    "trade" -> "İki krallık arasında kaynak takası. Kaynak yetmezse gerçekleşmez."
    "joint_enemy" -> "Üçüncü bir krallığa karşı belirli bir süre birlikte hareket etme anlaşması."
    else -> "Aranızdaki çatışmaya son veren, belirli süreli barış anlaşması."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Diplomacy(ctx: ModeContext, p: JsonObject, me: JsonElement?, myK: Int?, kingdoms: List<JsonElement>, kName: (Int?) -> String, kColor: (Int?) -> Color,
                      canAct: Boolean, turnNo: Int, ui: ModeUi) {
    var letterTo by remember { mutableStateOf<Int?>(null) }
    var offerTo by remember { mutableStateOf<Int?>(null) }
    val allies = (me / "allies").ints()
    val offers = (me / "offers").a()
    val pacts = (me / "pacts").a()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canAct && turnNo == 1) RenameBox(ui, kingdoms, myK)
        val incoming = offers.filter { (it / "to").i() == myK && (it / "status").s() == "pending" }
        if (incoming.isNotEmpty()) {
            SectionLabel("Gelen teklifler")
            incoming.forEach { o -> OfferCard(o, myK, kName, kColor, canAct, ui) }
        }
        SectionLabel("Krallıklar")
        kingdoms.forEach { k ->
            val id = (k / "k").i() ?: 0
            val isMe = id == myK
            val dead = !(k / "alive").b()
            val rel = buildList {
                if (dead) add("Düştü" to Bad)
                if (id in allies) add("🤝 Müttefik" to Good)
                pacts.filter { (it / "a").i() == id || (it / "b").i() == id }.forEach { pc ->
                    add("${offerIcon((pc / "type").s())} ${offerName((pc / "type").s())}${(pc / "target").i()?.let { " · ${kName(it)}" } ?: ""} · ${(pc / "until").i()}. tura kadar" to Amber)
                }
                if (!isMe && !dead && isEmpty()) add("Nötr" to Soft)
            }
            Row(Modifier.fillMaxWidth().krCard(CardShape, rim = if (isMe) kColor(id).copy(alpha = 0.7f) else Rim)
                .background(Brush.horizontalGradient(listOf(kColor(id).copy(alpha = 0.12f), Color.Transparent)))
                .padding(12.dp).testTag("kr_kingdom_$id"), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(36.dp, 42.dp)) { crest(Offset.Zero, size.width, kColor(id), id) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(kName(id) + if (isMe) " (sen)" else "", color = Cream, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    Text(ctx.name((k / "user").s()) + if ((k / "ready").b() && !dead) " · hazır" else "", color = Soft, fontSize = 11.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                        rel.forEach { (t, c) -> Text(t, color = c, fontSize = 10.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp)) }
                    }
                }
                if (canAct && !isMe && !dead) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallBtn("✉ Mektup", "kr_letter_$id") { letterTo = if (letterTo == id) null else id }
                    SmallBtn("📜 Teklif", "kr_offer_$id") { offerTo = id }
                }
                if (canAct && isMe && allies.isNotEmpty()) SmallBtn("İttifaktan ayrıl", "kr_leave_alliance") { ui.act("leave_alliance", payload()) {} }
            }
            if (letterTo == id) LetterComposer(kName(id), kColor(id), id, onDismiss = { letterTo = null }) { subj, body ->
                letterTo = null; ui.act("letter", payload("to" to jsonInts(listOf(id)), "subject" to subj, "body" to body)) { ui.flash("Mektup gönderildi") } }
        }
        val outgoing = offers.filter { (it / "from").i() == myK }
        if (outgoing.isNotEmpty()) {
            SectionLabel("Gönderdiğin teklifler")
            outgoing.forEach { o -> OfferCard(o, myK, kName, kColor, canAct, ui) }
        }
        SectionLabel("Mektuplar")
        val inbox = (me / "inbox").a()
        if (inbox.isEmpty()) Text("Henüz mektup yok.", color = Soft, fontSize = 12.sp)
        inbox.reversed().forEach { l -> Letter(l, kName, kColor, incoming = true) }
        (me / "sent").a().takeIf { it.isNotEmpty() }?.let { sent ->
            var showSent by remember { mutableStateOf(false) }
            Text(if (showSent) "▾ Gönderilenler (${sent.size})" else "▸ Gönderilenler (${sent.size})", color = Soft, fontSize = 12.sp,
                modifier = Modifier.clickable { showSent = !showSent }.padding(vertical = 4.dp))
            if (showSent) sent.reversed().forEach { l -> Letter(l, kName, kColor, incoming = false) }
        }
    }
    offerTo?.let { to ->
        OfferDialog(to, kingdoms.mapNotNull { (it / "k").i() }.filter { it != to && it != myK && kingdoms.first { k -> (k / "k").i() == it }.let { k -> (k / "alive").b() } },
            kName, kColor, me / "res", onDismiss = { offerTo = null }) { pl -> offerTo = null; ui.act("offer", payload(*(listOf("to" to to) + pl).toTypedArray())) { ui.flash("Teklif gönderildi") } }
    }
}

@Composable
private fun SectionLabel(t: String) { Text(t.uppercase(), color = Amber, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.5.sp) }

@Composable
private fun SmallBtn(text: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Tint).border(1.dp, Rim, RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp).testTag(tag)) { Text(text, color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Black) }
}

private fun statusName(s: String?) = when (s) { "pending" -> "bekliyor"; "accepted" -> "kabul edildi"; "rejected" -> "reddedildi"; "failed" -> "gerçekleşmedi"; "cancelled" -> "geri çekildi"; else -> s.orEmpty() }

@Composable
private fun OfferCard(o: JsonElement, myK: Int?, kName: (Int?) -> String, kColor: (Int?) -> Color, canAct: Boolean, ui: ModeUi) {
    val incoming = (o / "to").i() == myK
    val other = if (incoming) (o / "from").i() else (o / "to").i()
    val pending = (o / "status").s() == "pending"
    val type = (o / "type").s()
    val hue = offerHue(type)
    Column(Modifier.fillMaxWidth().krCard(CardShape, rim = if (incoming && pending) hue else Rim)
        .background(Brush.verticalGradient(listOf(hue.copy(alpha = if (incoming && pending) 0.26f else 0.12f), Color.Transparent)))
        .padding(14.dp).testTag("kr_offer_card_${(o / "id").i()}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(hue.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) { Text(offerIcon(type), fontSize = 18.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(offerName(type), color = Cream, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(if (incoming) "${kName(other)} sana teklif ediyor" else "${kName(other)} krallığına · ${statusName((o / "status").s())}", color = Soft, fontSize = 11.sp)
            }
            Canvas(Modifier.size(26.dp, 31.dp)) { crest(Offset.Zero, size.width, kColor(other), other ?: 1) }
        }
        when (type) {
            "trade" -> {
                // what each side gives, side by side; seen from this player
                val theyGive = if (incoming) o / "give" else o / "get"
                val iGive = if (incoming) o / "get" else o / "give"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TradeSide("Sen alırsın", theyGive, LoopaPalette.Mint, Good, Modifier.weight(1f))
                    Text("⇄", color = Soft, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    TradeSide("Sen verirsin", iGive, LoopaPalette.Peach, Bad, Modifier.weight(1f))
                }
            }
            "nap", "peace" -> InfoPill("⏳ ${(o / "turns").i()} tur sürer")
            "joint_enemy" -> InfoPill("🎯 ${kName((o / "target").i())} · ${(o / "turns").i()} tur")
            else -> Text(offerAbout(type ?: ""), color = Soft, fontSize = 11.sp)
        }
        if (canAct && pending) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (incoming) {
                Action("Kabul et", LoopaPalette.Mint, "kr_accept") { ui.act("respond", payload("id" to (o / "id").i(), "accept" to true)) {} }
                Action("Reddet", if (LocalKr.current.dark) LoopaPalette.NightCardHigh else Color.White, "kr_reject", content = Bad) {
                    ui.act("respond", payload("id" to (o / "id").i(), "accept" to false)) {}
                }
            } else SmallBtn("Geri çek", "kr_cancel_offer") { ui.act("cancel_offer", payload("id" to (o / "id").i())) {} }
        }
    }
}

@Composable
private fun TradeSide(title: String, bundle: JsonElement?, fill: Color, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(fill.copy(alpha = if (LocalKr.current.dark) 0.16f else 0.3f)).padding(10.dp)) {
        Text(title, color = accent, fontWeight = FontWeight.Black, fontSize = 11.sp)
        val items = bundle.o().entries.filter { (it.value.i() ?: 0) > 0 }
        if (items.isEmpty()) Text("—", color = Soft, fontSize = 12.sp)
        items.forEach { (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Canvas(Modifier.size(16.dp)) { resourceIcon(k, Offset.Zero, size.minDimension) }
                Spacer(Modifier.width(4.dp))
                Text("${v.i()} ${resName(k)}", color = Cream, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Letter(l: JsonElement, kName: (Int?) -> String, kColor: (Int?) -> Color, incoming: Boolean) {
    val who = if (incoming) (l / "from").i() else (l / "to").ints().firstOrNull()
    Column(Modifier.fillMaxWidth().krCard(RoundedCornerShape(18.dp), Color(0xFFFFF8EA), LoopaPalette.Butter).padding(12.dp)
        .testTag("kr_letter_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(kColor(who)))
            Spacer(Modifier.width(6.dp))
            Text((if (incoming) "${kName(who)} → sen" else "sen → ${kName(who)}") + " · tur ${(l / "turn").i()}", color = Color(0xFF7A6A55), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text((l / "subject").s().orEmpty().ifEmpty { "(konusuz)" }, color = InkOn, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Text((l / "body").s().orEmpty(), color = InkOn, fontSize = 13.sp)
    }
}

/** Letter composer: an inline parchment card under the kingdom (stays above the keyboard, unlike a dialog). */
@Composable
private fun LetterComposer(to: String, color: Color, k: Int, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var subj by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val ink = InkOn
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = ink, unfocusedTextColor = ink, focusedBorderColor = LoopaPalette.LavenderDeep,
        unfocusedBorderColor = LoopaPalette.Lavender, focusedLabelColor = LoopaPalette.LavenderDeep, unfocusedLabelColor = Color(0xFF7A6A55), cursorColor = ink,
        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White)
    Column(Modifier.fillMaxWidth().krCard(CardShape, Color(0xFFFFF8EA), color.copy(alpha = 0.7f))
        .padding(12.dp).testTag("kr_letter_composer"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(22.dp, 26.dp)) { crest(Offset.Zero, size.width, color, k) }
            Spacer(Modifier.width(8.dp))
            Text("Mektup: $to", color = ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        OutlinedTextField(subj, { subj = it.take(60) }, label = { Text("Konu") }, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("kr_letter_subject"))
        OutlinedTextField(body, { body = it.take(600) }, label = { Text("Mektup") }, minLines = 3, colors = fieldColors, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("kr_letter_body"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${body.length}/600 · yalnızca alıcı okur", color = Color(0xFF7A6A55), fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onDismiss) { Text("Vazgeç", color = Color(0xFF7A6A55), fontWeight = FontWeight.Bold) }
        }
        LoopaButton("Mührü bas ve gönder", { onSend(subj, body) }, Modifier.fillMaxWidth().testTag("kr_letter_send"), color = LoopaPalette.Lavender, enabled = body.isNotBlank(), compact = true)
    }
}

/** Offer composer: type tiles, duration, target, and a two-column trade with steppers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferDialog(to: Int, others: List<Int>, kName: (Int?) -> String, kColor: (Int?) -> Color, myRes: JsonElement?, onDismiss: () -> Unit,
                        onSend: (List<Pair<String, Any?>>) -> Unit) {
    var type by remember { mutableStateOf("nap") }
    var turns by remember { mutableIntStateOf(5) }
    var target by remember { mutableStateOf(others.firstOrNull()) }
    val give = remember { mutableStateMapOf<String, Int>() }
    val get = remember { mutableStateMapOf<String, Int>() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = PanelHigh, shape = RoundedCornerShape(28.dp),
        title = { Text("Teklif: ${kName(to)}", color = Cream, fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("nap", "alliance", "trade", "joint_enemy", "peace").forEach { t ->
                        Column(Modifier.width(76.dp).clip(RoundedCornerShape(18.dp)).background(if (type == t) offerHue(t) else Tint)
                            .border(if (type == t) 2.dp else 0.dp, if (type == t) com.loopa.core.ui.components.darken(offerHue(t)) else Color.Transparent, RoundedCornerShape(18.dp))
                            .clickable { type = t; turns = if (t == "peace") 10 else 5 }.padding(6.dp).testTag("kr_offer_type_$t"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(offerIcon(t), fontSize = 20.sp)
                            Text(offerName(t), color = if (type == t) InkOn else Cream, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
                Text(offerAbout(type), color = Soft, fontSize = 11.sp)
                if (type in setOf("nap", "peace", "joint_enemy")) Stepper("Süre", "$turns tur", "kr_turns", { turns = (turns - 1).coerceAtLeast(2) }, { turns = (turns + 1).coerceAtMost(30) })
                if (type == "joint_enemy") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { k -> SmallBtn((if (target == k) "● " else "") + kName(k), "kr_target_$k") { target = k } }
                }
                if (type == "trade") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Sen verirsin", color = Bad, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        resNames.forEach { (k, _) -> ResStepper(k, give[k] ?: 0, "kr_give_$k", max = (myRes / k).i() ?: 0) { give[k] = it } }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Karşılığında alırsın", color = Good, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        resNames.forEach { (k, _) -> ResStepper(k, get[k] ?: 0, "kr_get_$k", max = 100_000) { get[k] = it } }
                    }
                }
            }
        },
        confirmButton = {
            val ok = type != "trade" || give.values.any { it > 0 } || get.values.any { it > 0 }
            TextButton({
                val pl = mutableListOf<Pair<String, Any?>>("type" to type)
                if (type in setOf("nap", "peace", "joint_enemy")) pl += "turns" to turns
                if (type == "joint_enemy") pl += "target" to target
                if (type == "trade") {
                    pl += "give" to payload(*resNames.map { (k, _) -> k to (give[k] ?: 0) }.toTypedArray())
                    pl += "get" to payload(*resNames.map { (k, _) -> k to (get[k] ?: 0) }.toTypedArray())
                }
                onSend(pl)
            }, enabled = ok, modifier = Modifier.testTag("kr_offer_send")) { Text("Teklifi gönder", color = Amber, fontWeight = FontWeight.Black, fontSize = 15.sp) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Vazgeç", color = Soft) } })
}

@Composable
private fun Stepper(label: String, value: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Cream, fontSize = 12.sp, modifier = Modifier.weight(1f))
        StepBtn("−", "${tag}_minus", onMinus); Text(value, color = Cream, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp)); StepBtn("+", "${tag}_plus", onPlus)
    }
}

@Composable
private fun ResStepper(k: String, value: Int, tag: String, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Tint).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp)) { resourceIcon(k, Offset.Zero, size.minDimension) }
        StepBtn("−", "${tag}_minus") { onChange((value - 5).coerceAtLeast(0)) }
        Text("$value", color = Cream, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        StepBtn("+", "${tag}_plus") { onChange((value + 5).coerceAtMost(max)) }
    }
}

@Composable
private fun StepBtn(t: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(PanelHigh).border(1.dp, Rim, CircleShape).clickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Text(t, color = Amber, fontWeight = FontWeight.Black)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenameBox(ui: ModeUi, kingdoms: List<JsonElement>, myK: Int?) {
    var name by remember { mutableStateOf(kingdoms.firstOrNull { (it / "k").i() == myK }?.let { (it / "name").s() }.orEmpty()) }
    val palette = listOf("#E53935", "#1E88E5", "#43A047", "#FDD835", "#8E24AA", "#FB8C00", "#00ACC1", "#D81B60", "#6D4C41", "#3949AB",
        "#7CB342", "#F4511E", "#5E35B1", "#00897B", "#C0CA33", "#546E7A", "#FFB300", "#26A69A", "#EC407A", "#8D6E63")
    val taken = kingdoms.filter { (it / "k").i() != myK }.mapNotNull { (it / "color").s() }.toSet()
    var color by remember { mutableStateOf(kingdoms.firstOrNull { (it / "k").i() == myK }?.let { (it / "color").s() }) }
    Column(Modifier.fillMaxWidth().krCard(CardShape, rim = LoopaPalette.Lavender).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(34.dp, 40.dp)) { crest(Offset.Zero, size.width, parseColor(color), myK ?: 1) }
            Spacer(Modifier.width(8.dp))
            Text("Krallığına ad ve renk ver (yalnızca ilk tur)", color = Cream, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Krallık adı") }, modifier = Modifier.fillMaxWidth().testTag("kr_name"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            palette.forEach { c ->
                val free = c !in taken
                Box(Modifier.size(26.dp).clip(CircleShape).background(parseColor(c).copy(alpha = if (free) 1f else 0.2f))
                    .border(if (c == color) 3.dp else 0.dp, if (LocalKr.current.dark) Color.White else LoopaPalette.Ink, CircleShape).clickable(enabled = free) { color = c })
            }
        }
        Action("Kaydet", LoopaPalette.Lavender, "kr_rename_save") { if (name.trim().length >= 2) ui.act("rename", payload("name" to name.trim(), "color" to color)) { ui.flash("Krallık güncellendi") } }
    }
}

// ====================================================================== journal (reports)
private fun lerpC(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

private data class Entry(val turn: Int, val icon: String, val text: String, val tone: Color, val tile: Int?, val big: Boolean)

@Composable
private fun Journal(me: JsonElement?, p: JsonObject, myK: Int?, kName: (Int?) -> String, kColor: (Int?) -> Color, map: List<List<Int>>, onTile: (Int) -> Unit) {
    val entries = remember(me, p) {
        val own = (me / "events").a().mapNotNull { e ->
            val t = (e / "turn").i() ?: 0; val tile = (e / "tile").i()
            when ((e / "e").s()) {
                "battle" -> {
                    val att = (e / "att").i(); val def = (e / "def").i(); val won = (e / "won").b()
                    val iWin = (att == myK && won) || (def == myK && !won)
                    Entry(t, "⚔", "${kName(att)} (${(e / "att_n").i()}) ⟶ ${if (def == 0) "Tarafsız" else kName(def)} (${(e / "def_n").i()}) · " +
                        (if (won) "bölge el değiştirdi" else "savunma tuttu") + " · ${(e / "left").i()} asker kaldı", if (iWin) LoopaPalette.MintDeep else LoopaPalette.PeachDeep, tile, true)
                }
                "arrived" -> Entry(t, "➜", "${(e / "n").i()} asker bölge #$tile'e ulaştı", LoopaPalette.Sky, tile, false)
                "built" -> Entry(t, "🔨", "${buildingName((e / "type").s())} tamamlandı (#$tile)", LoopaPalette.MintDeep, tile, false)
                "build_failed" -> Entry(t, "🔨", "${buildingName((e / "type").s())} yapılamadı: ${if ((e / "why").s() == "RESOURCES") "kaynak yetersiz" else "bölge uygun değil"}", LoopaPalette.PeachDeep, tile, false)
                "no_food" -> Entry(t, "🌾", "Kışla yiyecek yetmediği için asker üretemedi (#$tile)", LoopaPalette.PeachDeep, tile, false)
                "offer" -> Entry(t, "📜", "${kName((e / "from").i())} sana ${offerName((e / "type").s())} teklif etti", LoopaPalette.ButterDeep, null, false)
                "offer_accepted" -> Entry(t, "🤝", "${kName((e / "by").i())} ${offerName((e / "type").s())} teklifini kabul etti", LoopaPalette.MintDeep, null, true)
                "offer_rejected" -> Entry(t, "✗", "${kName((e / "by").i())} ${offerName((e / "type").s())} teklifini reddetti", LoopaPalette.PeachDeep, null, false)
                "trade_failed" -> Entry(t, "⚖", "Ticaret gerçekleşmedi: kaynaklar yetmedi", LoopaPalette.PeachDeep, null, false)
                "alliance_failed" -> Entry(t, "🤝", "İttifak kurulamadı (en fazla 3 krallık)", LoopaPalette.PeachDeep, null, false)
                "eliminated" -> Entry(t, "☠", "Başkentin düştü", LoopaPalette.PeachDeep, null, true)
                else -> null
            }
        }
        val public = (p / "log").a().mapNotNull { l ->
            val t = (l / "turn").i() ?: 0
            when ((l / "e").s()) {
                "eliminated" -> Entry(t, "☠", "${kName((l / "k").i())} düştü — fetheden: ${kName((l / "by").i())}", LoopaPalette.PeachDeep, null, true)
                "alliance" -> Entry(t, "🤝", "${kName((l / "a").i())} ile ${kName((l / "b").i())} ittifak kurdu", LoopaPalette.SkyDeep, null, true)
                "alliance_broken" -> Entry(t, "💔", "${kName((l / "k").i())}, müttefiki ${kName((l / "victim").i())} krallığına saldırdı; ittifaktan çıktı", LoopaPalette.PeachDeep, null, true)
                "alliance_left" -> Entry(t, "🚪", "${kName((l / "k").i())} ittifaktan ayrıldı", LoopaPalette.Lavender, null, false)
                "pact_broken" -> Entry(t, "⛓", "${kName((l / "k").i())}, ${kName((l / "victim").i())} ile anlaşmasını bozdu", LoopaPalette.PeachDeep, null, true)
                else -> null
            }
        }
        (own + public).sortedByDescending { it.turn }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val armies = (me / "armies").a()
        if (armies.isNotEmpty()) {
            SectionLabel("Yoldaki orduların")
            armies.forEach { a -> JournalRow(Entry(0, "🚩", "${(a / "n").i()} asker → bölge #${(a / "to").i()} · ${(a / "arrive").i()}. tur sonunda varır", LoopaPalette.LavenderDeep, (a / "to").i(), false), onTile) }
        }
        if (entries.isEmpty()) Text("Henüz olay yok. Tur sonları burada kayda geçer.", color = Soft, fontSize = 12.sp)
        entries.groupBy { it.turn }.forEach { (turn, list) ->
            Text("TUR $turn", color = Soft, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 6.dp).testTag("kr_journal_turn_$turn"))
            list.forEach { JournalRow(it, onTile) }
        }
    }
}

@Composable
private fun JournalRow(e: Entry, onTile: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().krCard(RoundedCornerShape(16.dp), if (e.big) lerpC(PanelHigh, e.tone, 0.12f) else PanelHigh)
        .clickable(enabled = e.tile != null) { e.tile?.let(onTile) }.padding(start = 8.dp, end = 10.dp).testTag("kr_journal_entry"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(e.tone.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) { Text(e.icon, fontSize = 15.sp) }
        Spacer(Modifier.width(10.dp))
        Text(e.text, color = Cream, fontSize = 12.sp, fontWeight = if (e.big) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (e.tile != null) Text("Haritada ›", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}
