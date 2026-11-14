package com.loopa.feature.game.modes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.LocalReducedMotion
import kotlinx.serialization.json.JsonElement
import java.util.UUID

// Dark industrial palette of the Buckshot table (the rest of LOOPA stays pastel).
private val Steel = Color(0xFF1C1D22)
private val SteelHigh = Color(0xFF2A2C33)
private val SteelEdge = Color(0xFF3C3F48)
private val Rust = Color(0xFFE0533D)
private val Ember = Color(0xFFFF8A3D)
private val Bone = Color(0xFFE8E2D6)
private val Dim = Color(0xFF9A958C)
private val Blank = Color(0xFF6E7A86)

/** Items with LOOPA names; the server knows them by their id. */
internal object BuckshotItems {
    data class Info(val name: String, val icon: String, val about: String, val needsTarget: Boolean = false)
    val all = mapOf(
        "beer" to Info("Gazoz", "🥤", "Sıradaki mermiyi ateşlemeden çıkarır."),
        "cigarettes" to Info("Yara Bandı", "❤", "+1 can (can doluysa kullanılamaz)."),
        "magnifier" to Info("Büyüteç", "🔍", "Sıradaki merminin dolu mu boş mu olduğunu yalnız sana gösterir."),
        "handcuffs" to Info("Kelepçe", "⛓", "Seçtiğin rakip bir sonraki sırasını kaçırır.", needsTarget = true),
        "saw" to Info("Testere", "🔪", "Sıradaki dolu mermi 2 hasar verir."),
        "inverter" to Info("Çevirici", "🔄", "Sıradaki merminin türünü tersine çevirir."),
        "phone" to Info("Telsiz", "📻", "İlerideki rastgele bir mermiyi yalnız sana söyler."),
        "medicine" to Info("Şüpheli İlaç", "💊", "%50 +2 can, %50 −1 can."),
        "remote" to Info("Kumanda", "🎛", "Sıra yönünü tersine çevirir."),
    )
    fun info(id: String) = all[id] ?: Info(id, "?", "")
}

