package com.loopa.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaLogo
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

/** Shown when the APK was built without Supabase credentials. Never pretends to be online. */
@Composable
fun BackendNotConfiguredScreen() {
    Column(
        Modifier.fillMaxSize().background(Loopa.colors.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        LoopaLogo(size = 96.dp)
        Text("Sunucu bağlı değil", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)
        Text(
            "Bu LOOPA derlemesi bir Supabase projesine bağlanmadan oluşturuldu. Çok oyunculu oyun için geliştiricinin " +
                "SUPABASE_URL ve SUPABASE_ANON_KEY değerlerini local.properties dosyasına ekleyip uygulamayı yeniden derlemesi gerekiyor.",
            style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center,
            modifier = Modifier.loopaContentWidth(),
        )
        Text(
            "SUPABASE_URL=https://<proje>.supabase.co\nSUPABASE_ANON_KEY=<anon public key>",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Loopa.colors.text,
            modifier = Modifier.loopaContentWidth().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Loopa.colors.cardHigh).padding(14.dp),
        )
        Text("Ayrıntılar için README.md → \"Backend kurulumu\".", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
    }
}

@Composable
fun ConnectionErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Loopa.colors.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoopaLogo(size = 88.dp)
        Spacer(Modifier.height(16.dp))
        Text("Bağlanamadık", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        LoopaButton("Tekrar dene", onRetry, icon = Icons.Rounded.Refresh, color = LoopaPalette.Sky)
    }
}
