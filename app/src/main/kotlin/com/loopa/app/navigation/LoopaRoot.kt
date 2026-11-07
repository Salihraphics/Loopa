package com.loopa.app.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.AuthState
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaException
import com.loopa.core.ui.components.FullScreenLoading
import com.loopa.feature.auth.BackendNotConfiguredScreen
import com.loopa.feature.auth.ConnectionErrorScreen
import com.loopa.feature.auth.OnboardingScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Boot {
    data object Splash : Boot
    data object NotConfigured : Boot
    data object Onboarding : Boot
    data class Ready(val resumeRoomId: String?) : Boot
    data class Error(val message: String) : Boot
}

class RootViewModel(private val c: DataContainer) : ViewModel() {
    private val _boot = MutableStateFlow<Boot>(Boot.Splash)
    val boot: StateFlow<Boot> = _boot.asStateFlow()

    init { start() }

    fun start() {
        _boot.value = Boot.Splash
        viewModelScope.launch {
            val minSplash = launch { delay(1_100) }
            val next = resolve()
            minSplash.join()
            _boot.value = next
        }
    }

    private suspend fun resolve(): Boot {
        if (!c.backend.isConfigured) return Boot.NotConfigured
        val auth = withTimeoutOrNull(10_000) { c.auth.state.first { it != AuthState.Loading } } ?: AuthState.SignedOut
        if (auth !is AuthState.SignedIn) return Boot.Onboarding
        return try {
            c.profiles.refresh() ?: return Boot.Onboarding
            // Recover an in-progress room after an app restart.
            val active = runCatching { c.rooms.activeRoom() }.getOrNull()
            Boot.Ready(resumeRoomId = active?.id?.takeIf { active.status == "in_game" })
        } catch (e: LoopaException) {
            Boot.Error(e.error.message)
        }
    }

    fun onOnboarded() { _boot.value = Boot.Ready(null) }
    fun onLoggedOut() { _boot.value = Boot.Onboarding }
}

@Composable
fun LoopaRoot(versionName: String) {
    val vm = containerViewModel { RootViewModel(it) }
    val boot by vm.boot.collectAsStateWithLifecycle()
    AnimatedContent(boot, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "boot") { b ->
        when (b) {
            Boot.Splash -> FullScreenLoading()
            Boot.NotConfigured -> BackendNotConfiguredScreen()
            Boot.Onboarding -> OnboardingScreen(onDone = vm::onOnboarded)
            is Boot.Error -> ConnectionErrorScreen(b.message, onRetry = vm::start)
            is Boot.Ready -> AppNavHost(versionName = versionName, resumeRoomId = b.resumeRoomId, onLoggedOut = vm::onLoggedOut)
        }
    }
}
