package com.loopa.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.loopa.core.game.LevelCurve
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

/** Big, chunky, tactile game button. */
@Composable
fun LoopaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LoopaPalette.Lavender,
    contentColor: Color = LoopaPalette.Ink,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    sfx: Sfx = Sfx.TAP,
) {
    val feedback = LocalFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, spring(stiffness = 900f), label = "btn")
    val shape = RoundedCornerShape(if (compact) 16.dp else 22.dp)
    val bg = if (enabled) color else color.copy(alpha = 0.45f)
    val depth = if (enabled) darken(color) else Color.Transparent
    Box(
        modifier
            .scale(scale)
            .heightIn(min = if (compact) 44.dp else 58.dp)
            .shadow(if (enabled && !pressed) 6.dp else 1.dp, shape, ambientColor = depth, spotColor = depth)
            .clip(shape)
            .background(bg)
            .border(BorderStroke(2.dp, depth.copy(alpha = 0.35f)), shape)
            .clickable(
                interactionSource = interaction, indication = null,
                enabled = enabled && !loading, role = Role.Button,
            ) {
                feedback.play(sfx)
                onClick()
            }
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = if (compact) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp), color = contentColor, strokeWidth = 3.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (icon != null) {
                    Icon(icon, null, tint = contentColor, modifier = Modifier.size(if (compact) 20.dp else 24.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = contentColor.copy(alpha = if (enabled) 1f else 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun darken(c: Color, f: Float = 0.78f) = Color(c.red * f, c.green * f, c.blue * f, c.alpha)

@Composable
fun LoopaCard(
    modifier: Modifier = Modifier,
    color: Color = Loopa.colors.card,
    shape: Shape = RoundedCornerShape(26.dp),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val feedback = LocalFeedback.current
    Surface(
        modifier = modifier
            .shadow(4.dp, shape, ambientColor = LoopaPalette.Lavender.copy(alpha = 0.35f), spotColor = LoopaPalette.Lavender.copy(alpha = 0.35f))
            .then(if (onClick != null) Modifier.clip(shape).clickable { feedback.play(Sfx.TAP); onClick() } else Modifier),
        shape = shape,
        color = color,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun LoopaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    accent: Color = LoopaPalette.Lavender,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = Loopa.colors.textSoft) } },
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        shape = RoundedCornerShape(18.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight(800)),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leading,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = darken(accent),
            unfocusedBorderColor = accent.copy(alpha = 0.7f),
            disabledBorderColor = accent.copy(alpha = 0.35f),
            focusedContainerColor = Loopa.colors.card,
            unfocusedContainerColor = Loopa.colors.card,
            disabledContainerColor = Loopa.colors.cardHigh,
            cursorColor = darken(accent, 0.6f),
            focusedLabelColor = darken(accent, 0.6f),
            focusedTextColor = Loopa.colors.text,
            unfocusedTextColor = Loopa.colors.text,
            disabledTextColor = Loopa.colors.text.copy(alpha = 0.8f),
        ),
    )
}

@Composable
fun LoopaChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LoopaPalette.Lavender,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val feedback = LocalFeedback.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) color else Loopa.colors.cardHigh)
            .border(2.dp, if (selected) darken(color) else Loopa.colors.outline, shape)
            .clickable(enabled = enabled) { feedback.play(Sfx.TAP); onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(6.dp)) }
        Text(
            text, style = MaterialTheme.typography.labelMedium,
            color = if (selected) LoopaPalette.Ink else Loopa.colors.text.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, textColor: Color = LoopaPalette.Ink) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        maxLines = 1,
    )
}

@Composable
fun LoopaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri", tint = Loopa.colors.text)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title, style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(LoopaPalette.Butter).border(2.dp, LoopaPalette.ButterDeep, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            level.toString(), color = LoopaPalette.Ink,
            style = if (size < 30.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun XpBar(xp: Int, modifier: Modifier = Modifier, height: Dp = 14.dp, showLabel: Boolean = true) {
    val p = LevelCurve.progress(xp)
    val anim by animateFloatAsState(p.fraction, tween(900), label = "xp")
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Loopa.colors.cardHigh)
                .border(1.dp, Loopa.colors.outline, RoundedCornerShape(50)),
        ) {
            Box(
                Modifier.fillMaxWidth(anim.coerceIn(0.02f, 1f)).height(height).clip(RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(LoopaPalette.Mint, LoopaPalette.Sky))),
            )
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${p.intoLevel} / ${p.levelSpan} XP · Seviye ${p.level + 1} için ${p.remaining} XP",
                style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft,
            )
        }
    }
}

@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.55f)).padding(14.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Ink.copy(alpha = 0.75f))
    }
}

@Composable
fun FullScreenLoading(message: String? = null) {
    Column(
        Modifier.fillMaxSize().background(Loopa.colors.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedLoopaLogo(size = 96.dp)
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
        }
    }
}

@Composable
fun ErrorBanner(message: String?, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    AnimatedVisibility(message != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(
            modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LoopaPalette.Error.copy(alpha = 0.18f))
                .border(2.dp, LoopaPalette.Error.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .then(if (onDismiss != null) Modifier.clickable { onDismiss() } else Modifier)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = darken(LoopaPalette.Error, 0.8f))
            Spacer(Modifier.width(10.dp))
            Text(message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoopaLogo(size = 64.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "Vazgeç",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Loopa.colors.card,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, style = MaterialTheme.typography.labelLarge, color = darken(LoopaPalette.Error, 0.85f))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText, style = MaterialTheme.typography.labelLarge) } },
    )
}

/** Constrains content width on tablets / landscape so layouts stay readable. */
fun Modifier.loopaContentWidth(): Modifier = this.widthIn(max = 640.dp)