/**
 * Buckshot (2-4 FFA). Landscape: the table on the left, the player's own items and actions on the
 * right. The client sees counts and what it learnt itself; the server owns the magazine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuckshotScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val order = (p / "order").strs()
    val players = (p / "players")
    val turn = (p / "turn").s()
    val over = mode.over
    val myTurn = turn == ctx.me && !over && !ctx.isSpectator && (players / ctx.me / "alive").b()
    val inv = (mode.me / "inv").strs()
    val known = (mode.me / "known").a()
    val mag = p / "mag"
    var pickTarget by remember(turn, mode.round?.id) { mutableStateOf<String?>(null) }   // item waiting for a target
    val nonce = { UUID.randomUUID().toString() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Steel).testTag("bs_screen")) {
        val wide = maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.35f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Table(ctx, p, order, players, turn, over, myTurn, pickTarget, ui, nonce, onPicked = { pickTarget = null })
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Side(ctx, mag, known, inv, myTurn, pickTarget, { pickTarget = it }, ui, nonce, p)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("En iyi deneyim için telefonu yan çevir.", color = Dim, fontSize = 12.sp)
                Table(ctx, p, order, players, turn, over, myTurn, pickTarget, ui, nonce, onPicked = { pickTarget = null })
                Side(ctx, mag, known, inv, myTurn, pickTarget, { pickTarget = it }, ui, nonce, p)
            }
        }
        if (over) MoneyBag(ctx, (p / "winner").s(), (p / "bag").i() ?: 0, (p / "round").i() ?: 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Table(ctx: ModeContext, p: kotlinx.serialization.json.JsonObject, order: List<String>, players: JsonElement?, turn: String?, over: Boolean,
                              myTurn: Boolean, pickTarget: String?, ui: ModeUi, nonce: () -> String, onPicked: () -> Unit) {
    val wins = p / "wins"
    // header: round, scores, direction
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SteelHigh).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("RAUND ${(p / "round").i() ?: 1}/3", color = Ember, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.testTag("bs_round"))
        Spacer(Modifier.width(12.dp))
        Text("Can ${(p / "max_hp").i() ?: 3}", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(if ((p / "dir").i() == -1) "⟲ ters yön" else "⟳ saat yönü", color = Dim, fontSize = 12.sp)
    }
    // the table: seats around a lit centre
    val lastShot = (p / "log").a().lastOrNull { (it / "e").s() == "shot" }
    ShotFlash(lastShot)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { u ->
            val pl = players / u
            val alive = (pl / "alive").b()
            val isTurn = u == turn && !over
            val targetable = (myTurn && alive) && (pickTarget == null || u != ctx.me)
            Column(
                Modifier.width(150.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(SteelHigh, Steel)))
                    .border(if (isTurn) 2.dp else 1.dp, if (isTurn) Rust else SteelEdge, RoundedCornerShape(14.dp))
                    .padding(10.dp).alpha(if (alive) 1f else 0.45f).testTag("bs_seat_$u"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (u == ctx.me) "SEN" else ctx.name(u)).take(10), color = Bone, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("💰".repeat((wins / u).i() ?: 0), fontSize = 12.sp)
                }
                // HP as warm lamps
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    repeat((p / "max_hp").i() ?: 3) { k ->
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (k < ((pl / "hp").i() ?: 0)) Rust else SteelEdge))
                    }
                }
                Text(buildString {
                    append(if (alive) "${(pl / "items").i() ?: 0} eşya" else "ELENDİ")
                    if ((pl / "cuffed").b()) append(" · ⛓")
                    if ((pl / "saw").b()) append(" · 🔪")
                }, color = Dim, fontSize = 11.sp)
                if (isTurn) Text("SIRADA", color = Ember, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (targetable) {
                    Spacer(Modifier.height(6.dp))
                    val label = when {
                        pickTarget != null -> "${BuckshotItems.info(pickTarget).name} hedefi"
                        u == ctx.me -> "Kendine ateş"
                        else -> "Ateş et"
                    }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (u == ctx.me) SteelEdge else Rust)
                        .clickable(enabled = ui.busy == null) {
                            if (pickTarget != null) { ui.act("item", payload("item" to pickTarget, "target" to u, "nonce" to nonce())) {}; onPicked() }
                            else ui.act("fire", payload("target" to u, "nonce" to nonce())) {}
                        }.padding(vertical = 8.dp).testTag(if (u == ctx.me) "bs_fire_self" else "bs_fire_$u"),
                        contentAlignment = Alignment.Center) {
                        Text(label, color = Bone, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
    // log
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SteelHigh).padding(10.dp).testTag("bs_log")) {
        (p / "log").a().takeLast(6).forEach { e -> logLine(ctx, e)?.let { Text(it, color = Dim, fontSize = 12.sp) } }
    }
}

@Composable
private fun Side(ctx: ModeContext, mag: JsonElement?, known: List<JsonElement>, inv: List<String>, myTurn: Boolean, pickTarget: String?,
                 setPick: (String?) -> Unit, ui: ModeUi, nonce: () -> String, p: kotlinx.serialization.json.JsonObject) {
    Magazine(mag, known, p)
    if (ctx.isSpectator) return
    Text("EŞYALARIN (${inv.size}/8)", color = Ember, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 13.sp)
    if (inv.isEmpty()) Text("Eşyan yok.", color = Dim, fontSize = 12.sp)
    inv.groupingBy { it }.eachCount().forEach { (id, count) ->
        val info = BuckshotItems.info(id)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (pickTarget == id) Rust.copy(alpha = 0.4f) else SteelHigh)
            .border(1.dp, SteelEdge, RoundedCornerShape(12.dp))
            .clickable(enabled = myTurn && ui.busy == null) {
                if (info.needsTarget) setPick(if (pickTarget == id) null else id)
                else ui.act("item", payload("item" to id, "nonce" to nonce())) {}
            }.padding(8.dp).testTag("bs_item_$id"), verticalAlignment = Alignment.CenterVertically) {
            Text(info.icon, fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name + if (count > 1) " ×$count" else "", color = Bone, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(info.about, color = Dim, fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
    }
    if (pickTarget != null) Text("Masadan bir rakip seç.", color = Ember, fontSize = 12.sp)
    Text(if (myTurn) "Sıra sende: istediğin kadar eşya kullan, sonra ateş et." else "Bekle… Süre biterse sunucu rastgele bir rakibe ateş eder.",
        color = Dim, fontSize = 11.sp)
    Text("Bahis yok. Raund galibi para çantası (coin) kazanır; kaybeden hiçbir şey kaybetmez.", color = Dim, fontSize = 10.sp)
}

/** Shells: fired ones with their type, the rest unknown unless this player learnt them. */
@Composable
private fun Magazine(mag: JsonElement?, known: List<JsonElement>, p: kotlinx.serialization.json.JsonObject) {
    val remaining = (mag / "remaining").i() ?: 0
    val fired = 8 - remaining
    // fired shells of the current magazine, from the public log (after its last "reload")
    val log = (p / "log").a()
    val since = log.indexOfLast { (it / "e").s() == "reload" }
    val firedTypes = log.drop(since + 1).filter { (it / "shell").i() != null }.associate { (it / "shell").i()!! to (it / "live").b() }
    val knownBy = known.associate { ((it / "shell").i() ?: 0) to (it / "live").b() }
    val liveLeft = ((mag / "loaded_live").i() ?: 0) - ((mag / "live_seen").i() ?: 0)
    val inverted = ((mag / "inverted").i() ?: 0) > 0
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SteelHigh).border(1.dp, SteelEdge, RoundedCornerShape(14.dp)).padding(10.dp)
        .testTag("bs_mag")) {
        Text("ŞARJÖR", color = Ember, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 13.sp)
        Text("Yüklenen: ${(mag / "loaded_live").i() ?: 0} dolu · ${(mag / "loaded_blank").i() ?: 0} boş", color = Bone, fontSize = 12.sp)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in 1..8) {
                val (color, label) = when {
                    k <= fired -> (if (firedTypes[k] == true) Rust.copy(alpha = 0.35f) else Blank.copy(alpha = 0.35f)) to "×"
                    knownBy.containsKey(k) -> (if (knownBy[k] == true) Rust else Blank) to (if (knownBy[k] == true) "D" else "B")
                    else -> SteelEdge to "?"
                }
                Box(Modifier.size(width = 18.dp, height = 34.dp).clip(RoundedCornerShape(5.dp)).background(color)
                    .border(if (k == fired + 1) 2.dp else 0.dp, Ember, RoundedCornerShape(5.dp)).testTag("bs_shell_$k"), contentAlignment = Alignment.Center) {
                    Text(label, color = Bone, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val pct = if (remaining > 0) (liveLeft.coerceIn(0, remaining) * 100 / remaining) else 0
        Text("Kalan $remaining · tahmini dolu ${liveLeft.coerceIn(0, remaining)} (%$pct)", color = Bone, fontSize = 12.sp, modifier = Modifier.testTag("bs_odds"))
        knownBy.entries.minByOrNull { it.key }?.let { (shell, live) ->
            Text("Bildiğin: $shell. mermi ${if (live) "DOLU" else "BOŞ"}", color = if (live) Rust else Blank, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("bs_known"))
        }
        if (inverted) Text("Çevirici kullanıldı: tahmin kesin değil.", color = Ember, fontSize = 11.sp)
    }
}

private fun logLine(ctx: ModeContext, e: JsonElement): String? {
    val who = (e / "u").s()?.let { if (it == ctx.me) "Sen" else ctx.name(it) }
    return when ((e / "e").s()) {
        "shot" -> {
            val t = (e / "t").s(); val tn = if (t == (e / "u").s()) "kendine" else if (t == ctx.me) "sana" else ctx.name(t)
            "$who → $tn: ${if ((e / "live").b()) "DOLU (−${(e / "dmg").i()})" else "boş"}"
        }
        "item" -> "$who: ${BuckshotItems.info((e / "item").s().orEmpty()).name}" + when {
            (e / "item").s() == "beer" -> " (${if ((e / "live").b()) "dolu" else "boş"} mermi atıldı)"
            (e / "item").s() == "medicine" -> if ((e / "heal").b()) " (+2)" else " (−1)"
            (e / "t").s() != null -> " → ${ctx.name((e / "t").s())}"
            else -> ""
        }
        "reload" -> "Yeni şarjör: ${(e / "live").i()} dolu, ${(e / "blank").i()} boş"
        "skipped" -> "$who kelepçeli: sırası atlandı"
        "out" -> "$who elendi"
        "timeout" -> "$who süreyi aştı"
        "round_won" -> "$who raundu kazandı" + ((e / "bag").i()?.takeIf { it > 0 }?.let { " (+$it coin)" } ?: "")
        else -> null
    }
}

/** A short red flash when a live shell fires (none with reduced motion). */
@Composable
private fun ShotFlash(last: JsonElement?) {
    val reduced = LocalReducedMotion.current
    val a = remember { Animatable(0f) }
    val key = last?.toString()
    LaunchedEffect(key) {
        if (key != null && (last / "live").b() && !reduced) { a.snapTo(0.6f); a.animateTo(0f, tween(500)) }
    }
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Rust.copy(alpha = 0.15f + a.value)))
}

/** Round over: the winner gets the money bag (server value). */
@Composable
private fun MoneyBag(ctx: ModeContext, winner: String?, bag: Int, round: Int) {
    val reduced = LocalReducedMotion.current
    val pulse = if (reduced) null else rememberInfiniteTransition(label = "bag").animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).testTag("bs_round_over"), contentAlignment = Alignment.Center) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(SteelHigh).border(2.dp, Ember, RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("💰", fontSize = 56.sp, modifier = Modifier.scale(pulse?.value ?: 1f))
            Text(if (winner == ctx.me) "Raundu kazandın!" else "${ctx.name(winner)} raundu kazandı", color = Bone, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                textAlign = TextAlign.Center)
            if (bag > 0) Text("Para çantası: +$bag coin", color = Ember, fontWeight = FontWeight.Bold)
            Text(if (round < 3) "Sonraki raund hazırlanıyor…" else "Maç bitti", color = Dim, fontSize = 12.sp)
        }
    }
}
