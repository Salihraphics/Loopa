package com.loopa.feature.game.modes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.loopa.core.game.modes.Hex
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.JsonElement
import kotlin.math.cos
import kotlin.math.sin

private val Fog = Color(0xFF5C5A6E)
private fun terrainColor(t: String?): Color = when (t) {
    "forest" -> Color(0xFF66BB6A); "mine" -> Color(0xFF90A4AE); "gold" -> Color(0xFFFFD54F); "farm" -> Color(0xFFDCE775); else -> Color(0xFFD7CCC8)
}
internal fun terrainName(t: String?): String = when (t) { "forest" -> "Orman"; "mine" -> "Maden"; "gold" -> "Altın"; "farm" -> "Tarım arazisi"; else -> "Ova" }
internal fun buildingName(b: String?): String = when (b) { "barracks" -> "Kışla"; "market" -> "Pazar"; "farm" -> "Çiftlik"; else -> "—" }
private val resNames = listOf("wood" to "Odun", "iron" to "Demir", "gold" to "Altın", "food" to "Yiyecek")

private data class Move(val from: Int, val to: Int, val n: Int)
private data class Build(val tile: Int, val type: String)

/** Krallıklar: hex map with fog of war, simultaneous orders, letters and formal offers. */
@OptIn(ExperimentalLayoutApi::class)
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
    val tiles: Map<Int, JsonElement> = (if (over) (p / "tiles").a().mapIndexed { i, t -> i to t } else (me / "tiles").a().map { ((it / "i").i() ?: 0) to it }).toMap()
    val kColor: (Int?) -> Color = { k -> kingdoms.firstOrNull { (it / "k").i() == k }?.let { parseColor((it / "color").s()) } ?: Color.Gray }
    val kName: (Int?) -> String = { k -> kingdoms.firstOrNull { (it / "k").i() == k }?.let { (it / "name").s() } ?: "Tarafsız" }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var targeting by remember { mutableStateOf(false) }
    var sendTo by remember { mutableStateOf<Int?>(null) }
    val moves = remember(turnNo) { mutableStateListOf<Move>().apply { addAll((me / "orders" / "moves").a().map { Move((it / "from").i() ?: 0, (it / "to").i() ?: 0, (it / "n").i() ?: 1) }) } }
    val builds = remember(turnNo) { mutableStateListOf<Build>().apply { addAll((me / "orders" / "builds").a().map { Build((it / "tile").i() ?: 0, (it / "type").s().orEmpty()) }) } }
    val ready = (me / "ready").b()
    val canAct = alive && !over && !ctx.isSpectator && myK != null

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // stock
        if (myK != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(kColor(myK)))
                Spacer(Modifier.width(6.dp))
                Text(kName(myK), fontWeight = FontWeight.Bold, color = Loopa.colors.text)
            }
            Text(resNames.joinToString("  ") { (k, n) -> "${n.take(3)} ${(me / "res" / k).i() ?: 0}" }, style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
        }
        if (over) TurnBanner(ctx, null, true, doneText = (p / "winner").i()?.let { if (it == myK) "Krallığın ayakta kalan son krallık!" else "${kName(it)} kazandı" } ?: "Oyun bitti")
        else if (!alive && myK != null) TurnBanner(ctx, null, true, doneText = "Krallığın düştü", extra = "Oyunu izlemeye devam edebilirsin.")
        TabRow(selectedTabIndex = tab) {
            listOf("Harita", "Diplomasi", "Raporlar").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("kr_tab_$i")) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> {
                    HexMap(map, tiles, kColor, selected, moves.map { it.from to it.to }, onTap = { i ->
                        if (targeting && selected != null) { sendTo = i; targeting = false } else { selected = i; sendTo = null }
                    })
                    if (targeting) Text("Hedef karoya dokun.", color = LoopaPalette.PeachDeep, fontWeight = FontWeight.Bold)
                    selected?.let { i -> TileInfo(i, tiles[i], myK, kName, canAct, builds.any { it.tile == i }, me,
                        onSend = { targeting = true },
                        onBuild = { type -> builds.removeAll { it.tile == i }; builds.add(Build(i, type)) }) }
                    val from = selected; val to = sendTo
                    if (canAct && from != null && to != null) {
                        val avail = ((tiles[from] / "x").i() ?: 0) - moves.filter { it.from == from }.sumOf { it.n }
                        var count by remember(from, to) { mutableFloatStateOf(maxOf(1, avail).toFloat()) }
                        val dist = map.getOrNull(from)?.let { a -> map.getOrNull(to)?.let { b -> Hex.distance(a[0], a[1], b[0], b[1]) } } ?: 1
                        val speed = (me / "balance" / "speed").i() ?: 2
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Loopa.colors.card).padding(10.dp)) {
                            Text("Asker gönder: ${count.toInt()} · mesafe $dist · ${Hex.turnsToArrive(dist, speed)} tur sonunda varır", color = Loopa.colors.text)
                            if (avail >= 1) Slider(count, { count = it }, valueRange = 1f..maxOf(1, avail).toFloat(), steps = maxOf(0, avail - 2))
                            ActionRow {
                                GameButton("Vazgeç", primary = false) { sendTo = null }
                                GameButton("Emre ekle", enabled = avail >= 1, tag = "kr_add_move", color = LoopaPalette.Mint) { moves.add(Move(from, to, count.toInt())); sendTo = null }
                            }
                        }
                    }
                    if (canAct) OrdersPanel(moves, builds, ready, ui, kName = { i -> terrainName((tiles[i] / "t").s()) })
                }
                1 -> Diplomacy(ctx, p, me, myK, kingdoms, kName, kColor, canAct, turnNo, ui)
                2 -> Reports(me, p, kName)
            }
            WaitingNote("Turlar 60 saniye; herkes hazır olunca erken biter. Savaşlar tur sonunda sunucuda çözülür. Denge değerleri geçicidir.")
        }
    }
}

