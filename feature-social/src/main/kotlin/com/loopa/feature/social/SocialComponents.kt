package com.loopa.feature.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.loopa.core.game.LoopaError
import com.loopa.core.game.SocialFormat
import com.loopa.core.model.Presence
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.LoopaSurface
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing
import kotlinx.serialization.json.JsonObject

/** Avatar with a small online dot (only when the friend shares presence and is online). */
@Composable
fun PresenceAvatar(avatar: JsonObject?, presence: Presence?, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(modifier) {
        AvatarView(avatar, size = size, showFrame = false)
        if (presence?.visible == true && presence.online) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size / 4 + 4.dp).clip(CircleShape)
                    .background(Loopa.colors.card).padding(2.dp).clip(CircleShape).background(LoopaPalette.MintDeep),
            )
        }
    }
}

/** A person in a list: avatar, name, level / presence line and trailing actions. */
@Composable
fun PersonRow(
    name: String,
    avatar: JsonObject?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    presence: Presence? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(Radii.md)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(avatar, presence, 48.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

fun presenceLine(level: Int, presence: Presence?): String =
    listOfNotNull("Seviye $level", presence?.let { SocialFormat.presence(it) }).joinToString(" · ")

/** Flat palette illustration for social empty states: two friends and a speech bubble. */
@Composable
fun FriendsIllustration(modifier: Modifier = Modifier, accent: Color = LoopaPalette.Mint) {
    val ink = LoopaPalette.Ink
    Canvas(modifier.size(160.dp, 120.dp)) {
        val w = size.width; val h = size.height
        drawCircle(accent.copy(alpha = 0.45f), radius = h * 0.48f, center = Offset(w * 0.5f, h * 0.55f))
        // two heads + bodies
        listOf(0.36f to LoopaPalette.Lavender, 0.64f to LoopaPalette.Peach).forEach { (x, c) ->
            drawRoundRect(c, topLeft = Offset(w * x - w * 0.13f, h * 0.58f), size = Size(w * 0.26f, h * 0.34f), cornerRadius = CornerRadius(w * 0.1f))
            drawRoundRect(ink, topLeft = Offset(w * x - w * 0.13f, h * 0.58f), size = Size(w * 0.26f, h * 0.34f), cornerRadius = CornerRadius(w * 0.1f), style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, radius = h * 0.13f, center = Offset(w * x, h * 0.45f))
            drawCircle(ink, radius = h * 0.13f, center = Offset(w * x, h * 0.45f), style = Stroke(3.dp.toPx()))
        }
        // bubble
        drawRoundRect(LoopaPalette.Butter, topLeft = Offset(w * 0.62f, h * 0.04f), size = Size(w * 0.32f, h * 0.22f), cornerRadius = CornerRadius(h * 0.1f))
        drawRoundRect(ink, topLeft = Offset(w * 0.62f, h * 0.04f), size = Size(w * 0.32f, h * 0.22f), cornerRadius = CornerRadius(h * 0.1f), style = Stroke(3.dp.toPx()))
        for (i in 0..2) drawCircle(ink, radius = 3.dp.toPx(), center = Offset(w * (0.70f + i * 0.08f), h * 0.15f))
    }
}

/** Centered empty / unavailable state with the social illustration. */
@Composable
fun SocialEmpty(title: String, body: String, modifier: Modifier = Modifier, accent: Color = LoopaPalette.Mint, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FriendsIllustration(accent = accent)
        Text(title, style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(Spacing.xs)); action() }
    }
}

/** Shown when the server does not have the social migration yet: nothing pretends to work. */
@Composable
fun SocialUnavailable(error: LoopaError?, modifier: Modifier = Modifier) {
    LoopaSurface(modifier, color = LoopaPalette.Butter.copy(alpha = 0.35f)) {
        Text(if (error == LoopaError.SERVER_OUTDATED) "Sosyal özellikler sunucuda henüz açılmadı" else "Sosyal bilgiler alınamadı",
            style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
        Text(if (error == LoopaError.SERVER_OUTDATED) "Arkadaşlar, mesajlar ve davetler sunucu güncellendiğinde burada görünecek. Açık odalar ve oda kodu ile oynamaya devam edebilirsin."
             else (error?.message ?: "Bir sorun oluştu.") + " Aşağı çekip ya da biraz sonra tekrar dene.",
            style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
    }
}

/** Small pill showing a relation or state ("İstek gönderildi"). */
@Composable
fun StatePill(text: String, color: Color = Loopa.colors.cardHigh, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(Radii.pill).background(color).border(1.dp, Loopa.colors.outline, Radii.pill)
        .padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text, maxLines = 1)
}
