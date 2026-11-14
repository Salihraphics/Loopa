package com.loopa.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.GameModes
import com.loopa.core.game.LoopaException
import com.loopa.core.model.GamePrefs
import com.loopa.core.model.Leaderboard
import com.loopa.core.model.Progression
import com.loopa.core.model.QuestBoard
import com.loopa.core.model.Wallet
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaChip
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProgressHubViewModel(private val c: DataContainer) : ViewModel() {
    val progression = c.platform.progression
    private val _wallet = MutableStateFlow<Wallet?>(null); val wallet: StateFlow<Wallet?> = _wallet.asStateFlow()
    private val _quests = MutableStateFlow<QuestBoard?>(null); val quests: StateFlow<QuestBoard?> = _quests.asStateFlow()
    private val _board = MutableStateFlow<Leaderboard?>(null); val board: StateFlow<Leaderboard?> = _board.asStateFlow()
    private val _prefs = MutableStateFlow<GamePrefs?>(null); val prefs: StateFlow<GamePrefs?> = _prefs.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null); val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _note = MutableStateFlow<String?>(null); val note: StateFlow<String?> = _note.asStateFlow()

    private fun run(tag: String, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = tag
        try { block(); _error.value = null } catch (e: LoopaException) { _error.value = e.error.message }
        _busy.value = null
    }

    fun refresh() = run("load") {
        c.platform.refreshProgression()
        _wallet.value = c.platform.wallet()
        _quests.value = c.platform.quests()
    }
    fun loadBoard(kind: String) = run("board") { _board.value = c.platform.leaderboard(kind) }
    fun loadPrefs() = run("prefs") { _prefs.value = c.platform.gamePrefs() }
    fun claim(id: String) = run("claim:$id") {
        val r = c.platform.claimQuest(id)
        _note.value = if ((r["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "claimed") "Ödül alındı!" else "Bu ödül zaten alınmış."
        _quests.value = c.platform.quests(); _wallet.value = c.platform.wallet()
    }
    fun equip(title: String?, badges: List<String>) = run("showcase") { c.platform.setShowcase(title, badges) }
    fun createTeam(name: String) = run("team") { c.platform.createTeam(name) }
    fun joinTeam(code: String) = run("team") { c.platform.joinTeam(code) }
    fun leaveTeam() = run("team") { c.platform.leaveTeam() }
    fun savePrefs(likes: List<String>, dislikes: List<String>) = run("prefs") { c.platform.setGamePrefs(likes, dislikes); _prefs.value = c.platform.gamePrefs(); _note.value = "Tercihlerin kaydedildi." }
    fun dismiss() { _error.value = null; _note.value = null }
}

private val tagNames = mapOf("word" to "Kelime", "drawing" to "Çizim", "quiz" to "Bilgi", "social" to "Sosyal", "team" to "Takım",
    "duel" to "Düello", "ffa" to "Herkes tek", "card" to "Kart", "board" to "Masa", "strategy" to "Strateji")
private val rarityNames = mapOf("common" to "Yaygın", "rare" to "Nadir", "epic" to "Destansı", "legendary" to "Efsanevi", "seasonal" to "Sezonluk", "meme" to "Mizah")

/** 0.8.0 progression hub: level / prestige / reputation / coins, quests, leaderboards, badges, team, preferences. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressHubScreen(onBack: () -> Unit, initialTab: Int = 0) {
    val vm = containerViewModel { ProgressHubViewModel(it) }
    val prog by vm.progression.collectAsStateWithLifecycle()
    val wallet by vm.wallet.collectAsStateWithLifecycle()
    val quests by vm.quests.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(initialTab) }
    var boardKind by remember { mutableStateOf("weekly") }
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(tab, boardKind) { if (tab == 2) vm.loadBoard(boardKind); if (tab == 5) vm.loadPrefs() }

    Column(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        LoopaTopBar("İlerleme", onBack = onBack)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            listOf("İlerleme", "Görevler", "Sıralama", "Rozetler", "Takım", "Tercihler").forEachIndexed { i, t ->
                Tab(tab == i, { tab = i; vm.dismiss() }, text = { Text(t) }, modifier = Modifier.testTag("hub_tab_$i"))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ErrorBanner(error, onDismiss = vm::dismiss)
            note?.let { Text(it, color = LoopaPalette.MintDeep, fontWeight = FontWeight.Bold) }
            val p = prog
            when (tab) {
                0 -> if (p != null) Overview(p, wallet) else Text("Yükleniyor…", color = Loopa.colors.textSoft)
                1 -> QuestsTab(quests, busy, vm::claim)
                2 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoopaChip("Bu hafta", boardKind == "weekly", { boardKind = "weekly" }, color = LoopaPalette.Butter)
                        LoopaChip("Tüm zamanlar", boardKind == "total", { boardKind = "total" }, color = LoopaPalette.Sky)
                    }
                    BoardTab(board, p?.userId)
                }
                3 -> if (p != null) BadgesTab(p, busy == "showcase", vm::equip)
                4 -> if (p != null) TeamTab(p, busy == "team", vm::createTeam, vm::joinTeam, vm::leaveTeam)
                5 -> prefs?.let { PrefsTab(it, busy == "prefs", vm::savePrefs) }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Loopa.colors.card).border(1.5.dp, Loopa.colors.outline, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Overview(p: Progression, wallet: Wallet?) {
    Card {
        Text("Seviye ${p.level}${if (p.prestige > 0) " · Prestij ${p.prestige}" else ""}", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, modifier = Modifier.testTag("hub_level"))
        Text("Toplam XP ${p.xp} · bu hafta ${p.weeklyXp}", color = Loopa.colors.textSoft)
        Text(if (p.level >= p.prestigeLevelRequired) "Prestij seviyesine ulaştın; XP harcanmaz, birikmeye devam eder."
             else "Prestij için seviye ${p.prestigeLevelRequired}. XP hiçbir zaman harcanmaz.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🪙 ${wallet?.coins ?: p.coins ?: 0} LOOPA Coin", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text, modifier = Modifier.weight(1f).testTag("hub_coins"))
            Text("⭐ itibar ${p.reputation}", color = Loopa.colors.text)
        }
        Text("Coin'ler maç, görev ve turnuvalardan kazanılır; kozmetik almak için kullanılır. Oyun avantajı satılmaz.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
        wallet?.recent?.take(5)?.forEach { t ->
            Text("${if (t.amount >= 0) "+" else ""}${t.amount} · ${reasonName(t.reason)}", style = MaterialTheme.typography.bodySmall, color = if (t.amount >= 0) LoopaPalette.MintDeep else LoopaPalette.PeachDeep)
        }
    }
    Card {
        Text("🔥 Seri: ${p.streak} (en iyi ${p.bestStreak})${if (p.streak >= 3) " 👑" else ""}", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text, modifier = Modifier.testTag("hub_streak"))
        Text("Sonraki galibiyet çarpanı ×${"%.2f".format(p.streakMultiplier)} (bireysel ve takım çarpanı birlikte uygulanmaz; yüksek olan geçerli).",
            style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
        p.team?.let { Text("Takım: ${it.name} · seri ${it.currentStreak}", color = Loopa.colors.text) }
        if (p.trophies > 0) Text("🏆 Turnuva şampiyonluğu: ${p.trophies}", color = Loopa.colors.text)
    }
    if (p.gameStats.isNotEmpty()) Card {
        Text("Oyun istatistikleri", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        p.gameStats.forEach { g ->
            Text("${GameModes.name(g.gameMode)}: ${g.matchesPlayed} maç · ${g.wins} galibiyet (%${g.winRate})" +
                (if (g.playSeconds > 0) " · ${g.playSeconds / 60} dk" else "") +
                g.extra.entries.take(4).joinToString("") { (k, v) -> " · ${statName(k)} ${(v as? kotlinx.serialization.json.JsonPrimitive)?.content}" },
                style = MaterialTheme.typography.bodySmall, color = Loopa.colors.text)
        }
    }
}

private fun reasonName(r: String) = when (r) {
    "match" -> "maç"; "quest" -> "görev"; "tournament" -> "turnuva"; "purchase" -> "satın alma"; "admin" -> "admin"; else -> r
}
private fun statName(k: String) = when (k) {
    "hands_won" -> "kazanılan el"; "busts" -> "21'i geçme"; "mars_won" -> "mars"; "captures" -> "yeme"; "shots" -> "atış"; "hits" -> "isabet"
    "rounds_won" -> "kazanılan el"; "capitals_taken" -> "başkent"; "battles_won" -> "savaş"; "max_tiles" -> "en çok karo"; "games" -> "oyun"; "wins" -> "galibiyet"
    else -> k.replace('_', ' ')
}

@Composable
private fun QuestsTab(board: QuestBoard?, busy: String?, onClaim: (String) -> Unit) {
    if (board == null) { Text("Yükleniyor…", color = Loopa.colors.textSoft); return }
    listOf("daily" to "Günlük görevler", "weekly" to "Haftalık görevler").forEach { (period, title) ->
        val qs = board.quests.filter { it.period == period }
        if (qs.isEmpty()) return@forEach
        Text(title, style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        qs.forEach { q ->
            Card {
                Text(q.title, fontWeight = FontWeight.Bold, color = Loopa.colors.text)
                LinearProgressIndicator(progress = { (q.progress.toFloat() / q.target.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${q.progress}/${q.target} · 🪙${q.coins} · ${q.xp} XP", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, modifier = Modifier.weight(1f))
                    when {
                        q.claimed -> Text("Alındı ✓", color = LoopaPalette.MintDeep)
                        q.completed -> LoopaButton("Ödülü al", { onClaim(q.id) }, loading = busy == "claim:${q.id}", compact = true, color = LoopaPalette.Mint,
                            modifier = Modifier.testTag("quest_claim_${q.id}"))
                        else -> {}
                    }
                }
            }
        }
    }
    Text("Görevler her gün 00:00'da, haftalıklar pazartesi yenilenir (Türkiye saati). Ödülü sunucu bir kez verir.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
}

@Composable
private fun BoardTab(board: Leaderboard?, me: String?) {
    if (board == null) { Text("Yükleniyor…", color = Loopa.colors.textSoft); return }
    board.me?.let { Text("Senin sıran: ${it.rank} (${it.score} XP)", fontWeight = FontWeight.Bold, color = Loopa.colors.text, modifier = Modifier.testTag("board_me")) }
    Column(Modifier.testTag("board_rows"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        board.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (r.userId == me) LoopaPalette.Lavender.copy(alpha = 0.4f) else Loopa.colors.card).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${r.rank}.", modifier = Modifier.width(34.dp), fontWeight = FontWeight.Bold, color = Loopa.colors.text)
                AvatarView(r.avatar, size = 30.dp, showFrame = false)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.username + (if (r.streak >= 3) " 👑" else ""), color = Loopa.colors.text, fontWeight = FontWeight.Bold)
                    Text("Sv ${r.level}${r.title?.let { " · ${it.name}" } ?: ""}${if (r.streak > 0) " · 🔥${r.streak}" else ""}", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                }
                Text("${r.score ?: 0}", fontWeight = FontWeight.Bold, color = Loopa.colors.text)
            }
        }
    }
    if (board.kind == "weekly") Text("Haftalık sıralama pazartesi 00:00'da sıfırlanır; toplam XP hiç azalmaz.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgesTab(p: Progression, busy: Boolean, onEquip: (String?, List<String>) -> Unit) {
    val titles = p.badges.filter { it.kind == "title" }
    val badges = p.badges.filter { it.kind != "title" }
    var title by remember(p) { mutableStateOf(titles.firstOrNull { it.equipped }?.id) }
    var shown by remember(p) { mutableStateOf(badges.filter { it.equipped }.map { it.id }.toSet()) }
    if (p.badges.isEmpty()) Text("Henüz rozetin yok. Maç oyna, görevleri tamamla, turnuvalara katıl!", color = Loopa.colors.textSoft)
    if (titles.isNotEmpty()) {
        Text("Ünvan (adının altında görünür)", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LoopaChip("Yok", title == null, { title = null }, color = LoopaPalette.Sky)
            titles.forEach { t -> LoopaChip(t.name, title == t.id, { title = t.id }, color = LoopaPalette.Butter) }
        }
    }
    if (badges.isNotEmpty()) {
        Text("Rozetler (vitrinde en fazla 3)", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        badges.forEach { b ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (b.id in shown) LoopaPalette.Mint.copy(alpha = 0.35f) else Loopa.colors.card).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${b.name} · ${rarityNames[b.rarity] ?: b.rarity.orEmpty()}", fontWeight = FontWeight.Bold, color = Loopa.colors.text)
                    b.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft) }
                }
                TextButton({ shown = if (b.id in shown) shown - b.id else if (shown.size < 3) shown + b.id else shown }) { Text(if (b.id in shown) "Kaldır" else "Vitrine koy") }
            }
        }
    }
    if (p.badges.isNotEmpty()) LoopaButton("Vitrini kaydet", { onEquip(title, shown.toList()) }, loading = busy, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("showcase_save"))
}

@Composable
private fun TeamTab(p: Progression, busy: Boolean, onCreate: (String) -> Unit, onJoin: (String) -> Unit, onLeave: () -> Unit) {
    val t = p.team
    if (t != null) {
        Card {
            Text(t.name, style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, modifier = Modifier.testTag("team_name"))
            Text("Takım serisi ${t.currentStreak} · en iyi ${t.bestStreak}", color = Loopa.colors.textSoft)
            t.joinCode?.let { Text("Katılım kodu: $it", fontWeight = FontWeight.Bold, color = Loopa.colors.text, modifier = Modifier.testTag("team_code")) }
            t.members.forEach { m -> Row(verticalAlignment = Alignment.CenterVertically) { AvatarView(m.avatar, size = 28.dp, showFrame = false); Spacer(Modifier.width(8.dp)); Text(m.username, color = Loopa.colors.text) } }
            Text("Takım serisi yalnızca takımın tamamı aynı tarafta oynadığında sayılır.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
        }
        LoopaButton("Takımdan ayrıl", onLeave, loading = busy, color = LoopaPalette.Peach, modifier = Modifier.fillMaxWidth())
    } else {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        Card {
            Text("Takım kur", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
            LoopaTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth().testTag("team_create_name"), placeholder = "Takım adı")
            LoopaButton("Kur", { onCreate(name) }, enabled = name.trim().length >= 3, loading = busy, color = LoopaPalette.Mint, compact = true)
        }
        Card {
            Text("Koda göre katıl", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
            LoopaTextField(code, { code = it.take(8) }, Modifier.fillMaxWidth().testTag("team_join_code"), placeholder = "Katılım kodu")
            LoopaButton("Katıl", { onJoin(code) }, enabled = code.trim().length >= 4, loading = busy, color = LoopaPalette.Sky, compact = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrefsTab(prefs: GamePrefs, busy: Boolean, onSave: (List<String>, List<String>) -> Unit) {
    var likes by remember(prefs) { mutableStateOf(prefs.likes.toSet()) }
    var dislikes by remember(prefs) { mutableStateOf(prefs.dislikes.toSet()) }
    Text("Oyun çarkı odadaki herkesin tercihlerine göre ağırlık verir; sonucu sunucu seçer.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
    Text("Sevdiklerin", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prefs.tags.forEach { t -> LoopaChip(tagNames[t] ?: t, t in likes, { likes = if (t in likes) likes - t else likes + t; dislikes = dislikes - t }, color = LoopaPalette.Mint) }
    }
    Text("Sevmediklerin", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prefs.tags.forEach { t -> LoopaChip(tagNames[t] ?: t, t in dislikes, { dislikes = if (t in dislikes) dislikes - t else dislikes + t; likes = likes - t }, color = LoopaPalette.Peach) }
    }
    Spacer(Modifier.height(4.dp))
    LoopaButton("Kaydet", { onSave(likes.toList(), dislikes.toList()) }, loading = busy, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("prefs_save"))
    Box {}
}
