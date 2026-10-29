package com.aura.music.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing

/** AURA's search input: a quiet filled pill with a clear action. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Radius.pill)
            .background(AuraTheme.colors.surface)
            .padding(start = Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AuraIcons.Search, contentDescription = null, tint = AuraTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(horizontal = Spacing.x3), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = AuraTheme.type.body, color = AuraTheme.colors.textTertiary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AuraTheme.type.body.copy(color = AuraTheme.colors.textPrimary),
                cursorBrush = SolidColor(AuraTheme.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            AuraIconButton(AuraIcons.Close, stringResource(R.string.search_clear), { onValueChange("") }, tint = AuraTheme.colors.textSecondary, iconSize = 18.dp)
        } else {
            Box(Modifier.width(Spacing.x4))
        }
    }
}
