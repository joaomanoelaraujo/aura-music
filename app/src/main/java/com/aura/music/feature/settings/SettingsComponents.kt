package com.aura.music.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing

@Composable
fun SettingsSection(title: String) {
    Text(
        title.uppercase(),
        style = AuraTheme.type.overline,
        color = AuraTheme.colors.accent,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x8, bottom = Spacing.x2),
    )
}

@Composable
fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = AuraTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x3)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraTheme.type.titleSmall, color = colors.textPrimary)
            if (body != null) Text(body, style = AuraTheme.type.bodySmall, color = colors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.surfaceElevated,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
}

/** A labelled single choice shown as chips; wraps onto several lines on narrow screens. */
@Composable
fun <T> ChoiceRow(title: String, body: String?, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.x3)) {
        Text(title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, modifier = Modifier.padding(horizontal = Spacing.gutter))
        if (body != null) {
            Text(body, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = Spacing.gutter))
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            options.forEach { option -> AuraChip(label(option), option == selected, onClick = { onSelect(option) }) }
        }
    }
}

/** Several independent toggles shown as chips. */
@Composable
fun <T> MultiChoiceRow(title: String, body: String?, options: List<T>, selected: Set<T>, label: @Composable (T) -> String, onToggle: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.x3)) {
        Text(title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, modifier = Modifier.padding(horizontal = Spacing.gutter))
        if (body != null) {
            Text(body, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = Spacing.gutter))
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            options.forEach { option -> AuraChip(label(option), option in selected, onClick = { onToggle(option) }) }
        }
    }
}

/**
 * A 0–100 % slider. The value is committed when the finger lifts, so dragging doesn't write the
 * preference dozens of times while the preview still follows the finger.
 */
@Composable
fun PercentSliderRow(title: String, body: String?, value: Float, onCommit: (Float) -> Unit, enabled: Boolean = true) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging
    val percent = "${(shown * 100).toInt()}%"
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x3).alpha(if (enabled) 1f else 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(percent, style = AuraTheme.type.numeric, color = AuraTheme.colors.textSecondary)
        }
        if (body != null) Text(body, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (!dragging.isNaN()) onCommit(dragging)
                dragging = Float.NaN
            },
            enabled = enabled,
            steps = 9,
            colors = sliderColors(),
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = percent
            },
        )
    }
}

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = AuraTheme.colors.accent,
    activeTrackColor = AuraTheme.colors.accent,
    inactiveTrackColor = AuraTheme.colors.surfaceElevated,
    activeTickColor = AuraTheme.colors.onAccent.copy(alpha = 0.4f),
    inactiveTickColor = AuraTheme.colors.textTertiary.copy(alpha = 0.4f),
)

@Composable
fun ActionRow(icon: ImageVector?, title: String, body: String?, onClick: () -> Unit, enabled: Boolean = true, tint: androidx.compose.ui.graphics.Color = AuraTheme.colors.textPrimary) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraTheme.type.titleSmall, color = if (enabled) tint else AuraTheme.colors.textTertiary)
            if (body != null) Text(body, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (enabled) AuraTheme.colors.textSecondary else AuraTheme.colors.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

/** Entry on the settings hub leading to one category. */
@Composable
fun CategoryRow(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Box(
            Modifier.size(44.dp).clip(Radius.medium).background(AuraTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AuraTheme.colors.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary)
            Text(body, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 2)
        }
        Icon(com.aura.music.core.designsystem.icons.AuraIcons.ChevronRight, contentDescription = null, tint = AuraTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary)
        Text(value, style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary)
    }
}

@Composable
fun NoteText(text: String) {
    Text(
        text,
        style = AuraTheme.type.bodySmall,
        color = AuraTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
    )
}
