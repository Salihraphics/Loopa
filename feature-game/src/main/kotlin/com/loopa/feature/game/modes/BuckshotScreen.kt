package com.loopa.feature.game.modes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.model.ModeState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.theme.LocalReducedMotion
import com.loopa.feature.game.modes.BsArt.crate
import com.loopa.feature.game.modes.BsArt.item
import com.loopa.feature.game.modes.BsArt.shell
import com.loopa.feature.game.modes.BsArt.shotgun
import com.loopa.feature.game.modes.BsArt.table
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.math.atan2

/** Items with LOOPA names; the server knows them by their id. */
internal object BuckshotItems {
    data class Info(val name: String, val about: String, val needsTarget: Boolean = false)
    val all = mapOf(
        "beer" to Info("Gazoz", "Sıradaki mermiyi ateşlemeden çıkarır."),
        "cigarettes" to Info("Yara Bandı", "+1 can (can doluysa kullanılamaz)."),
        "magnifier" to Info("Büyüteç", "Sıradaki mermiyi yalnız sana gösterir."),
        "handcuffs" to Info("Kelepçe", "Seçtiğin rakip bir sonraki sırasını kaçırır.", needsTarget = true),
        "saw" to Info("Testere", "Sıradaki dolu mermi 2 hasar verir."),
        "inverter" to Info("Çevirici", "Sıradaki merminin türünü tersine çevirir."),
        "phone" to Info("Telsiz", "İlerideki bir mermiyi yalnız sana söyler."),
        "medicine" to Info("Şüpheli İlaç", "%50 +2 can, %50 −1 can."),
        "remote" to Info("Kumanda", "Sıra yönünü tersine çevirir."),
    )
    fun info(id: String) = all[id] ?: Info(id, "")
}

/** Where rivals sit around the table (fractions of the table box); the player always sits at the bottom. */
private fun seatSpots(rivals: Int): List<Pair<Float, Float>> = when (rivals) {
    1 -> listOf(0.5f to 0.12f)
    2 -> listOf(0.22f to 0.16f, 0.78f to 0.16f)
    else -> listOf(0.13f to 0.46f, 0.5f to 0.1f, 0.87f to 0.46f)
}

/**
 * Buckshot: friends around one table (portrait). Rivals sit around the lit table, the shotgun lies
 * in the middle and turns to whoever is aimed at, the player's items lie in front of them as
 * objects. The screen never computes odds: loaded counts, shells left and what you learnt only.
 */
