package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.modes.Stroke
import com.loopa.core.game.modes.progressLabel
import com.loopa.core.model.ModeState
import com.loopa.core.model.RoomState
import com.loopa.core.ui.components.ConfirmDialog
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.FullScreenLoading
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.Haptic
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.feature.game.rememberServerNow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** What a game screen can do; implemented by the ViewModel, faked in UI tests. */
data class ModeUi(
    val busy: String? = null,
    val act: (kind: String, payload: JsonObject, onResult: (JsonObject) -> Unit) -> Unit = { _, _, _ -> },
    /** The drawer's own canvas, already including unsent strokes. */
    val localStrokes: List<Stroke> = emptyList(),
    /** Strokes received from the server (what guessers see). */
    val remoteStrokes: List<Stroke> = emptyList(),
    val addStroke: (Stroke) -> Unit = {},
    val clearCanvas: () -> Unit = {},
    val skip: () -> Unit = {},
    val flash: (String) -> Unit = {},
    val showError: (String) -> Unit = {},
)

/** Game screen of every game except İsim Şehir. */
@Composable
fun ModeGameScreen(roomId: String, state: RoomState, onLeft: () -> Unit) {
    val vm = containerViewModel(key = "mode-$roomId") { ModeGameViewModel(it, roomId) }
    val mode by vm.mode.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val flash by vm.flash.collectAsStateWithLifecycle()
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    val local by vm.localStrokes.collectAsStateWithLifecycle()
    val now by rememberServerNow(vm.clock)
    val feedback = LocalFeedback.current
    var confirmLeave by remember { mutableStateOf(false) }

    val m = mode
    if (m == null) { FullScreenLoading("Oyun yükleniyor…"); return }
    val roundId = m.round?.id
    val ui = ModeUi(
        busy = busy,
        act = { kind, payload, onResult -> feedback.play(Sfx.SUBMIT); vm.act(kind, payload, onResult) },
        // After an app restart the drawer's own list is empty: show what the server has.
        localStrokes = if (local.first == roundId && local.second.isNotEmpty()) local.second else strokes.strokes.takeIf { strokes.roundId == roundId }.orEmpty(),
        remoteStrokes = strokes.strokes.takeIf { strokes.roundId == roundId }.orEmpty(),
        addStroke = { s -> roundId?.let { vm.addStroke(it, s) } },
        clearCanvas = { roundId?.let { vm.clearCanvas(it) } },
        skip = vm::skip,
        flash = { feedback.play(Sfx.SCORE); feedback.haptic(Haptic.SUCCESS); vm.flash(it) },
        showError = vm::showError,
    )
    LaunchedEffect(flash) { if (flash != null) { delay(2_200); vm.dismissFlash() } }
    LaunchedEffect(m.round?.phase, m.round?.id) {
        when (m.round?.phase) {
            "reveal", "showcase" -> feedback.play(Sfx.REVEAL)
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        ModeGameContent(state, m, now, ui, onLeave = { confirmLeave = true },
            banner = { ErrorBanner(error, Modifier.padding(horizontal = 16.dp), onDismiss = vm::dismissError) }, flash = flash)
    }
    if (confirmLeave) {
        ConfirmDialog("Oyundan çıkılsın mı?", "Maç senin için biter; diğerleri oynamaya devam eder.", "Çık",
            onConfirm = { confirmLeave = false; vm.leave(onLeft) }, onDismiss = { confirmLeave = false })
    }
}

/** Stateless: picks the game's own screen. Used directly by UI tests. */
@Composable
fun ModeGameContent(
    room: RoomState,
    mode: ModeState,
    now: Long,
    ui: ModeUi,
    onLeave: () -> Unit = {},
    banner: @Composable () -> Unit = {},
    flash: String? = null,
) {
    val ctx = ModeContext(room, now, room.me.userId, room.isHost, room.isSpectator)
    Column(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Box(Modifier.align(Alignment.CenterHorizontally).loopaContentWidth().fillMaxWidth()) {
            ModeHeader(mode.mode, progressFor(mode), mode.round, now, onLeave, timed = mode.round?.phase !in setOf("done"))
        }
        banner()
        FlashBanner(flash)
        Box(Modifier.weight(1f).align(Alignment.CenterHorizontally).loopaContentWidth().fillMaxWidth()) {
            when (mode.mode) {
                "gizli_kelime" -> GizliKelimeScreen(ctx, mode, ui)
                "sessiz_sinema" -> SessizSinemaScreen(ctx, mode, ui)
                "ciz_tahmin" -> CizTahminScreen(ctx, mode, ui)
                "anlat_bakalim" -> AnlatBakalimScreen(ctx, mode, ui)
                "bilgi_arenasi" -> BilgiArenasiScreen(ctx, mode, ui)
                "iki_dogru_bir_yalan" -> IkiDogruBirYalanScreen(ctx, mode, ui)
                "en_muhtemel_kim" -> EnMuhtemelKimScreen(ctx, mode, ui)
                "kelime_zinciri" -> KelimeZinciriScreen(ctx, mode, ui)
                "cizim_telefonu" -> CizimTelefonuScreen(ctx, mode, ui)
                else -> WaitingNote("Bu oyun bu sürümde desteklenmiyor.")
            }
        }
    }
}

private fun progressFor(m: ModeState): String = when (m.mode) {
    "bilgi_arenasi", "en_muhtemel_kim" -> m.progressLabel("Soru")
    "kelime_zinciri" -> m.progressLabel("Hamle")
    "cizim_telefonu" -> if (m.round?.phase == "showcase") "Sonuçlar" else m.progressLabel("Adım")
    "iki_dogru_bir_yalan" -> if (m.round?.number == 1) "Yazma turu" else m.progressLabel("Tur")
    else -> m.progressLabel("Tur")
}
