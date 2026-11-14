package com.loopa.feature.game.modes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.LocalReducedMotion
import kotlinx.serialization.json.JsonElement

/** Role names and one-line descriptions shown to the role's owner. */
internal object KurtAdamRoles {
    val names = mapOf(
        "kurt" to "Kurt Adam", "shapeshifter" to "Şekil Değiştiren", "alpha" to "Alfa Kurt", "silencer" to "Susturucu",
        "brute" to "Vahşi Kurt", "illusionist" to "İllüzyonist", "wolf_cub" to "Kurt Yavrusu", "seer" to "Kâhin", "doctor" to "Doktor",
        "hunter" to "Avcı", "witch" to "Cadı", "detective" to "Dedektif", "guardian" to "Muhafız", "medium" to "Medyum",
        "mayor" to "Muhtar", "villager" to "Köylü", "jester" to "Soytarı", "assassin" to "Suikastçı",
    )
    val about = mapOf(
        "kurt" to "Geceleri sürünle birlikte bir kurban seç.",
        "shapeshifter" to "Bir kez: hedefini ortadan kaldırır, onun koltuğunda oyuna devam edersin.",
        "alpha" to "Kâhin seni köylü olarak görür.",
        "silencer" to "Gece birini sustur; ertesi gün konuşamaz. Aynı kişi iki gece üst üste olmaz.",
        "brute" to "Bir kez: korumaların durduramadığı özel saldırı.",
        "illusionist" to "Araştırmalarda o geceye ait sahte bir köylü rolü gibi görünürsün.",
        "wolf_cub" to "Ölürsen yanında son bir oyuncuyu götürürsün; hiçbir koruma işlemez.",
        "seer" to "Her gece bir oyuncunun kurt olup olmadığını öğren.",
        "doctor" to "Her gece bir oyuncuyu kurt saldırısından koru.",
        "hunter" to "Bir kez: gece bir ok at. Yalnızca Muhafız oku üstlenebilir.",
        "witch" to "Bir iyileştirme ve bir zehir iksirin var; ikisi aynı kişiye kullanılamaz.",
        "detective" to "İki oyuncunun aynı takımda olup olmadığını öğren.",
        "guardian" to "Bir oyuncuyu koru; ona gelen saldırıyı sen üstlenirsin.",
        "medium" to "Bir kez: önceki gecelerde elenmiş birini geri getir.",
        "mayor" to "Oylamada oyun iki sayılır.",
        "villager" to "Gece yeteneğin yok; gündüz konuş, kurtları bul.",
        "jester" to "Linç edilirsen tek başına kazanırsın.",
        "assassin" to "Her gece birini hedef alabilirsin. Son kalan sen olursan kazanırsın.",
    )
    fun wolf(role: String?) = role in setOf("kurt", "shapeshifter", "alpha", "silencer", "brute", "illusionist", "wolf_cub")
    val targetRoles = setOf("doctor", "guardian", "shapeshifter", "silencer", "seer", "assassin", "hunter")
}