@Composable
fun BuckshotScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val order = (p / "order").strs()
    val players = (p / "players")
    val turn = (p / "turn").s()
    val over = mode.over
    val me = ctx.me.takeIf { it in order && !ctx.isSpectator }
    val myTurn = me != null && turn == me && !over && (players / me / "alive").b()
    val inv = (mode.me / "inv").strs()
    val known = (mode.me / "known").a()
    val reduced = LocalReducedMotion.current
    var aim by remember(turn, mode.round?.id) { mutableStateOf<String?>(null) }
    var picked by remember(turn, mode.round?.id, inv.size) { mutableStateOf<Int?>(null) }
    val nonce = { UUID.randomUUID().toString() }
    // seating: the player at the bottom, rivals clockwise from their left
    val bottom = me ?: order.firstOrNull()
    val i0 = order.indexOf(bottom).coerceAtLeast(0)
    val rivals = (1 until order.size).map { order[(i0 + it) % order.size] }
    val spots = seatSpots(rivals.size)
    val pickedItem = picked?.let { inv.getOrNull(it) }
    val targeting = pickedItem != null && BuckshotItems.info(pickedItem).needsTarget

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(BsArt.Night).testTag("bs_screen")) {
            TopStrip(ctx, p, order, turn, over)
            // ------------------------------------------------------------------ the table
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val w = maxWidth; val h = maxHeight
                val gunTarget: String? = aim ?: turn
                val gunAngle by animateFloatAsState(
                    targetValue = gunTarget?.let { t ->
                        val (fx, fy) = if (t == bottom) 0.5f to 1.05f else spots.getOrElse(rivals.indexOf(t)) { 0.5f to 0f }
                        Math.toDegrees(atan2(((fy - 0.42f) * h.value).toDouble(), ((fx - 0.5f) * w.value).toDouble())).toFloat()
                    } ?: -20f,
                    animationSpec = if (reduced) tween(0) else spring(dampingRatio = 0.6f, stiffness = 120f), label = "gun")
                val flash = remember { Animatable(0f) }
                val lastShot = (p / "log").a().lastOrNull { (it / "e").s() == "shot" }
                val shotKey = lastShot?.toString()
                LaunchedEffect(shotKey) { if (shotKey != null && (lastShot / "live").b() && !reduced) { flash.snapTo(1f); flash.animateTo(0f, tween(650)) } }
                val lamp = if (reduced) null else rememberInfiniteTransition(label = "lamp").animateFloat(0.85f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "l")
                val mag = p / "mag"
                val remaining = (mag / "remaining").i() ?: 0
                val fired = 8 - remaining
                val knownShells = known.associate { ((it / "shell").i() ?: 0) to (it / "live").b() }

                Canvas(Modifier.fillMaxSize().testTag("bs_table")) {
                    table(lamp?.value ?: 1f)
                    val c = Offset(size.width / 2, size.height * 0.42f)
                    shotgun(c, size.width * 0.46f, gunAngle, flash.value)
                    // the magazine: 8 shells standing in a row; spent ones knocked over and grey
                    val sw = size.width * 0.03f; val sh = sw * 2.6f
                    val x0 = c.x - 4 * sw * 1.5f
                    for (k in 1..8) {
                        val band = knownShells[k]?.let { if (it) BsArt.Rust else BsArt.BlankBlue }
                        shell(Offset(x0 + (k - 1) * sw * 1.5f, size.height * 0.6f), sw, sh, band, spent = k <= fired)
                    }
                    // rivals' items: covered crates in front of their seats (how many, never which)
                    rivals.forEachIndexed { i, u ->
                        val (fx, fy) = spots[i]
                        val n = (players / u / "items").i() ?: 0
                        val cs = size.width * 0.032f
                        val base = Offset(size.width * (0.5f + (fx - 0.5f) * 0.7f) - cs * 2.2f, size.height * (0.42f + (fy - 0.42f) * 0.62f) + cs * 0.6f)
                        for (k in 0 until minOf(n, 8)) crate(base + Offset((k % 4) * cs * 1.15f, (k / 4) * cs * 0.95f), cs)
                    }
                }
                // facts about the magazine (no odds)
                Column(Modifier.align(Alignment.Center).offset(y = h * 0.24f).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).border(1.dp, BsArt.Edge, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp).testTag("bs_mag"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Yüklenen: ${(mag / "loaded_live").i() ?: 0} dolu · ${(mag / "loaded_blank").i() ?: 0} boş", color = BsArt.Bone, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Kalan mermi: $remaining", color = BsArt.Lamp, fontSize = 11.sp, modifier = Modifier.testTag("bs_remaining"))
                    known.minByOrNull { (it / "shell").i() ?: 99 }?.let { k ->
                        Text("Bildiğin: ${(k / "shell").i()}. mermi ${if ((k / "live").b()) "DOLU" else "BOŞ"}",
                            color = if ((k / "live").b()) BsArt.Rust else BsArt.BlankBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("bs_known"))
                    }
                }
                rivals.forEachIndexed { i, u ->
                    val (fx, fy) = spots[i]
                    Seat(ctx, u, players / u, (p / "max_hp").i() ?: 3, (p / "wins" / u).i() ?: 0, isTurn = u == turn && !over, aimed = aim == u,
                        tappable = myTurn && (players / u / "alive").b(), tagTarget = targeting,
                        modifier = Modifier.offset(x = w * fx - 46.dp, y = (h * fy - 44.dp).coerceAtLeast(0.dp))) { aim = u }
                }
                (p / "log").a().lastOrNull()?.let { e -> logLine(ctx, e) }?.let {
                    Text(it, color = BsArt.Bone.copy(alpha = 0.85f), fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp, start = 24.dp, end = 24.dp).testTag("bs_caption"))
                }
            }
            // ------------------------------------------------------------------ the player's edge of the table
            if (bottom != null) MyPlace(ctx, bottom, players / bottom, (p / "max_hp").i() ?: 3, (p / "wins" / bottom).i() ?: 0,
                isTurn = bottom == turn && !over, aimed = aim == bottom, myTurn = myTurn, isMe = me != null, inv = inv, picked = picked,
                onPick = { idx -> picked = if (picked == idx) null else idx; aim = null }, onAimSelf = { if (!targeting) aim = bottom })
            ActionBar(ctx, myTurn, aim, pickedItem, targeting, ui,
                onFire = { t -> ui.act("fire", payload("target" to t, "nonce" to nonce())) {}; aim = null },
                onUse = { id, t -> ui.act("item", payload("item" to id, "target" to t, "nonce" to nonce())) {}; picked = null; aim = null },
                onCancel = { picked = null; aim = null })
        }
        if (over) MoneyBag(ctx, (p / "winner").s(), (p / "bag").i() ?: 0, (p / "round").i() ?: 1)
    }
}

