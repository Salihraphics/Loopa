package com.loopa.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.loopa.core.ui.components.ComingSoonTag
import com.loopa.core.ui.components.ListRow
import com.loopa.core.ui.components.LoopaSurface
import com.loopa.core.ui.components.SectionHeader
import com.loopa.core.ui.components.loopaContentWidth
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Spacing

/**
 * A planned social feature. [available] is true only when the backend supports it; everything else
 * is clearly marked "Yakında" and does nothing when tapped.
 */
data class SocialFeature(val key: String, val title: String, val description: String, val icon: ImageVector, val available: Boolean = false)

/** The social roadmap the screen is built around; flipping [SocialFeature.available] wires one in. */
val plannedSocialFeatures = listOf(
    SocialFeature("friends", "Arkadaşlar", "Arkadaşlarını ekle, kimin çevrim içi olduğunu gör.", Icons.Rounded.People),
    SocialFeature("requests", "Arkadaşlık istekleri", "Gelen ve gönderilen istekler.", Icons.Rounded.GroupAdd),
    SocialFeature("messages", "Mesajlar", "Birebir ve grup sohbetleri.", Icons.Rounded.Chat),
    SocialFeature("invites", "Davetler", "Arkadaşlarından gelen oda davetleri.", Icons.Rounded.Mail),
)

@Composable
fun SocialScreen(onPublicRooms: () -> Unit, onOpenGames: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Loopa.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).loopaContentWidth().fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter).testTag("social_screen"),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Sosyal", style = MaterialTheme.typography.displaySmall, color = Loopa.colors.text, modifier = Modifier.padding(top = Spacing.sm))
            // What works today: playing with others through room codes and public rooms.
            SectionHeader("Şimdi birlikte oyna")
            LoopaSurface {
                ListRow("Açık odalar", subtitle = "Herkese açık bir odaya katıl, yeni insanlarla oyna.", icon = Icons.Rounded.Public,
                    iconTint = LoopaPalette.Sky, onClick = onPublicRooms, modifier = Modifier.testTag("social_public_rooms"))
                ListRow("Arkadaşını davet et", subtitle = "Bir oda kur, lobideki oda kodunu “Paylaş” ile gönder.", icon = Icons.Rounded.Share,
                    iconTint = LoopaPalette.Mint, onClick = onOpenGames, modifier = Modifier.testTag("social_invite"))
            }
            SectionHeader("Yakında")
            LoopaSurface(Modifier.testTag("social_planned")) {
                plannedSocialFeatures.forEach { f ->
                    ListRow(f.title, subtitle = f.description, icon = f.icon, iconTint = LoopaPalette.Lavender,
                        modifier = Modifier.testTag("social_${f.key}"), trailing = { if (!f.available) ComingSoonTag() })
                }
            }
            LoopaSurface(color = Loopa.colors.cardHigh) {
                ListRow("Gruplar ve çevrim içi durum", subtitle = "Arkadaş sistemiyle birlikte gelecek. Şu an kimsenin durumu paylaşılmıyor.",
                    icon = Icons.Rounded.Groups, iconTint = LoopaPalette.Peach)
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}
