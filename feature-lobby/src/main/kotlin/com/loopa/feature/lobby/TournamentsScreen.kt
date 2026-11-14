package com.loopa.feature.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.loopa.core.game.ModeOptions
import com.loopa.core.model.TournamentDetail
import com.loopa.core.model.TournamentSummary
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.GameIcon
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaChip
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.LoopaTopBar
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TournamentsViewModel(private val c: DataContainer) : ViewModel() {
    private val _list = MutableStateFlow<List<TournamentSummary>?>(null); val list: StateFlow<List<TournamentSummary>?> = _list.asStateFlow()
    private val _detail = MutableStateFlow<TournamentDetail?>(null); val detail: StateFlow<TournamentDetail?> = _detail.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null); val busy: StateFlow<String?> = _busy.asStateFlow()
    val me: String? get() = c.backend.currentUserId

    private fun run(tag: String, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = tag
        try { block(); _error.value = null } catch (e: LoopaException) { _error.value = e.error.message }
        _busy.value = null
    }
    fun refresh() = run("list") { _list.value = c.platform.tournaments() }
    fun open(id: String?) { _detail.value = null; if (id != null) run("detail") { _detail.value = c.platform.tournament(id) } }
    fun reload() { _detail.value?.id?.let { id -> viewModelScope.launch { runCatching { _detail.value = c.platform.tournament(id) } } } }
    fun create(name: String, mode: String, format: String, max: Int, onDone: (String) -> Unit) = run("create") {
        val r = c.platform.createTournament(name, mode, format, max, ModeOptions.normalize(mode, JsonObject(emptyMap())))
        val id = ((r as? JsonObject)?.get("id") as? JsonPrimitive)?.content ?: (r as? JsonPrimitive)?.content
        _list.value = c.platform.tournaments()
        id?.let(onDone)
    }
    fun join(id: String) = run("join") { c.platform.joinTournament(id); _detail.value = c.platform.tournament(id) }
    fun leave(id: String) = run("leave") { c.platform.leaveTournament(id); _detail.value = c.platform.tournament(id) }
    fun start(id: String) = run("start") { c.platform.startTournament(id); _detail.value = c.platform.tournament(id) }
    fun cancel(id: String) = run("cancel") { c.platform.cancelTournament(id); _detail.value = c.platform.tournament(id) }
    fun playMatch(matchId: String, onRoom: (String) -> Unit) = run("play") {
        val r = c.platform.joinTournamentMatch(matchId)
        (r["id"] as? JsonPrimitive)?.content?.let(onRoom)
    }
    fun dismiss() { _error.value = null }
}

private fun formatName(f: String) = when (f) { "duel" -> "1v1 eleme"; "team" -> "Takım eleme"; "ffa" -> "Herkes tek (her tur sonuncu elenir)"; else -> f }
private fun statusName(s: String) = when (s) { "registration" -> "Kayıt açık"; "running" -> "Oynanıyor"; "finished" -> "Bitti"; "cancelled" -> "İptal"; else -> s }