@Composable
private fun TopStrip(ctx: ModeContext, p: JsonObject, order: List<String>, turn: String?, over: Boolean) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF0B0B0E)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("RAUND ${(p / "round").i() ?: 1}", color = BsArt.Lamp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.testTag("bs_round"))
        Spacer(Modifier.width(8.dp))
        val won = order.sumOf { (p / "wins" / it).i() ?: 0 }
        repeat(3) { k ->
            Box(Modifier.padding(horizontal = 2.dp).size(16.dp).clip(CircleShape)
                .background(if (k < won) BsArt.Brass else BsArt.Steel).border(1.dp, BsArt.Edge, CircleShape))
        }
        Spacer(Modifier.weight(1f))
        Text(when {
            over -> "Raund bitti"
            turn == ctx.me -> "SIRA SENDE"
            else -> "Sıra: ${ctx.name(turn)}"
        }, color = if (turn == ctx.me && !over) BsArt.Lamp else BsArt.Bone, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.testTag("bs_turn"))
        Spacer(Modifier.width(8.dp))
        Text(if ((p / "dir").i() == -1) "⟲" else "⟳", color = BsArt.Bone.copy(alpha = 0.7f), fontSize = 16.sp)
    }
}

/** A rival's seat: avatar with a glow on turn, name, HP lamps, status. */
@Composable
private fun Seat(ctx: ModeContext, u: String, pl: JsonElement?, maxHp: Int, wins: Int, isTurn: Boolean, aimed: Boolean,
                 tappable: Boolean, tagTarget: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val alive = (pl / "alive").b()
    val reduced = LocalReducedMotion.current
    val glow = if (isTurn && !reduced) rememberInfiniteTransition(label = "seat").animateFloat(0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "g").value else 1f
    Column(modifier.width(92.dp).clickable(enabled = tappable, onClick = onTap).testTag("bs_seat_$u"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (isTurn || aimed) Box(Modifier.size(66.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf((if (aimed) BsArt.Rust else BsArt.Lamp).copy(alpha = 0.7f * glow), Color.Transparent))))
            Box(Modifier.size(52.dp).clip(CircleShape).border(2.dp, if (aimed) BsArt.Rust else if (isTurn) BsArt.Lamp else BsArt.Edge, CircleShape)
                .alpha(if (alive) 1f else 0.35f), contentAlignment = Alignment.Center) {
                AvatarView(ctx.avatar(u), size = 50.dp, showFrame = false)
            }
            if (!alive) Text("✕", color = BsArt.Rust, fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Text(ctx.name(u).take(10), color = if (alive) BsArt.Bone else BsArt.Bone.copy(alpha = 0.4f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        HpLamps((pl / "hp").i() ?: 0, maxHp, 7.dp)
        StatusLine(pl, wins, alive)
        if (tappable && tagTarget) Text("hedef seç", color = BsArt.Lamp, fontSize = 10.sp)
    }
}

@Composable
private fun HpLamps(hp: Int, max: Int, size: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp).testTag("bs_hp")) {
        repeat(max) { k ->
            Box(Modifier.size(size).clip(CircleShape).background(if (k < hp) BsArt.Rust else Color(0xFF3A2A28)).border(0.5.dp, Color.Black, CircleShape))
        }
    }
}

@Composable
private fun StatusLine(pl: JsonElement?, wins: Int, alive: Boolean) {
    val parts = buildList {
        if (!alive) add("ELENDİ")
        if ((pl / "cuffed").b()) add("kelepçeli")
        if ((pl / "saw").b()) add("testere")
        if (wins > 0) add("$wins çanta")
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = if (!alive) BsArt.Rust else BsArt.Lamp.copy(alpha = 0.9f), fontSize = 10.sp, maxLines = 1)
}

/** The player's own edge of the table: avatar, HP and the items lying in front of them. */
@Composable
private fun MyPlace(ctx: ModeContext, u: String, pl: JsonElement?, maxHp: Int, wins: Int, isTurn: Boolean, aimed: Boolean, myTurn: Boolean, isMe: Boolean,
                    inv: List<String>, picked: Int?, onPick: (Int) -> Unit, onAimSelf: () -> Unit) {
    val alive = (pl / "alive").b()
    Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(BsArt.Steel, Color(0xFF17181C)))).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(84.dp).clickable(enabled = myTurn, onClick = onAimSelf).testTag("bs_seat_$u"), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).clip(CircleShape).border(2.dp, if (aimed) BsArt.Rust else if (isTurn) BsArt.Lamp else BsArt.Edge, CircleShape)
                .alpha(if (alive) 1f else 0.35f), contentAlignment = Alignment.Center) { AvatarView(ctx.avatar(u), size = 54.dp, showFrame = false) }
            Text(if (isMe) "Sen" else ctx.name(u).take(10), color = BsArt.Bone, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            HpLamps((pl / "hp").i() ?: 0, maxHp, 8.dp)
            StatusLine(pl, wins, alive)
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(BsArt.Felt, Color(0xFF1E2621))))
            .border(1.dp, BsArt.Edge, RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState()).padding(6.dp).testTag("bs_tray"),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                !isMe -> Text("İzliyorsun", color = BsArt.Bone.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                inv.isEmpty() -> Text("Önünde eşya yok", color = BsArt.Bone.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                else -> inv.forEachIndexed { idx, id -> ItemObject(id, picked == idx, myTurn) { onPick(idx) } }
            }
        }
    }
}

