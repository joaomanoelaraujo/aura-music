package com.aura.music.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.designsystem.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AuraTheme.colors.surface,
        contentColor = AuraTheme.colors.textPrimary,
        scrimColor = AuraTheme.colors.scrim,
        shape = Radius.xLarge.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = Spacing.x3, bottom = Spacing.x2)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(Radius.pill)
                    .background(AuraTheme.colors.textTertiary.copy(alpha = 0.5f)),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Spacing.x3), content = content)
    }
}

/** Header shown at the top of action sheets, usually identifying the selected item. */
@Composable
fun SheetHeader(title: String, subtitle: String?, leading: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraTheme.type.title, color = AuraTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = AuraTheme.colors.textPrimary) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.icon))
        Text(label, style = AuraTheme.type.body, color = tint)
    }
}

/** Single-line text dialog used to create and rename playlists. */
@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    placeholder: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length))) }
    val focus = remember { FocusRequester() }
    val canConfirm = value.text.isNotBlank()
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraTheme.colors.surfaceElevated,
        shape = Radius.xLarge,
        title = { Text(title, style = AuraTheme.type.headline, color = AuraTheme.colors.textPrimary) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(Radius.small)
                    .background(AuraTheme.colors.surface)
                    .border(1.dp, AuraTheme.colors.outline, Radius.small)
                    .padding(horizontal = Spacing.x4, vertical = Spacing.x4),
            ) {
                if (value.text.isEmpty()) {
                    Text(placeholder, style = AuraTheme.type.body, color = AuraTheme.colors.textTertiary)
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = AuraTheme.type.body.copy(color = AuraTheme.colors.textPrimary),
                    cursorBrush = SolidColor(AuraTheme.colors.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            AuraButton(confirmLabel, onClick = { onConfirm(value.text) }, enabled = canConfirm)
        },
        dismissButton = {
            AuraButton(dismissLabel, onClick = onDismiss, style = ButtonStyle.Secondary)
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraTheme.colors.surfaceElevated,
        shape = Radius.xLarge,
        title = { Text(title, style = AuraTheme.type.headline, color = AuraTheme.colors.textPrimary) },
        text = { Text(message, style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary) },
        confirmButton = {
            AuraButton(confirmLabel, onClick = onConfirm, accent = AuraTheme.colors.danger, onAccent = AuraTheme.colors.onAccent)
        },
        dismissButton = { AuraButton(dismissLabel, onClick = onDismiss, style = ButtonStyle.Secondary) },
    )
}

/** Horizontally scrolling set of chips acting as tabs. */
@Composable
fun <T> ChipTabs(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        items.forEach { item -> AuraChip(label(item), item == selected, onClick = { onSelect(item) }) }
    }
}

@Composable
fun AuraSnackbar(data: SnackbarData) {
    Snackbar(
        modifier = Modifier.padding(horizontal = Spacing.x4),
        shape = Radius.medium,
        containerColor = AuraTheme.colors.surfaceHighest,
        contentColor = AuraTheme.colors.textPrimary,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(data.visuals.message, style = AuraTheme.type.bodySmall, modifier = Modifier.weight(1f))
            Box(Modifier.width(Spacing.x2))
        }
    }
}