@Composable
private fun HexMap(map: List<List<Int>>, tiles: Map<Int, JsonElement>, kColor: (Int?) -> Color, selected: Int?, arrows: List<Pair<Int, Int>>, onTap: (Int) -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val index = remember(map) { map.mapIndexed { i, c -> (c[0] to c[1]) to i }.toMap() }
    Canvas(
        Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2E3B4E)).testTag("kr_map")
            .pointerInput(map) { detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(0.6f, 4f); pan += p } }
            .pointerInput(map, zoom, pan) {
                detectTapGestures { pos ->
                    if (map.isEmpty()) return@detectTapGestures
                    val (base, origin) = layout(map, size.width.toFloat(), size.height.toFloat())
                    val s = base * zoom
                    val (q, r) = Hex.at((pos.x - origin.x * zoom - pan.x - (size.width / 2f) * (1 - zoom)) / 1f, (pos.y - origin.y * zoom - pan.y - (size.height / 2f) * (1 - zoom)), s)
                    index[q to r]?.let(onTap)
                }
            },
    ) {
        if (map.isEmpty()) return@Canvas
        val (base, origin) = layout(map, size.width, size.height)
        val s = base * zoom
        val shift = Offset(origin.x * zoom + pan.x + (size.width / 2f) * (1 - zoom), origin.y * zoom + pan.y + (size.height / 2f) * (1 - zoom))
        map.forEachIndexed { i, c ->
            val (x, y) = Hex.center(c[0], c[1], s)
            val ctr = Offset(x, y) + shift
            val t = tiles[i]
            val path = hexPath(ctr, s * 0.96f)
            if (t == null) drawPath(path, Fog)
            else {
                drawPath(path, terrainColor((t / "t").s()).copy(alpha = if (((t / "l").i() ?: 0) == 0 && (t / "t").s() != "plain") 0.55f else 1f))
                val o = (t / "o").i() ?: 0
                if (o > 0) drawPath(path, kColor(o), style = Stroke(width = s * 0.22f))
                if ((t / "cap").b()) drawCircle(kColor(o), s * 0.32f, ctr)
                val x2 = (t / "x").i() ?: 0
                if (x2 > 0) drawCircle(Color(0xFF353344), s * (0.12f + minOf(x2, 50) / 300f), ctr)
                if ((t / "b").s() != null) drawRect(Color(0xFF6D4C41), ctr - Offset(s * 0.18f, s * 0.18f), androidx.compose.ui.geometry.Size(s * 0.36f, s * 0.2f))
            }
            if (i == selected) drawPath(path, Color.White, style = Stroke(width = s * 0.18f))
        }
        arrows.forEach { (f, to) ->
            val a = map.getOrNull(f); val b = map.getOrNull(to)
            if (a != null && b != null) {
                val (x1, y1) = Hex.center(a[0], a[1], s); val (x2, y2) = Hex.center(b[0], b[1], s)
                drawLine(Color.White, Offset(x1, y1) + shift, Offset(x2, y2) + shift, strokeWidth = s * 0.12f)
            }
        }
    }
}

