package com.loopa.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.containerViewModel
import com.loopa.core.game.LoopaException
import com.loopa.core.ui.avatar.AvatarCatalog
import com.loopa.core.ui.avatar.AvatarConfig
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.AnimatedLoopaLogo
import com.loopa.core.ui.components.ErrorBanner
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.LoopaWordmark
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingState(
    val name: String = "",
    val avatar: AvatarConfig = AvatarCatalog.random(),
    val loading: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(private val c: DataContainer) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun setName(v: String) = _state.update { it.copy(name = v.take(16), error = null) }
    fun shuffle() = _state.update { it.copy(avatar = AvatarCatalog.random()) }

    fun finish(onDone: () -> Unit) {
        val s = _state.value
        val name = s.name.trim()
        if (name.length < 3) {
            _state.update { it.copy(error = "Kullanıcı adın en az 3 karakter olmalı.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                c.auth.signInAnonymously()
                c.profiles.ensureProfile(name, s.avatar.toJson())
                c.settings.setOnboardingDone(true)
                onDone()
            } catch (e: LoopaException) {
                _state.update { it.copy(loading = false, error = e.error.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Bağlantı kurulamadı: ${e.message ?: "bilinmeyen hata"}") }
            }
        }
    }
}

private data class IntroPage(val title: String, val body: String, val icon: ImageVector, val color: Color)

private val pages = listOf(
    IntroPage("Oda kur, kodu paylaş", "6 haneli oda koduyla arkadaşların saniyeler içinde katılsın. 10 kişiye kadar!", Icons.Rounded.Groups, LoopaPalette.Mint),
    IntroPage("Harfi gör, hızlı düşün", "İsim, Şehir, Hayvan, Bitki… Her tur yeni bir harf. İlk bitiren +5 bonus kapar!", Icons.Rounded.Bolt, LoopaPalette.Butter),
    IntroPage("Jokerlerle oyunu çevir", "Ek Süre, Zaman Hırsızı, Yavaşlatma ve 2X Puan. Doğru anda kullan, kazan!", Icons.Rounded.EmojiEvents, LoopaPalette.Peach),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val vm = containerViewModel { OnboardingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(Loopa.colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            AnimatedLoopaLogo(size = if (page < pages.size) 110.dp else 84.dp)
            Spacer(Modifier.height(10.dp))
            LoopaWordmark()
            Spacer(Modifier.height(28.dp))
            AnimatedContent(
                targetState = page,
                transitionSpec = { (slideInHorizontally { it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 2 } + fadeOut()) },
                label = "onboarding",
            ) { p ->
                if (p < pages.size) IntroCard(pages[p]) else ProfileStep(state, vm::setName, vm::shuffle) { vm.finish(onDone) }
            }
            Spacer(Modifier.height(20.dp))
            if (page < pages.size) {
                Dots(page, pages.size + 1)
                Spacer(Modifier.height(20.dp))
                LoopaButton(
                    text = if (page == pages.size - 1) "Hadi başlayalım" else "Devam",
                    onClick = { page++ },
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    color = LoopaPalette.Lavender,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { page = pages.size }) {
                    Text("Geç", color = Loopa.colors.textSoft, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun IntroCard(p: IntroPage) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(p.color.copy(alpha = 0.55f)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            Icon(p.icon, null, tint = LoopaPalette.Ink, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(p.title, style = MaterialTheme.typography.headlineMedium, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(p.body, style = MaterialTheme.typography.bodyLarge, color = LoopaPalette.Ink.copy(alpha = 0.8f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Dots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val w by animateFloatAsState(if (i == current) 26f else 10f, tween(250), label = "dot")
            Box(Modifier.height(10.dp).width(w.dp).clip(RoundedCornerShape(50)).background(if (i == current) LoopaPalette.LavenderDeep else Loopa.colors.outline))
        }
    }
}

@Composable
private fun ProfileStep(state: OnboardingState, onName: (String) -> Unit, onShuffle: () -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Seni tanıyalım!", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)
        Text(
            "Kayıt yok, şifre yok. Bir kullanıcı adı seç ve oyuna gir. Karakterini daha sonra Avatar bölümünde detaylıca özelleştirebilirsin.",
            style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center,
        )
        val bounce by animateFloatAsState(1f, label = "avatar")
        AvatarView(state.avatar, size = 132.dp, modifier = Modifier.scale(bounce))
        LoopaButton("Karıştır", onShuffle, icon = Icons.Rounded.Casino, color = LoopaPalette.Sky, compact = true, sfx = Sfx.JOKER)
        LoopaTextField(
            value = state.name,
            onValueChange = onName,
            label = "Kullanıcı adı",
            placeholder = "örn. HızlıKalem",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
        ErrorBanner(state.error)
        LoopaButton(
            "Oyuna Gir", onSubmit, loading = state.loading, enabled = state.name.trim().length >= 3,
            color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth(), sfx = Sfx.JOIN,
        )
    }
}