/** An item lying on the felt; when picked it is lifted towards the table and lit. */
@Composable
private fun ItemObject(id: String, picked: Boolean, enabled: Boolean, onTap: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val lift by animateFloatAsState(if (picked) 1f else 0f, if (reduced) tween(0) else spring(dampingRatio = 0.55f, stiffness = 300f), label = "lift")
    Column(Modifier.width(58.dp).offset(y = (-8).dp * lift).scale(1f + 0.12f * lift).clickable(enabled = enabled, onClick = onTap).testTag("bs_item_$id"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.radialGradient(listOf(BsArt.Lamp.copy(alpha = 0.12f + 0.45f * lift), Color.Transparent)))) {
            Canvas(Modifier.fillMaxSize()) { item(id, Offset.Zero, size.minDimension) }
        }
        Text(BuckshotItems.info(id).name, color = if (picked) BsArt.Lamp else BsArt.Bone, fontSize = 10.sp, maxLines = 1)
    }
}

/** One big action: fire at the aimed seat, or use the picked item (on a target if it needs one). */
@Composable
private fun ActionBar(ctx: ModeContext, myTurn: Boolean, aim: String?, item: String?, targeting: Boolean, ui: ModeUi,
                      onFire: (String) -> Unit, onUse: (String, String?) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0B0B0E)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        val hint = when {
            !myTurn -> "Sıranı bekle. Süre biterse sunucu rastgele bir rakibe ateş eder."
            item != null -> BuckshotItems.info(item).let { "${it.name}: ${it.about}" + if (targeting) " Masadan bir rakip seç." else "" }
            aim == null -> "Önündeki bir eşyaya dokun ya da nişan almak için bir oyuncuya (kendin dahil) dokun."
            else -> "Tüfek ${if (aim == ctx.me) "sana" else ctx.name(aim)} dönük."
        }
        Text(hint, color = BsArt.Bone.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("bs_hint"))
        if (myTurn && (item != null || aim != null)) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(BsArt.Steel).clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Vazgeç", color = BsArt.Bone)
                }
                val ready = if (item != null) !targeting || aim != null else aim != null
                val label = when {
                    item != null -> if (targeting && aim != null) "${BuckshotItems.info(item).name} → ${ctx.name(aim)}" else "${BuckshotItems.info(item).name} KULLAN"
                    aim == ctx.me -> "KENDİNE ATEŞ ET"
                    else -> "ATEŞ ET → ${ctx.name(aim)}"
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (ready) BsArt.Rust else BsArt.Steel)
                    .clickable(enabled = ready && ui.busy == null) { if (item != null) onUse(item, aim.takeIf { targeting }) else aim?.let(onFire) }
                    .padding(vertical = 12.dp).testTag(if (item != null) "bs_use" else "bs_fire"), contentAlignment = Alignment.Center) {
                    Text(label, color = BsArt.Bone, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
        Text("Bahis yok. Raund galibi para çantası (coin) kazanır; kaybeden hiçbir şey kaybetmez.", color = BsArt.Bone.copy(alpha = 0.45f), fontSize = 10.sp, lineHeight = 13.sp,
            modifier = Modifier.padding(top = 4.dp))
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

/** Round over: the winner gets the money bag (server value). */
@Composable
private fun MoneyBag(ctx: ModeContext, winner: String?, bag: Int, round: Int) {
    val reduced = LocalReducedMotion.current
    val pulse = if (reduced) null else rememberInfiniteTransition(label = "bag").animateFloat(1f, 1.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).testTag("bs_round_over"), contentAlignment = Alignment.Center) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(BsArt.Steel).border(2.dp, BsArt.Lamp, RoundedCornerShape(22.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            AvatarView(ctx.avatar(winner), size = 72.dp, showFrame = false)
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.size(64.dp).scale(pulse?.value ?: 1f)) {
                val s = size.minDimension
                drawRoundRect(Color(0xFF6B4A2E), Offset(s * 0.15f, s * 0.3f), Size(s * 0.7f, s * 0.6f), CornerRadius(s * 0.12f))
                drawRoundRect(BsArt.Brass, Offset(s * 0.38f, s * 0.18f), Size(s * 0.24f, s * 0.16f), CornerRadius(s * 0.05f))
                drawCircle(BsArt.Brass, s * 0.1f, Offset(s * 0.5f, s * 0.6f))
            }
            Text(if (winner == ctx.me) "Raundu kazandın!" else "${ctx.name(winner)} raundu kazandı", color = BsArt.Bone, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                textAlign = TextAlign.Center)
            if (bag > 0) Text("Para çantası: +$bag coin", color = BsArt.Lamp, fontWeight = FontWeight.Bold)
            Text(if (round < 3) "Sonraki raund hazırlanıyor…" else "Maç bitti", color = BsArt.Bone.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}