/** Base hex size and offset so that the whole map fits the canvas. */
private fun layout(map: List<List<Int>>, w: Float, h: Float): Pair<Float, Offset> {
    val pts = map.map { Hex.center(it[0], it[1], 1f) }
    val minX = pts.minOf { it.first } - 1; val maxX = pts.maxOf { it.first } + 1
    val minY = pts.minOf { it.second } - 1; val maxY = pts.maxOf { it.second } + 1
    val s = minOf(w / (maxX - minX), h / (maxY - minY))
    return s to Offset(-minX * s + (w - (maxX - minX) * s) / 2, -minY * s + (h - (maxY - minY) * s) / 2)
}

private fun hexPath(c: Offset, r: Float): Path = Path().apply {
    for (k in 0 until 6) {
        val ang = Math.toRadians(60.0 * k - 30.0)
        val x = c.x + r * cos(ang).toFloat(); val y = c.y + r * sin(ang).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun TileInfo(i: Int, t: JsonElement?, myK: Int?, kName: (Int?) -> String, canAct: Boolean, buildQueued: Boolean, me: JsonElement?,
                     onSend: () -> Unit, onBuild: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Loopa.colors.card).padding(10.dp).testTag("kr_tile_info"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (t == null) { Text("Sis: bu karo görünmüyor.", color = Loopa.colors.textSoft); return@Column }
        val own = (t / "o").i() ?: 0
        val life = (t / "l").i() ?: 0
        Text("${terrainName((t / "t").s())}${if ((t / "cap").b()) " · BAŞKENT" else ""} — ${kName(own)}", fontWeight = FontWeight.Bold, color = Loopa.colors.text)
        Text("Asker: ${(t / "x").i() ?: 0} · Bina: ${buildingName((t / "b").s())}" +
            if ((t / "t").s() != "plain") " · ${if (life > 0) "$life tur kaynak" else "tükendi"}" else "", color = Loopa.colors.textSoft, style = MaterialTheme.typography.bodySmall)
        if (canAct && own == myK) {
            ActionRow { GameButton("Asker gönder", enabled = ((t / "x").i() ?: 0) > 0, tag = "kr_send", color = LoopaPalette.Sky, onClick = onSend) }
            if ((t / "b").s() == null && !(t / "cap").b()) {
                val bal = me / "balance" / "buildings"
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("barracks", "market", "farm").forEach { type ->
                        val cost = (bal / type / "cost").o().entries.joinToString(" ") { (k, v) -> "${resNames.firstOrNull { it.first == k }?.second?.take(3)} ${v.i()}" }
                        TextButton({ onBuild(type) }, modifier = Modifier.testTag("kr_build_$type")) { Text("${buildingName(type)}\n$cost", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (buildQueued) Text("Bu karoya bina emri verdin.", color = LoopaPalette.MintDeep, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OrdersPanel(moves: MutableList<Move>, builds: MutableList<Build>, ready: Boolean, ui: ModeUi, kName: (Int) -> String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Loopa.colors.card).padding(10.dp).testTag("kr_orders"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("Bu turun emirleri")
        if (moves.isEmpty() && builds.isEmpty()) Text("Henüz emir yok.", color = Loopa.colors.textSoft, style = MaterialTheme.typography.bodySmall)
        moves.toList().forEachIndexed { k, m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚔ ${m.n} asker: #${m.from} → #${m.to} (${kName(m.to)})", modifier = Modifier.weight(1f), color = Loopa.colors.text, style = MaterialTheme.typography.bodySmall)
                TextButton({ moves.removeAt(k) }) { Text("Sil") }
            }
        }
        builds.toList().forEachIndexed { k, b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏗 ${buildingName(b.type)}: #${b.tile}", modifier = Modifier.weight(1f), color = Loopa.colors.text, style = MaterialTheme.typography.bodySmall)
                TextButton({ builds.removeAt(k) }) { Text("Sil") }
            }
        }
        ActionRow {
            GameButton("Emirleri gönder", tag = "kr_submit", enabled = ui.busy == null, color = LoopaPalette.Mint) {
                ui.act("orders", payload(
                    "moves" to jsonList(moves.map { payload("from" to it.from, "to" to it.to, "n" to it.n) }),
                    "builds" to jsonList(builds.map { payload("tile" to it.tile, "type" to it.type) }))) { ui.flash("Emirler kaydedildi") }
            }
            GameButton(if (ready) "Hazır ✓" else "Hazırım", tag = "kr_ready", primary = ready.not(), color = LoopaPalette.Butter) { ui.act("ready", payload("ready" to !ready)) {} }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Diplomacy(ctx: ModeContext, p: kotlinx.serialization.json.JsonObject, me: JsonElement?, myK: Int?, kingdoms: List<JsonElement>,
                      kName: (Int?) -> String, kColor: (Int?) -> Color, canAct: Boolean, turnNo: Int, ui: ModeUi) {
    var letterTo by remember { mutableStateOf<Int?>(null) }
    var offerTo by remember { mutableStateOf<Int?>(null) }
    val allies = (me / "allies").ints()
    if (canAct && turnNo == 1) RenameBox(ui, kingdoms, myK)
    SectionTitle("Krallıklar")
    kingdoms.forEach { k ->
        val id = (k / "k").i() ?: 0
        val isAlive = (k / "alive").b()
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Loopa.colors.card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(kColor(id)))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("${(k / "name").s()}${if (id == myK) " (sen)" else ""}${if (id in allies) " · müttefik" else ""}", color = Loopa.colors.text, fontWeight = FontWeight.Bold)
                Text("${ctx.name((k / "user").s())}${if (!isAlive) " · düştü" else if ((k / "ready").b()) " · hazır" else ""}", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
            }
            if (canAct && id != myK && isAlive) {
                TextButton({ letterTo = id }, modifier = Modifier.testTag("kr_letter_$id")) { Text("Mektup") }
                TextButton({ offerTo = id }, modifier = Modifier.testTag("kr_offer_$id")) { Text("Teklif") }
            }
        }
    }
    (p / "alliances").a().takeIf { it.isNotEmpty() }?.let { al ->
        SectionTitle("İttifaklar (en fazla 3 krallık)")
        al.forEach { a -> Text((a / "m").ints().joinToString(" + ") { kName(it) }, color = Loopa.colors.text, style = MaterialTheme.typography.bodySmall) }
        if (canAct && allies.isNotEmpty()) GameButton("İttifaktan ayrıl", primary = false) { ui.act("leave_alliance", payload()) {} }
    }
    val offers = (me / "offers").a()
    if (offers.isNotEmpty()) SectionTitle("Teklifler")
    offers.forEach { o ->
        val incoming = (o / "to").i() == myK
        val status = (o / "status").s()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (incoming && status == "pending") LoopaPalette.Butter.copy(alpha = 0.5f) else Loopa.colors.card).padding(8.dp)) {
            Text("${offerName((o / "type").s())}: ${if (incoming) "${kName((o / "from").i())} → sen" else "sen → ${kName((o / "to").i())}"} · ${statusName(status)}",
                color = Loopa.colors.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            offerTerms(o, kName)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft) }
            if (canAct && status == "pending") Row {
                if (incoming) {
                    TextButton({ ui.act("respond", payload("id" to (o / "id").i(), "accept" to true)) {} }, modifier = Modifier.testTag("kr_accept")) { Text("Kabul") }
                    TextButton({ ui.act("respond", payload("id" to (o / "id").i(), "accept" to false)) {} }) { Text("Reddet") }
                } else TextButton({ ui.act("cancel_offer", payload("id" to (o / "id").i())) {} }) { Text("Geri çek") }
            }
        }
    }
    val pacts = (me / "pacts").a()
    if (pacts.isNotEmpty()) {
        SectionTitle("Anlaşmalar")
        pacts.forEach { pc ->
            val other = if ((pc / "a").i() == myK) (pc / "b").i() else (pc / "a").i()
            Text("${offerName((pc / "type").s())} — ${kName(other)}${(pc / "target").i()?.let { " (hedef: ${kName(it)})" } ?: ""} · ${(pc / "until").i()}. tura kadar",
                style = MaterialTheme.typography.bodySmall, color = Loopa.colors.text)
        }
    }
    SectionTitle("Gelen mektuplar")
    val inbox = (me / "inbox").a()
    if (inbox.isEmpty()) Text("Mektup yok.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
    inbox.reversed().forEach { l ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Loopa.colors.card).padding(8.dp)) {
            Text("${kName((l / "from").i())} · ${(l / "subject").s().orEmpty().ifEmpty { "(konusuz)" }} · tur ${(l / "turn").i()}", fontWeight = FontWeight.Bold, color = Loopa.colors.text, style = MaterialTheme.typography.bodySmall)
            Text((l / "body").s().orEmpty(), color = Loopa.colors.text, style = MaterialTheme.typography.bodySmall)
        }
    }
    letterTo?.let { to -> LetterDialog(kName(to), onDismiss = { letterTo = null }) { subj, body -> letterTo = null; ui.act("letter", payload("to" to jsonInts(listOf(to)), "subject" to subj, "body" to body)) { ui.flash("Mektup gönderildi") } } }
    offerTo?.let { to -> OfferDialog(kName(to), kingdoms.mapNotNull { (it / "k").i() }.filter { it != to && it != myK && kingdoms.first { k -> (k / "k").i() == it }.let { k -> (k / "alive").b() } }, kName,
        onDismiss = { offerTo = null }) { pl -> offerTo = null; ui.act("offer", payload(*(listOf("to" to to) + pl).toTypedArray())) { ui.flash("Teklif gönderildi") } } }
}

private fun offerName(t: String?) = when (t) { "nap" -> "Saldırmazlık"; "alliance" -> "İttifak"; "trade" -> "Ticaret"; "joint_enemy" -> "Ortak düşman"; "peace" -> "Barış"; else -> t.orEmpty() }
private fun statusName(s: String?) = when (s) { "pending" -> "bekliyor"; "accepted" -> "kabul edildi"; "rejected" -> "reddedildi"; "failed" -> "gerçekleşmedi"; "cancelled" -> "geri çekildi"; else -> s.orEmpty() }
private fun offerTerms(o: JsonElement, kName: (Int?) -> String): String? = when ((o / "type").s()) {
    "trade" -> "Verir: ${bundle(o / "give")} · İster: ${bundle(o / "get")}"
    "nap", "peace" -> "${(o / "turns").i()} tur"
    "joint_enemy" -> "Hedef: ${kName((o / "target").i())}, ${(o / "turns").i()} tur"
    else -> null
}
private fun bundle(b: JsonElement?): String = b.o().entries.joinToString(", ") { (k, v) -> "${v.i()} ${resNames.firstOrNull { it.first == k }?.second ?: k}" }.ifEmpty { "—" }

@Composable
private fun LetterDialog(to: String, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var subj by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Mektup: $to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(subj, { subj = it.take(60) }, label = { Text("Konu") }, singleLine = true, modifier = Modifier.testTag("kr_letter_subject"))
                OutlinedTextField(body, { body = it.take(600) }, label = { Text("Mektup") }, minLines = 3, modifier = Modifier.testTag("kr_letter_body"))
            }
        },
        confirmButton = { TextButton({ onSend(subj, body) }, enabled = body.isNotBlank(), modifier = Modifier.testTag("kr_letter_send")) { Text("Gönder") } },
        dismissButton = { TextButton(onDismiss) { Text("Vazgeç") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferDialog(to: String, others: List<Int>, kName: (Int?) -> String, onDismiss: () -> Unit, onSend: (List<Pair<String, Any?>>) -> Unit) {
    var type by remember { mutableStateOf("nap") }
    var turns by remember { mutableStateOf("5") }
    var target by remember { mutableStateOf(others.firstOrNull()) }
    val give = remember { mutableStateListOf("0", "0", "0", "0") }
    val get = remember { mutableStateListOf("0", "0", "0", "0") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Teklif: $to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("nap", "alliance", "trade", "joint_enemy", "peace").forEach { t -> FilterChip(type == t, { type = t; if (t == "peace") turns = "10" }, label = { Text(offerName(t)) }) }
                }
                if (type in setOf("nap", "peace", "joint_enemy"))
                    OutlinedTextField(turns, { turns = it.filter(Char::isDigit).take(2) }, label = { Text("Süre (2–30 tur)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (type == "joint_enemy") FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    others.forEach { k -> FilterChip(target == k, { target = k }, label = { Text(kName(k)) }) }
                }
                if (type == "trade") {
                    Text("Vereceğin", fontWeight = FontWeight.Bold)
                    resNames.forEachIndexed { i, (_, n) -> OutlinedTextField(give[i], { give[i] = it.filter(Char::isDigit).take(6) }, label = { Text(n) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                    Text("İstediğin", fontWeight = FontWeight.Bold)
                    resNames.forEachIndexed { i, (_, n) -> OutlinedTextField(get[i], { get[i] = it.filter(Char::isDigit).take(6) }, label = { Text(n) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                }
                if (type == "alliance") Text("İttifak en fazla 3 krallıktır. Müttefikine saldıran ittifaktan çıkar.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton({
                val pl = mutableListOf<Pair<String, Any?>>("type" to type)
                if (type in setOf("nap", "peace", "joint_enemy")) pl += "turns" to (turns.toIntOrNull() ?: 5)
                if (type == "joint_enemy") pl += "target" to target
                if (type == "trade") {
                    pl += "give" to payload(*resNames.mapIndexed { i, (k, _) -> k to (give[i].toIntOrNull() ?: 0) }.toTypedArray())
                    pl += "get" to payload(*resNames.mapIndexed { i, (k, _) -> k to (get[i].toIntOrNull() ?: 0) }.toTypedArray())
                }
                onSend(pl)
            }, modifier = Modifier.testTag("kr_offer_send")) { Text("Gönder") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Vazgeç") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenameBox(ui: ModeUi, kingdoms: List<JsonElement>, myK: Int?) {
    var name by remember { mutableStateOf(kingdoms.firstOrNull { (it / "k").i() == myK }?.let { (it / "name").s() }.orEmpty()) }
    val palette = listOf("#E53935", "#1E88E5", "#43A047", "#FDD835", "#8E24AA", "#FB8C00", "#00ACC1", "#D81B60", "#6D4C41", "#3949AB",
        "#7CB342", "#F4511E", "#5E35B1", "#00897B", "#C0CA33", "#546E7A", "#FFB300", "#26A69A", "#EC407A", "#8D6E63")
    val taken = kingdoms.filter { (it / "k").i() != myK }.mapNotNull { (it / "color").s() }.toSet()
    var color by remember { mutableStateOf(kingdoms.firstOrNull { (it / "k").i() == myK }?.let { (it / "color").s() }) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Loopa.colors.card).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Krallığına ad ve renk ver (yalnızca ilk tur)")
        OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Krallık adı") }, modifier = Modifier.testTag("kr_name"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            palette.forEach { c ->
                val free = c !in taken
                Box(Modifier.size(26.dp).clip(CircleShape).background(parseColor(c).copy(alpha = if (free) 1f else 0.2f))
                    .border(if (c == color) 3.dp else 0.dp, LoopaPalette.Ink, CircleShape).clickable(enabled = free) { color = c })
            }
        }
        GameButton("Kaydet", enabled = name.trim().length >= 2) { ui.act("rename", payload("name" to name.trim(), "color" to color)) { ui.flash("Krallık güncellendi") } }
    }
}

@Composable
private fun Reports(me: JsonElement?, p: kotlinx.serialization.json.JsonObject, kName: (Int?) -> String) {
    SectionTitle("Raporların")
    val events = (me / "events").a()
    if (events.isEmpty()) Text("Henüz rapor yok.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
    events.reversed().forEach { e -> Text("Tur ${(e / "turn").i()}: ${reportText(e, kName)}", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.text) }
    val armies = (me / "armies").a()
    if (armies.isNotEmpty()) {
        SectionTitle("Yoldaki orduların")
        armies.forEach { a -> Text("${(a / "n").i()} asker → #${(a / "to").i()} · ${(a / "arrive").i()}. tur sonunda varır", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.text) }
    }
    SectionTitle("Duyurular")
    (p / "log").a().reversed().forEach { l ->
        Text("Tur ${(l / "turn").i()}: " + when ((l / "e").s()) {
            "eliminated" -> "${kName((l / "k").i())} düştü (${kName((l / "by").i())})"
            "alliance" -> "${kName((l / "a").i())} ve ${kName((l / "b").i())} ittifak kurdu"
            "alliance_broken" -> "${kName((l / "k").i())} müttefiki ${kName((l / "victim").i())} krallığına saldırdı; ittifaktan çıktı"
            "alliance_left" -> "${kName((l / "k").i())} ittifaktan ayrıldı"
            "pact_broken" -> "${kName((l / "k").i())}, ${kName((l / "victim").i())} ile anlaşmasını bozdu"
            else -> (l / "e").s().orEmpty()
        }, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
    }
}

private fun reportText(e: JsonElement, kName: (Int?) -> String): String = when ((e / "e").s()) {
    "battle" -> "Savaş #${(e / "tile").i()}: ${kName((e / "att").i())} (${(e / "att_n").i()}) ⚔ ${kName((e / "def").i())} (${(e / "def_n").i()}) — " +
        (if ((e / "won").b()) "saldıran kazandı" else "savunan tuttu") + ", ${(e / "left").i()} asker kaldı"
    "arrived" -> "${(e / "n").i()} asker #${(e / "tile").i()} karosuna ulaştı"
    "built" -> "${buildingName((e / "type").s())} tamamlandı (#${(e / "tile").i()})"
    "build_failed" -> "${buildingName((e / "type").s())} yapılamadı (${if ((e / "why").s() == "RESOURCES") "kaynak yetersiz" else "karo uygun değil"})"
    "no_food" -> "Kışla yiyecek yetmediği için asker üretemedi (#${(e / "tile").i()})"
    "offer" -> "${kName((e / "from").i())} sana ${offerName((e / "type").s())} teklif etti"
    "offer_accepted" -> "${kName((e / "by").i())} ${offerName((e / "type").s())} teklifini kabul etti"
    "offer_rejected" -> "${kName((e / "by").i())} ${offerName((e / "type").s())} teklifini reddetti"
    "trade_failed" -> "Ticaret gerçekleşmedi: kaynaklar yetmedi"
    "alliance_failed" -> "İttifak kurulamadı (en fazla 3 krallık)"
    "eliminated" -> "Başkentin düştü"
    else -> (e / "e").s().orEmpty()
}

