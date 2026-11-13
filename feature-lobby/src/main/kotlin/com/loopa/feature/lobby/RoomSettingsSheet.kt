package com.loopa.feature.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopa.core.game.TurkishText
import com.loopa.core.model.BuiltInCategories
import com.loopa.core.model.Category
import com.loopa.core.model.RoomSettings
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaChip
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

/**
 * Host-only room configuration. Client-side checks are for UX only —
 * `update_room_settings` re-validates everything on the server.
 */
@Composable
fun RoomSettingsSheet(
    initial: RoomSettings,
    initialPublic: Boolean,
    currentPlayers: Int,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (RoomSettings, Boolean) -> Unit,
    /** Card state of each game for this host (from the server catalogue); null = not loaded. */
    gameStatus: (String) -> com.loopa.core.game.GameCardStatus? = { null },
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var s by remember { mutableStateOf(initial) }
    var isPublic by remember { mutableStateOf(initialPublic) }
    var newCustom by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    fun toggleCategory(cat: Category) {
        val enabled = s.categories.any { it.key == cat.key }
        s = if (enabled) {
            if (s.categories.size <= RoomSettings.MIN_CATEGORIES) { localError = "En az ${RoomSettings.MIN_CATEGORIES} kategori seçili olmalı."; return }
            s.copy(categories = s.categories.filterNot { it.key == cat.key })
        } else {
            if (s.categories.size >= RoomSettings.MAX_CATEGORIES) { localError = "En fazla ${RoomSettings.MAX_CATEGORIES} kategori seçebilirsin."; return }
            s.copy(categories = s.categories + cat)
        }
        localError = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Loopa.colors.background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Oda Ayarları", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)

            Text("Oyun", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
            FlowRow(Modifier.testTag("settings_games"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.loopa.core.game.GameModes.all.forEach { g ->
                    val st = gameStatus(g.id)
                    // The current game stays selectable; others only when the server says this host may start them.
                    val allowed = g.id == s.gameMode || g.id == com.loopa.core.game.GameModes.ISIM_SEHIR || st?.canStart == true
                    LoopaChip(g.name, s.gameMode == g.id, { s = s.copy(gameMode = g.id); localError = null },
                        color = com.loopa.core.ui.components.gameVisual(g.id).color, enabled = allowed,
                        leading = { com.loopa.core.ui.components.GameIcon(g.id, size = 20.dp, dimmed = !allowed) })
                }
            }
            Text(com.loopa.core.game.GameModes.info(s.gameMode).let { "${it.tagline} (${it.players})" },
                style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
            val labels = ModeSettingsLabels.of(s.gameMode)

            if (labels.duration != null) Section(labels.duration) {
                RoomSettings.DURATIONS.forEach { d -> LoopaChip("$d sn", s.roundSeconds == d, { s = s.copy(roundSeconds = d) }, color = LoopaPalette.Butter) }
            }
            if (labels.rounds != null) Section(labels.rounds) {
                RoomSettings.ROUNDS.forEach { r -> LoopaChip("$r", s.rounds == r, { s = s.copy(rounds = r) }, color = LoopaPalette.Peach) }
            }
            labels.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft) }
            Section("Oyuncu sınırı") {
                RoomSettings.PLAYER_LIMITS.forEach { m ->
                    LoopaChip("$m kişi", s.maxPlayers == m, { s = s.copy(maxPlayers = m) }, color = LoopaPalette.Sky, enabled = m >= currentPlayers)
                }
            }
            if (s.gameMode == com.loopa.core.game.GameModes.ISIM_SEHIR) {
            Section("Oylama süresi") {
                listOf(15, 20, 30, 45).forEach { v -> LoopaChip("$v sn", s.voteSeconds == v, { s = s.copy(voteSeconds = v) }, color = LoopaPalette.Lavender) }
            }

            Text("Kategoriler (${s.categories.size})", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BuiltInCategories.all.forEachIndexed { i, cat ->
                    LoopaChip(cat.name, s.categories.any { it.key == cat.key }, { toggleCategory(cat) }, color = LoopaPalette.accent(i))
                }
            }
            val customs = s.categories.filter { it.custom }
            if (customs.isNotEmpty()) {
                Text("Özel kategoriler", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.textSoft)
                customs.forEach { cat ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Loopa.colors.card).padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (renaming == cat.key) {
                            LoopaTextField(renameText, { renameText = it.take(20) }, Modifier.weight(1f).padding(vertical = 6.dp))
                            IconButton(onClick = {
                                val name = renameText.trim()
                                if (name.length in 2..20) {
                                    s = s.copy(categories = s.categories.map { if (it.key == cat.key) it.copy(name = name) else it })
                                    renaming = null
                                } else localError = "Kategori adı 2-20 karakter olmalı."
                            }) { Icon(Icons.Rounded.Save, "Kaydet", tint = Loopa.colors.text) }
                        } else {
                            Text(cat.name, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, modifier = Modifier.weight(1f))
                            IconButton(onClick = { renaming = cat.key; renameText = cat.name }) { Icon(Icons.Rounded.Edit, "Yeniden adlandır", tint = Loopa.colors.textSoft) }
                            IconButton(onClick = { toggleCategory(cat) }) { Icon(Icons.Rounded.Close, "Kaldır", tint = Loopa.colors.textSoft) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoopaTextField(newCustom, { newCustom = it.take(20) }, Modifier.weight(1f), placeholder = "Özel kategori ekle (örn. Süper Kahraman)")
                Spacer(Modifier.width(8.dp))
                LoopaButton(
                    "Ekle",
                    onClick = {
                        val name = newCustom.trim()
                        when {
                            name.length !in 2..20 -> localError = "Kategori adı 2-20 karakter olmalı."
                            customs.size >= RoomSettings.MAX_CUSTOM_CATEGORIES -> localError = "En fazla ${RoomSettings.MAX_CUSTOM_CATEGORIES} özel kategori ekleyebilirsin."
                            s.categories.size >= RoomSettings.MAX_CATEGORIES -> localError = "En fazla ${RoomSettings.MAX_CATEGORIES} kategori seçebilirsin."
                            else -> {
                                val used = s.categories.mapNotNull { it.key.removePrefix("custom_").toIntOrNull() }.toSet()
                                val n = (1..999).first { it !in used }
                                s = s.copy(categories = s.categories + Category("custom_$n", name, custom = true))
                                newCustom = ""
                                localError = null
                            }
                        }
                    },
                    icon = Icons.Rounded.Add, color = LoopaPalette.Mint, compact = true,
                )
            }
            Text(
                "Özel kategorilerdeki cevaplar sözlükte bulunmadığı için oyuncu oylamasıyla değerlendirilir.",
                style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
            )

            Text("Hariç tutulan harfler", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
            Text("Ğ zaten hiçbir zaman seçilmez. Zor harfleri kapatabilirsin.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TurkishText.LETTERS.forEach { l ->
                    val excluded = l in s.excludedLetters
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (excluded) LoopaPalette.Error.copy(alpha = 0.35f) else LoopaPalette.Mint.copy(alpha = 0.5f))
                            .clickable {
                                val next = if (excluded) s.excludedLetters - l else s.excludedLetters + l
                                if (next.size <= TurkishText.LETTERS.size - 5) s = s.copy(excludedLetters = next)
                                else localError = "En az 5 harf açık kalmalı."
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(l, style = MaterialTheme.typography.titleMedium, color = if (excluded) darken(LoopaPalette.Error, 0.7f) else LoopaPalette.Ink, textAlign = TextAlign.Center)
                    }
                }
            }
            }   // İsim Şehir only

            ToggleRow("Açık oda", "Oda tarayıcısında görünür.", isPublic) { isPublic = it }
            ToggleRow("İzleyici modu", "İzleyiciler oyunu izleyebilir ama etkileyemez.", s.allowSpectators) { s = s.copy(allowSpectators = it) }

            ErrorBanner(localError)
            LoopaButton(
                "Kaydet", { onSave(s, isPublic) }, loading = saving, icon = Icons.Rounded.Save,
                color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Ayarlar değişince oyuncuların tekrar \"Hazır\" olması gerekir.",
                style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Loopa.colors.card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = LoopaPalette.MintDeep))
    }
}

/** Which settings a game uses, with labels in its own terms. */
data class ModeSettingsLabels(val duration: String?, val rounds: String?, val note: String? = null) {
    companion object {
        fun of(mode: String) = when (mode) {
            "isim_sehir" -> ModeSettingsLabels("Tur süresi", "Tur sayısı")
            "gizli_kelime", "sessiz_sinema", "anlat_bakalim" -> ModeSettingsLabels("Anlatma süresi", "Tur sayısı", "Süre 45-120 sn arasında uygulanır.")
            "ciz_tahmin" -> ModeSettingsLabels("Çizim süresi", "Tur sayısı", "Süre 45-120 sn arasında uygulanır.")
            "bilgi_arenasi" -> ModeSettingsLabels(null, "Soru sayısı", "Her soru için 20 saniye.")
            "en_muhtemel_kim" -> ModeSettingsLabels(null, "Soru sayısı", "Her oylama 20 saniye.")
            "kelime_zinciri" -> ModeSettingsLabels("Hamle süresi (30→15, 60→20, 90→25, 120→30 sn)", "Tur (her oyuncu için)")
            "iki_dogru_bir_yalan" -> ModeSettingsLabels("Yazma süresi", null, "Her yazan oyuncu için bir oylama turu.")
            "cizim_telefonu" -> ModeSettingsLabels("Çizim süresi", null, "Adım sayısı oyuncu sayısı kadardır; tahminler 35 saniye.")
            else -> ModeSettingsLabels("Süre", "Tur sayısı")
        }
    }
}