/** Kurt Adam: moderatorless; the server runs phases, resolves the night and counts votes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KurtAdamScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val me = mode.me
    val phase = (p / "phase").s()
    val over = mode.over || (p / "winner").s() != null
    val seats = (p / "seats").a()
    val role = (me / "role").s()
    val alive = (me / "alive").b()
    val mySeat = (me / "seat").i()
    val used = (me / "used").strs()
    val wolfMates = (me / "wolf_mates").ints().toSet()
    val votesCount = (p / "votes_count").o()
    val silenced = (p / "silenced_seat").i()
    var pick by remember(phase, (p / "night").i()) { mutableStateOf("target") }
    var bruteSpecial by remember(phase) { mutableStateOf(false) }
    var detA by remember(phase) { mutableStateOf<Int?>(null) }

    fun onSeat(seat: Int) {
        if (!alive || over || ctx.isSpectator) return
        when (phase) {
            "night" -> when {
                pick == "attack" -> ui.act("act", payload("attack" to seat, "special" to bruteSpecial)) { ui.flash("Saldırı oyun kaydedildi") }
                role == "witch" && pick == "heal" -> ui.act("act", payload("heal" to seat)) { ui.flash("İyileştirme hazır") }
                role == "witch" && pick == "poison" -> ui.act("act", payload("poison" to seat)) { ui.flash("Zehir hazır") }
                role == "detective" -> if (detA == null) detA = seat else { val a = detA; detA = null; ui.act("act", payload("a" to a, "b" to seat)) { ui.flash("İnceleme kaydedildi") } }
                role in KurtAdamRoles.targetRoles -> ui.act("act", payload("target" to seat)) { ui.flash("Seçimin kaydedildi") }
            }
            "day", "vote" -> ui.act("vote", payload("seat" to seat)) {}
            "cub" -> if ((me / "cub_last").b()) ui.act("last", payload("seat" to seat)) {}
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhaseBanner(phase, over, (p / "winner").s(), (p / "night").i() ?: 1, (p / "day").i() ?: 0)
        if (!ctx.isSpectator && role != null) RoleCard(role, alive, wolfMates)
        EventFeed((me / "events").a())
        // morning: what happened in the night
        if (phase == "morning" || phase == "dusk") {
            val deaths = (p / "deaths").a()
            val revived = (p / "revived").ints()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Loopa.colors.card).padding(12.dp)) {
                if (phase == "dusk") Text((p / "lynch").i()?.let { "Köy ${seatName(seats, it)} oyuncusunu linç etti." } ?: "Bugün kimse linç edilmedi.", color = Loopa.colors.text)
                if (phase == "morning") {
                    if (deaths.isEmpty()) Text("Bu gece kimse ölmedi.", color = Loopa.colors.text)
                    deaths.forEach { d -> Text("☠ ${seatName(seats, (d / "seat").i() ?: 0)} gece hayatını kaybetti.", color = Loopa.colors.text) }
                    revived.forEach { r -> Text("✨ ${seatName(seats, r)} geri döndü!", color = LoopaPalette.MintDeep) }
                }
            }
        }
        // seats
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("ka_seats")) {
            seats.forEach { s ->
                val n = (s / "seat").i() ?: 0
                val isAlive = (s / "alive").b()
                val target = (me / "my_action").let { a -> (a / "target").i() == n || (a / "attack").i() == n || (a / "heal").i() == n || (a / "poison").i() == n }
                val voted = (me / "my_vote").s() == n.toString()
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(14.dp))
                        .background(when { !isAlive -> Loopa.colors.cardHigh; n == mySeat -> LoopaPalette.Lavender.copy(alpha = 0.5f); target || voted -> LoopaPalette.Butter; else -> Loopa.colors.card })
                        .border(if (n in wolfMates) 2.dp else 1.dp, if (n in wolfMates) LoopaPalette.Error else Loopa.colors.outline, RoundedCornerShape(14.dp))
                        .clickable(enabled = isAlive && alive && !over) { onSeat(n) }.padding(6.dp).alpha(if (isAlive) 1f else 0.5f).testTag("ka_seat_$n"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(PaletteSeat(n)), contentAlignment = Alignment.Center) {
                        Text(if (isAlive) "$n" else "☠", fontWeight = FontWeight.Bold, color = LoopaPalette.Ink)
                    }
                    Text(((s / "name").s() ?: "Oyuncu").take(9), style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text, textAlign = TextAlign.Center)
                    if (n == silenced) Text("🔇", fontSize = 12.sp)
                    votesCount[n.toString()]?.i()?.let { Text("🗳 $it", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text) }
                    if (over) (p / "roles" / n.toString()).s()?.let { Text(KurtAdamRoles.names[it] ?: it, style = MaterialTheme.typography.labelSmall, color = LoopaPalette.PeachDeep) }
                }
            }
        }
        // controls
        if (!over && alive && !ctx.isSpectator) when (phase) {
            "night" -> NightControls(role, used, pick, { pick = it }, bruteSpecial, { bruteSpecial = it }, detA, me, ui)
            "day", "vote" -> {
                Text(if (phase == "vote") "Oylama! Linç etmek istediğin oyuncuya dokun." else "Tartışma. İstersen oyunu şimdiden verebilirsin.", color = Loopa.colors.text)
                GameButton("Oy vermeden geç", primary = false, tag = "ka_skip") { ui.act("vote", payload("seat" to "skip")) {} }
            }
            "cub" -> if ((me / "cub_last").b()) Text("Kurt Yavrusu olarak son bir oyuncuyu seç.", color = LoopaPalette.Error, fontWeight = FontWeight.Bold)
                     else Text("Kurt Yavrusu son hamlesini yapıyor…", color = Loopa.colors.textSoft)
        }
        if (!alive && !ctx.isSpectator && !over) WaitingNote("Elendin. Oyunu izlemeye devam edebilirsin; rolün oyun sonunda açıklanır.")
        // chat (day phases)
        val chat = (p / "chat").a()
        if (phase in setOf("day", "vote", "morning", "dusk") || chat.isNotEmpty()) {
            SectionTitle("Köy meydanı")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Loopa.colors.card).padding(10.dp).testTag("ka_chat"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (chat.isEmpty()) Text("Henüz kimse konuşmadı.", color = Loopa.colors.textSoft, style = MaterialTheme.typography.bodySmall)
                chat.takeLast(25).forEach { c -> Text("${seatName(seats, (c / "seat").i() ?: 0)}: ${(c / "text").s()}", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.text) }
            }
            if (alive && !over && !ctx.isSpectator && phase in setOf("day", "vote", "morning", "dusk")) {
                if (silenced == mySeat) Text("Bugün susturuldun.", color = LoopaPalette.Error)
                else GuessInput("Mesaj yaz", enabled = true, busy = ui.busy == "chat", accent = LoopaPalette.Lavender, tag = "ka_chat_input", maxLength = 200,
                    hint = { null }, onSend = { t -> ui.act("chat", payload("text" to t)) {} })
            }
        }
        WaitingNote("Moderatör LOOPA: gece eylemleri gizlidir, sonuçlar sabah açıklanır. Roller ölümde açıklanmaz; oyun sonunda herkesin rolü gösterilir.")
    }
}

private fun seatName(seats: List<JsonElement>, seat: Int): String = seats.firstOrNull { (it / "seat").i() == seat }?.let { (it / "name").s() } ?: "Koltuk $seat"
private fun PaletteSeat(n: Int): Color = LoopaPalette.accent(n)

@Composable
private fun PhaseBanner(phase: String?, over: Boolean, winner: String?, night: Int, day: Int) {
    val (title, bg) = when {
        over -> (when (winner) { "village" -> "Köylüler kazandı!"; "wolf" -> "Kurtlar kazandı!"; "jester" -> "Soytarı kazandı!"; "assassin" -> "Suikastçı kazandı!"; else -> "Oyun bitti" }) to LoopaPalette.Butter
        phase == "night" -> "Gece $night — köy uyuyor" to Color(0xFF3F3D64)
        phase == "morning" -> "Sabah oldu" to LoopaPalette.Peach
        phase == "day" -> "Gün $day — tartışma" to LoopaPalette.Sky
        phase == "vote" -> "Oylama" to LoopaPalette.Lavender
        phase == "dusk" -> "Akşam" to LoopaPalette.PeachDeep
        phase == "cub" -> "Kurt Yavrusunun son hamlesi" to Color(0xFF5D4037)
        else -> "Kurt Adam" to Loopa.colors.card
    }
    val dark = phase == "night" || phase == "cub"
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).padding(14.dp).testTag("ka_phase"), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (dark) LoopaPalette.Cream else LoopaPalette.Ink)
    }
}

@Composable
private fun RoleCard(role: String, alive: Boolean, mates: Set<Int>) {
    val wolf = KurtAdamRoles.wolf(role)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (wolf) Color(0xFFFFCDD2) else if (role in setOf("jester", "assassin")) LoopaPalette.Butter else LoopaPalette.Mint)
        .border(2.dp, LoopaPalette.Ink.copy(alpha = 0.3f), RoundedCornerShape(18.dp)).padding(12.dp).testTag("ka_role")) {
        Text("Rolün: ${KurtAdamRoles.names[role] ?: role}${if (!alive) " (elendin)" else ""}", fontWeight = FontWeight.Bold, color = LoopaPalette.Ink)
        Text(KurtAdamRoles.about[role].orEmpty(), style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink)
        if (wolf && mates.isNotEmpty()) Text("Sürün: koltuk ${mates.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NightControls(role: String?, used: List<String>, pick: String, setPick: (String) -> Unit, special: Boolean, setSpecial: (Boolean) -> Unit,
                          detA: Int?, me: JsonElement?, ui: ModeUi) {
    val wolf = KurtAdamRoles.wolf(role)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (role in KurtAdamRoles.targetRoles && !(role == "hunter" && "arrow" in used) && !(role == "shapeshifter" && "shift" in used))
                FilterChip(pick == "target", { setPick("target") }, label = { Text(when (role) { "seer" -> "İncele"; "doctor", "guardian" -> "Koru"; "silencer" -> "Sustur"; "hunter" -> "Ok at"; "shapeshifter" -> "Dönüş"; else -> "Hedef" }) })
            if (wolf) FilterChip(pick == "attack", { setPick("attack") }, label = { Text("Saldırı oyu") }, modifier = Modifier.testTag("ka_pick_attack"))
            if (role == "witch") {
                if ("heal" !in used) FilterChip(pick == "heal", { setPick("heal") }, label = { Text("İyileştir") })
                if ("poison" !in used) FilterChip(pick == "poison", { setPick("poison") }, label = { Text("Zehirle") })
            }
        }
        if (role == "brute" && pick == "attack" && "special" !in used) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(special, setSpecial); Text("Özel saldırı (bir kez, korumalar işlemez)", color = Loopa.colors.text)
        }
        when {
            role == "detective" -> Text(if (detA == null) "İlk oyuncuyu seç." else "Koltuk $detA seçildi; ikinci oyuncuyu seç.", color = Loopa.colors.text)
            role == "medium" -> {
                val dead = (me / "dead").a()
                if ("revive" in used) Text("Diriltme hakkını kullandın.", color = Loopa.colors.textSoft)
                else if (dead.isEmpty()) Text("Geri getirilebilecek kimse yok.", color = Loopa.colors.textSoft)
                else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dead.forEach { d -> GameButton((d / "name").s() ?: "Oyuncu", primary = false) { ui.act("act", payload("revive" to (d / "user").s())) { ui.flash("Diriltme hazır") } } }
                }
            }
            role in setOf("villager", "mayor", "jester") -> Text("Gece yeteneğin yok. Sabahı bekle…", color = Loopa.colors.textSoft)
            else -> Text("Seçim yapmak için bir koltuğa dokun.", color = Loopa.colors.textSoft)
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** Private night results with a short animation (shield, silence, transform, revive); none with reduced motion. */
@Composable
private fun EventFeed(events: List<JsonElement>) {
    if (events.isEmpty()) return
    val last = events.last()
    val reduced = LocalReducedMotion.current
    val anim = remember { Animatable(1f) }
    LaunchedEffect(events.size) {
        if (!reduced) { anim.snapTo(0.4f); anim.animateTo(1f, tween(450)) }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Loopa.colors.card).padding(10.dp).testTag("ka_events"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(eventIcon(last), fontSize = 30.sp, modifier = Modifier.scale(anim.value))
            Spacer(Modifier.width(8.dp))
            Text(eventText(last), color = Loopa.colors.text, fontWeight = FontWeight.Bold)
        }
        events.dropLast(1).takeLast(4).reversed().forEach { Text("${eventIcon(it)} ${eventText(it)}", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft) }
    }
}

private fun eventIcon(e: JsonElement): String = when ((e / "t").s()) {
    "protected", "guarded" -> "🛡"; "silenced" -> "🔇"; "transformed" -> "🐺"; "revived" -> "✨"
    "seer" -> "🔮"; "detective" -> "🔎"; "eliminated" -> "☠"; else -> "•"
}

private fun eventText(e: JsonElement): String = when ((e / "t").s()) {
    "protected" -> "Bu gece saldırıya uğradın ama korundun."
    "guarded" -> "Muhafız sana gelen saldırıyı üstlendi."
    "silenced" -> "Susturuldun: bugün konuşamazsın."
    "transformed" -> "Dönüşüm tamamlandı; yeni koltuğundasın."
    "revived" -> "Medyum seni geri getirdi!"
    "seer" -> "Koltuk ${(e / "seat").i()}: ${if ((e / "wolf").b()) "KURT" else "kurt değil"}"
    "detective" -> "Koltuk ${(e / "a").i()} ve ${(e / "b").i()}: ${if ((e / "same").b()) "aynı takımda" else "farklı takımlarda"}"
    "eliminated" -> "Elendin."
    else -> (e / "t").s().orEmpty()
}
