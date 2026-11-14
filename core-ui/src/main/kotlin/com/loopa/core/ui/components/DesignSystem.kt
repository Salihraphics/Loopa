package com.loopa.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Borders
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaMotion
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.MinTouch
import com.loopa.core.ui.theme.Motion
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing

/**
 * Flat LOOPA surface: card colour, hairline outline, no heavy shadow. Pressing scales it slightly.
 * The default container of the 0.6 screens (LoopaCard stays for the game screens).
 */
@Composable
fun LoopaSurface(
    modifier: Modifier = Modifier,
    color: Color = Loopa.colors.card,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val feedback = LocalFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null && !LoopaMotion.reduced) 0.98f else 1f, Motion.press(), label = "surface")
    val border by animateColorAsState(if (selected) LoopaPalette.LavenderDeep else Loopa.colors.outline, Motion.standard(LoopaMotion.reduced), label = "border")
    Column(
        modifier.scale(scale).clip(Radii.lg).background(color)
            .border(if (selected) Borders.strong else Borders.hairline, border, Radii.lg)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, role = Role.Button) { feedback.play(Sfx.TAP); onClick() } else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Section title with an optional text action ("Tümü"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelMedium, color = Loopa.colors.accentText,
                modifier = Modifier.clip(Radii.pill).clickable(role = Role.Button, onClick = onAction).padding(horizontal = Spacing.md, vertical = Spacing.sm))
        }
    }
}

/** Settings / menu row: icon tile, title, optional subtitle, trailing content or chevron. 56 dp min. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LoopaPalette.Lavender,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radii.md)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(Radii.sm).background(iconTint.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Loopa.colors.text, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Loopa.colors.textSoft)
        }
    }
}

/** Round icon button with a 48 dp touch target and an optional dot. */
@Composable
fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Boolean = false) {
    Box(
        modifier.size(MinTouch).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Loopa.colors.card).border(Borders.hairline, Loopa.colors.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = Loopa.colors.text, modifier = Modifier.size(22.dp))
        }
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(9.dp).clip(CircleShape).background(LoopaPalette.PeachDeep))
    }
}

/** A destination of the bottom navigation. */
data class NavItem(val key: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon, val badge: Int = 0)

/**
 * Compact bottom navigation: a pastel pill slides behind the selected icon and the label of the
 * selected item is emphasised. Handles the system navigation bar inset itself.
 */
@Composable
fun LoopaBottomNav(items: List<NavItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Loopa.colors.card)) {
        Box(Modifier.fillMaxWidth().heightIn(min = Borders.hairline).background(Loopa.colors.outline))
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                .testTag("bottom_nav"),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            items.forEach { item -> NavDestination(item, item.key == selected, { onSelect(item.key) }, Modifier.weight(1f)) }
        }
    }
}

/** Vertical rail for tablets / landscape, same destinations and visuals as [LoopaBottomNav]. */
@Composable
fun LoopaNavRail(items: List<NavItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(Loopa.colors.card).padding(horizontal = Spacing.sm, vertical = Spacing.xl).testTag("nav_rail"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items.forEach { item -> NavDestination(item, item.key == selected, { onSelect(item.key) }, Modifier.width(80.dp)) }
    }
}

@Composable
private fun NavDestination(item: NavItem, isSel: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val reduced = LoopaMotion.reduced
    val pill by animateDpAsState(if (isSel) 56.dp else 36.dp, Motion.standard(reduced), label = "pill")
    val pillColor by animateColorAsState(if (isSel) LoopaPalette.Lavender else Color.Transparent, Motion.standard(reduced), label = "pillColor")
    Column(
        modifier.heightIn(min = 60.dp).clip(Radii.md)
            .selectable(isSel, role = Role.Tab, onClick = onClick)
            .padding(vertical = Spacing.xs).testTag("nav_${item.key}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.width(pill).heightIn(min = 30.dp).clip(Radii.pill).background(pillColor), contentAlignment = Alignment.Center) {
                Icon(if (isSel) item.selectedIcon else item.icon, null,
                    tint = if (isSel) LoopaPalette.Ink else Loopa.colors.textSoft, modifier = Modifier.padding(vertical = 3.dp).size(24.dp))
            }
            if (item.badge > 0) CountBadge(item.badge, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-2).dp).testTag("nav_badge_${item.key}"))
        }
        Spacer(Modifier.heightIn(min = 2.dp))
        Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (isSel) Loopa.colors.text else Loopa.colors.textSoft)
    }
}

/** Small count bubble ("3", "9+") for unread items. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(Radii.pill).background(LoopaPalette.PeachDeep)
            .semantics { contentDescription = "$count yeni" }.padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else "$count", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Ink, maxLines = 1)
    }
}

/** Small honest note for features that do not exist yet ("Yakında"). */
@Composable
fun ComingSoonTag(modifier: Modifier = Modifier) {
    Text("Yakında", modifier.clip(Radii.pill).background(Loopa.colors.cardHigh).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
}
