package com.loopa.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopa.core.data.SessionEvent
import com.loopa.core.data.rememberDataContainer
import com.loopa.core.ui.components.FullScreenLoading
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.feature.game.GameScreen
import com.loopa.feature.lobby.LobbyScreen
import com.loopa.feature.results.ResultsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One screen for the whole room lifecycle. Which UI is shown is derived
 * from the authoritative room snapshot, so every client switches between
 * lobby, game and results at the same moment the server does.
 */
@Composable
fun RoomHost(roomId: String, onExit: () -> Unit) {
    val container = rememberDataContainer()
    val session = remember(roomId) { container.sessions.open(roomId) }
    val state by session.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    var resultsMatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var slowLoad by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        session.events.collect { e ->
            when (e) {
                is SessionEvent.MatchFinished -> resultsMatchId = e.matchId
                SessionEvent.Kicked -> notice = "Bu odadan çıkarıldın veya artık odada değilsin."
                SessionEvent.RoomClosed -> notice = "Oda sahibi odayı kapattı."
                else -> Unit
            }
        }
    }
    LaunchedEffect(Unit) { delay(10_000); slowLoad = true }
    BackHandler { onExit() }   // the session stays alive; Home offers "return to room"

    val s = state
    val inGame = s?.room?.inGame == true && s.match != null
    DisposableEffect(inGame) {
        feedback.setMusicPlaying(!inGame)
        onDispose { }
    }

    fun exitRoom() {
        scope.launch {
            session.leave()
            container.sessions.close()
            onExit()
        }
    }

    when {
        s == null -> if (slowLoad) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Odaya bağlanılamıyor", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
                Spacer(Modifier.height(16.dp))
                LoopaButton("Tekrar dene", { slowLoad = false; session.requestRefresh() }, color = LoopaPalette.Sky)
                Spacer(Modifier.height(8.dp))
                LoopaButton("Ana sayfaya dön", { container.sessions.close(); onExit() }, color = LoopaPalette.Lavender)
            }
        } else {
            FullScreenLoading("Odaya bağlanılıyor…")
        }
        resultsMatchId != null && !inGame -> ResultsScreen(
            matchId = resultsMatchId!!,
            onPlayAgain = {
                resultsMatchId = null
                if (!s.isHost && !s.isSpectator) scope.launch { runCatching { session.setReady(true) } }
            },
            onReturnToLobby = { resultsMatchId = null },
            onLeaveRoom = { exitRoom() },
        )
        inGame && s.match?.settings?.gameMode != com.loopa.core.game.GameModes.ISIM_SEHIR ->
            com.loopa.feature.game.modes.ModeGameScreen(roomId, s, onLeft = onExit)
        inGame -> GameScreen(roomId, s, onLeft = onExit)
        else -> LobbyScreen(roomId, s, onLeft = onExit)
    }

    notice?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = Loopa.colors.card,
            title = { Text("Odadan ayrıldın", style = MaterialTheme.typography.headlineSmall) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    notice = null
                    container.sessions.close()
                    onExit()
                }) { Text("Tamam") }
            },
        )
    }
}