/** Tournaments: 1v1 and team brackets, FFA elimination. Results come only from the server's match data. */
@Composable
fun TournamentsScreen(onBack: () -> Unit, onRoomReady: (String) -> Unit) {
    val vm = containerViewModel { TournamentsViewModel(it) }
    val list by vm.list.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    // a running tournament changes as matches finish: keep the bracket fresh while it is open
    LaunchedEffect(detail?.id, detail?.status) { while (detail?.status in setOf("registration", "running")) { delay(5_000); vm.reload() } }

    Column(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        LoopaTopBar(detail?.name ?: "Turnuvalar", onBack = { if (detail != null) vm.open(null) else if (creating) creating = false else onBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ErrorBanner(error, onDismiss = vm::dismiss)
            val d = detail
            when {
                d != null -> Detail(d, vm.me, busy, vm, onRoomReady)
                creating -> Create(busy == "create") { name, mode, format, max -> vm.create(name, mode, format, max) { creating = false; vm.open(it) } }
                else -> {
                    LoopaButton("Turnuva oluştur", { creating = true }, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("tm_create"))
                    Text("Ödüller mütevazıdır ve yalnızca gerçekten oynanmış finallerde verilir (en az 4 farklı katılımcı, günlük sınır).",
                        style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                    if (list == null) Text("Yükleniyor…", color = Loopa.colors.textSoft)
                    if (list?.isEmpty() == true) Text("Şu an açık turnuva yok. İlkini sen kur!", color = Loopa.colors.textSoft)
                    list.orEmpty().forEach { t ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Loopa.colors.card).border(1.dp, Loopa.colors.outline, RoundedCornerShape(18.dp))
                            .clickable { vm.open(t.id) }.padding(12.dp).testTag("tm_row_${t.id}"), verticalAlignment = Alignment.CenterVertically) {
                            GameIcon(t.gameMode, size = 40.dp)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(t.name, fontWeight = FontWeight.Bold, color = Loopa.colors.text)
                                Text("${GameModes.name(t.gameMode)} · ${formatName(t.format)} · ${t.entriesCount}/${t.maxEntries} · ${statusName(t.status)}${if (t.joined) " · katıldın" else ""}",
                                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Create(busy: Boolean, onCreate: (String, String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("tavla") }
    var format by remember { mutableStateOf("duel") }
    var max by remember { mutableStateOf(8) }
    Text("Oyun", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GameModes.all.forEach { g -> LoopaChip(g.name, mode == g.id, { mode = g.id }, color = com.loopa.core.ui.components.gameVisual(g.id).color) }
    }
    Text("Biçim", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("duel", "team", "ffa").forEach { f -> LoopaChip(formatName(f), format == f, { format = f }, color = LoopaPalette.Sky) }
    }
    Text("Katılımcı sınırı", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(4, 8, 16, 32).forEach { m -> LoopaChip("$m", max == m, { max = m }, color = LoopaPalette.Butter) }
    }
    LoopaTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth().testTag("tm_name"), placeholder = "Turnuva adı")
    LoopaButton("Oluştur", { onCreate(name, mode, format, max) }, enabled = name.trim().length >= 3, loading = busy, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("tm_create_send"))
    Text("Takım turnuvasında takımının tamamı katılır (Profil → İlerleme → Takım). Sunucu biçimi oyunun desteklediğiyle karşılaştırır.",
        style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
}

@Composable
private fun Detail(d: TournamentDetail, me: String?, busy: String?, vm: TournamentsViewModel, onRoomReady: (String) -> Unit) {
    val mine = d.entries.firstOrNull { it.mine }
    val host = d.hostId == me
    val entryName: (String?) -> String = { id -> d.entries.firstOrNull { it.id == id }?.let { e -> e.name ?: e.players.joinToString(" & ") { it.username } } ?: "—" }
    Text("${GameModes.name(d.gameMode)} · ${formatName(d.format)} · ${statusName(d.status)}${if (d.currentRound > 0) " · tur ${d.currentRound}" else ""}",
        color = Loopa.colors.text, modifier = Modifier.testTag("tm_status"))
    d.championEntry?.let { Text("🏆 Şampiyon: ${entryName(it)}${if (d.rewarded) "" else " (ödül koşulları oluşmadı)"}", fontWeight = FontWeight.Bold, color = LoopaPalette.ButterDeep) }
    if (d.status == "registration") {
        if (mine == null) LoopaButton("Katıl", { vm.join(d.id) }, loading = busy == "join", color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("tm_join"))
        else LoopaButton("Kaydımı sil", { vm.leave(d.id) }, loading = busy == "leave", color = LoopaPalette.Peach, modifier = Modifier.fillMaxWidth())
        if (host) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoopaButton("Başlat", { vm.start(d.id) }, loading = busy == "start", color = LoopaPalette.Sky, modifier = Modifier.weight(1f).testTag("tm_start"))
            LoopaButton("İptal et", { vm.cancel(d.id) }, loading = busy == "cancel", color = LoopaPalette.Peach, modifier = Modifier.weight(1f))
        }
    }
    Text("Katılımcılar (${d.entries.size}/${d.maxEntries})", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
    d.entries.forEach { e ->
        Text("${e.seed?.let { "#$it " } ?: ""}${entryName(e.id)}${if (e.status == "eliminated") " · elendi (tur ${e.eliminatedRound})" else ""}${if (e.mine) " · sen" else ""}",
            color = if (e.status == "eliminated") Loopa.colors.textSoft else Loopa.colors.text, style = MaterialTheme.typography.bodyMedium)
    }
    if (d.matches.isNotEmpty()) {
        Text("Eşleşmeler", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        d.matches.groupBy { it.round }.toSortedMap().forEach { (round, ms) ->
            Text("Tur $round", fontWeight = FontWeight.Bold, color = Loopa.colors.textSoft)
            ms.forEach { m ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (m.mine && m.status in setOf("waiting", "playing")) LoopaPalette.Butter.copy(alpha = 0.45f) else Loopa.colors.card)
                    .padding(10.dp).testTag("tm_match_${m.id}")) {
                    Text(m.entries.joinToString(" vs ") { entryName(it) }, color = Loopa.colors.text, fontWeight = FontWeight.Bold)
                    Text(when (m.status) {
                        "waiting" -> "Oyuncular bekleniyor"; "playing" -> "Oynanıyor"; "bye" -> "Bay geçti"
                        "forfeit" -> "Hükmen: ${entryName(m.winnerEntry)}"; "done" -> "Kazanan: ${entryName(m.winnerEntry)}${m.eliminatedEntry?.let { " · elenen: ${entryName(it)}" } ?: ""}"
                        else -> m.status
                    }, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                    if (m.mine && m.status in setOf("waiting", "playing")) LoopaButton("Maça git", { vm.playMatch(m.id, onRoomReady) }, loading = busy == "play",
                        color = LoopaPalette.Mint, compact = true, modifier = Modifier.testTag("tm_play"))
                }
            }
        }
    }
}
