package com.loopa.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loopa.core.data.DataContainer
import com.loopa.core.data.ThemeMode
import com.loopa.core.data.UserSettings
import com.loopa.core.data.containerViewModel
import com.loopa.core.model.ProfileOverview
import com.loopa.core.ui.components.ConfirmDialog
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaCard
import com.loopa.core.ui.components.LoopaChip
import com.loopa.core.ui.components.LoopaLogo
import com.loopa.core.ui.components.SectionTitle
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: DataContainer) : ViewModel() {
    val settings: StateFlow<UserSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val profile: StateFlow<ProfileOverview?> = c.profiles.profile
    val userId: String? get() = c.auth.userId

    fun setSound(v: Boolean) = viewModelScope.launch { c.settings.setSound(v) }
    fun setMusic(v: Boolean) = viewModelScope.launch { c.settings.setMusic(v) }
    fun setVibration(v: Boolean) = viewModelScope.launch { c.settings.setVibration(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { c.settings.setTheme(v) }
    fun setLanguage(v: String) = viewModelScope.launch { c.settings.setLanguage(v) }

    private val _account = kotlinx.coroutines.flow.MutableStateFlow<com.loopa.core.data.AccountStatus?>(null)
    val account: StateFlow<com.loopa.core.data.AccountStatus?> = _account
    private val _googleAvailable = kotlinx.coroutines.flow.MutableStateFlow(false)
    val googleAvailable: StateFlow<Boolean> = _googleAvailable
    private val _busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    /** Set when a link attempt found the Google identity on another account. */
    private val _conflict = kotlinx.coroutines.flow.MutableStateFlow(false)
    val conflict: StateFlow<Boolean> = _conflict
    val callbacks = c.auth.callbacks

    init { refreshAccount() }

    fun refreshAccount() = viewModelScope.launch {
        _account.value = runCatching { c.auth.accountStatus() }.getOrNull()
        _googleAvailable.value = c.auth.isGoogleAvailable()
    }

    fun linkGoogle() = launchAuth { c.auth.linkGoogle() }
    fun signInWithGoogle() = launchAuth { _conflict.value = false; c.auth.signInWithGoogle() }

    private fun launchAuth(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true; _message.value = null
        try { block() } catch (e: com.loopa.core.game.LoopaException) { _message.value = e.error.message; _busy.value = false }
    }

    /** Handles the OAuth redirect result while this screen is open. */
    fun onCallback(result: com.loopa.core.data.AuthCallbackResult, onAccountSwitched: () -> Unit) {
        c.auth.consumeCallback()
        _busy.value = false
        when (result) {
            is com.loopa.core.data.AuthCallbackResult.Linked -> {
                _message.value = "Hesabın Google'a bağlandı. Seviyen, XP'n, avatarın, kozmetiklerin ve maç geçmişin korundu."
                refreshAccount(); viewModelScope.launch { runCatching { c.profiles.refresh() }; c.membership.refresh(); c.catalog.refresh() }
            }
            is com.loopa.core.data.AuthCallbackResult.SignedIn ->
                if (result.switchedFrom != null) viewModelScope.launch {
                    c.sessions.close(); c.profiles.clear(); c.membership.clear(); c.catalog.clear(); onAccountSwitched()
                } else refreshAccount()
            is com.loopa.core.data.AuthCallbackResult.Failed ->
                if (result.error == com.loopa.core.game.LoopaError.IDENTITY_ALREADY_LINKED) _conflict.value = true
                else _message.value = result.error.message
        }
    }

    fun dismissConflict() { _conflict.value = false }
    fun dismissMessage() { _message.value = null }

    fun logout(done: () -> Unit) = viewModelScope.launch {
        c.sessions.current.value?.leave()
        c.sessions.close()
        runCatching { c.auth.signOut() }
        c.profiles.clear()
        c.membership.clear(); c.catalog.clear()
        c.settings.setOnboardingDone(false)
        done()
    }
}

/** Languages the architecture supports; only Turkish ships in this build. */
private val languages = listOf("tr" to "Türkçe", "en" to "English (yakında)")

@Composable
fun SettingsScreen(versionName: String, onLoggedOut: () -> Unit, onAccountSwitched: () -> Unit = onLoggedOut, onOpenPlus: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val membershipVm = containerViewModel { MembershipViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val googleAvailable by vm.googleAvailable.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val conflict by vm.conflict.collectAsStateWithLifecycle()
    val callback by vm.callbacks.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(callback) { callback?.let { vm.onCallback(it, onAccountSwitched) } }
    val isGuest = account?.isAnonymous != false

    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    com.loopa.core.ui.components.IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Geri", onBack, Modifier.testTag("settings_back"))
                    Spacer(Modifier.width(8.dp))
                }
                Text("Ayarlar", style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text)
            }

            SectionTitle("Hesap")
            LoopaCard {
                InfoRow(Icons.Rounded.Person, "Kullanıcı adı", profile?.username ?: "-")
                InfoRow(Icons.Rounded.Person, "Hesap türü", when {
                    account == null -> "…"
                    account!!.isAnonymous -> "Misafir hesap"
                    account!!.hasGoogle -> "Google hesabı"
                    else -> "Bağlı hesap"
                })
                if (!isGuest && account?.email != null) InfoRow(Icons.Rounded.Info, "E-posta", account!!.email!!)
                InfoRow(Icons.Rounded.Info, "Oyuncu kimliği", vm.userId?.take(8)?.plus("…") ?: "-")
                com.loopa.core.ui.components.ErrorBanner(message, Modifier.padding(top = 8.dp), onDismiss = vm::dismissMessage)
                if (isGuest) {
                    Text(
                        "Misafir hesabın yalnızca bu cihazda. Google hesabına bağlarsan oyuncu kimliğin aynı kalır; seviyen, XP'n, avatarın, " +
                            "kozmetiklerin, istatistiklerin ve maç geçmişin korunur ve başka cihazdan Google ile girebilirsin.",
                        style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    LoopaButton(
                        if (googleAvailable) "Google hesabına bağla" else "Google bağlama (yakında)", vm::linkGoogle,
                        enabled = googleAvailable, loading = busy, color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth(),
                    )
                    if (googleAvailable) {
                        Spacer(Modifier.height(8.dp))
                        LoopaButton("Başka bir Google hesabıyla giriş yap", { confirmSwitch = true }, color = androidx.compose.ui.graphics.Color.White,
                            compact = true, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Text(
                        "Hesabın Google'a bağlı. Çıkış yapsan da verilerin sunucuda kalır; Google ile tekrar giriş yapabilirsin.",
                        style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                LoopaButton("Çıkış yap", { confirmLogout = true }, icon = Icons.AutoMirrored.Rounded.Logout, color = LoopaPalette.Peach, compact = true)
            }

            SectionTitle("Görünüm")
            LoopaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DarkMode, null, tint = Loopa.colors.text)
                    Spacer(Modifier.width(12.dp))
                    Text("Tema", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoopaChip("Sistem", s.theme == ThemeMode.SYSTEM, { vm.setTheme(ThemeMode.SYSTEM) })
                    LoopaChip("Açık", s.theme == ThemeMode.LIGHT, { vm.setTheme(ThemeMode.LIGHT) }, color = LoopaPalette.Butter)
                    LoopaChip("Koyu", s.theme == ThemeMode.DARK, { vm.setTheme(ThemeMode.DARK) }, color = LoopaPalette.Sky)
                }
            }

            SectionTitle("Ses ve titreşim")
            LoopaCard {
                ToggleRow(Icons.Rounded.VolumeUp, "Ses efektleri", "Butonlar, sayaç ve kutlamalar", s.soundEnabled, vm::setSound)
                ToggleRow(Icons.Rounded.MusicNote, "Müzik", "Menülerde hafif arka plan müziği", s.musicEnabled, vm::setMusic)
                ToggleRow(Icons.Rounded.Vibration, "Titreşim", "Sayaç uyarıları ve jokerler", s.vibrationEnabled, vm::setVibration)
            }

            SectionTitle("Bildirimler")
            LoopaCard(modifier = Modifier.testTag("settings_notifications")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Notifications, null, tint = Loopa.colors.text)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Anlık bildirimler", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                        Text("LOOPA şu an bildirim göndermiyor. Oda davetleri gelince buradan açıp kapatabileceksin.",
                            style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                    }
                    Spacer(Modifier.width(8.dp))
                    com.loopa.core.ui.components.ComingSoonTag()
                }
            }

            SectionTitle("Dil")
            LoopaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, null, tint = Loopa.colors.text)
                    Spacer(Modifier.width(12.dp))
                    Text("Uygulama dili", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    languages.forEach { (code, name) ->
                        LoopaChip(name, s.language == code, { vm.setLanguage(code) }, color = LoopaPalette.Mint, enabled = code == "tr")
                    }
                }
                Text(
                    "Şu an yalnızca Türkçe mevcut. Diğer diller eklendiğinde buradan seçilebilecek.",
                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionTitle("Üyelik")
            MembershipSettings(onOpenPlus, membershipVm)

            SectionTitle("Gizlilik")
            LoopaCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.PrivacyTip, null, tint = Loopa.colors.text)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "LOOPA yalnızca oyun için gerekli verileri saklar: kullanıcı adın, avatarın, oyun istatistiklerin ve oynadığın maçlardaki cevapların. " +
                            "Google hesabını bağlarsan Google'dan gelen e-posta adresin ve adın yalnızca giriş için saklanır. Telefon veya konum toplanmaz. " +
                            "Cevapların yalnızca aynı odadaki oyunculara gösterilir. Reklam ya da üçüncü taraf izleme yoktur.",
                        style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                    )
                }
            }

            SectionTitle("LOOPA hakkında")
            LoopaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoopaLogo(size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("LOOPA", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
                        Text("Play together. Think faster.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                        Text("Sürüm $versionName", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Arkadaşlarınla oynayabileceğin çok oyunculu parti oyunu. ${com.loopa.core.game.GameModes.all.size} oyun, tek uygulamada. Yazı tipleri: Baloo 2 ve Nunito (SIL Open Font License).",
                    style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (conflict) {
        ConfirmDialog(
            "Bu Google hesabı zaten kullanılıyor",
            "Seçtiğin Google hesabı başka bir LOOPA hesabına bağlı, bu yüzden bağlanmadı. Misafir hesabın olduğu gibi duruyor; hiçbir şey silinmedi veya birleştirilmedi.\n\n" +
                "İstersen o Google hesabıyla giriş yapabilirsin, ama bu cihazdaki misafir ilerlemen o hesaba aktarılmaz.",
            confirmText = "O hesapla giriş yap",
            onConfirm = { vm.dismissConflict(); vm.signInWithGoogle() },
            onDismiss = vm::dismissConflict,
            dismissText = "Misafir kal",
        )
    }
    if (confirmSwitch) {
        ConfirmDialog(
            "Başka hesaba geçilsin mi?",
            "Google ile başka bir LOOPA hesabına giriş yaparsan bu cihazdaki misafir ilerlemen (seviye ${profile?.level ?: 1}) o hesaba aktarılmaz. " +
                "İlerlemeni korumak için \"Google hesabına bağla\"yı kullan.",
            "Yine de giriş yap",
            onConfirm = { confirmSwitch = false; vm.signInWithGoogle() },
            onDismiss = { confirmSwitch = false },
        )
    }
    if (confirmLogout) {
        ConfirmDialog(
            "Çıkış yapılsın mı?",
            if (isGuest) "Misafir hesabından çıkarsan bu hesaba tekrar giriş yapılamaz; seviyen ve kozmetiklerin kaybolur. Önce Google hesabına bağlamanı öneririz."
            else "Bu cihazda çıkış yapılacak. Verilerin güvende; Google ile tekrar giriş yapabilirsin.",
            "Çıkış yap",
            onConfirm = { confirmLogout = false; vm.logout(onLoggedOut) },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Loopa.colors.text)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = LoopaPalette.MintDeep))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Loopa.colors.textSoft)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
    }
}
