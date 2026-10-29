package com.aura.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.aura.music.core.designsystem.icons.AuraIcons

/** Round icon button with a hover state and a tooltip naming the action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuraIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Aura.colors.textSecondary,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    TooltipArea(
        tooltip = {
            Text(
                label,
                style = AuraType.small,
                color = c.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.surfaceHighest).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
        delayMillis = 600,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
    ) {
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(if (hovered && enabled) c.surfaceHighest.copy(alpha = 0.7f) else Color.Transparent)
                .hoverable(interaction)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            val color = when {
                !enabled -> c.textTertiary.copy(alpha = 0.5f)
                active -> c.accent
                hovered -> c.textPrimary
                else -> tint
            }
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
            if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).clip(CircleShape).background(c.accent))
        }
    }
}

/** Pill button; [primary] uses the accent colour. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, primary: Boolean = true, modifier: Modifier = Modifier) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        primary -> if (hovered) c.accent.copy(alpha = 0.9f) else c.accent
        hovered -> c.surfaceHighest
        else -> Color.Transparent
    }
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (primary) c.onAccent else c.textPrimary
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, style = AuraType.bodyStrong, color = fg)
    }
}

/**
 * Thin progress bar that thickens on hover; click or drag to choose a value in 0..1.
 * [onChange] fires while dragging, [onCommit] once released.
 */
@Composable
fun ScrubBar(
    fraction: Float,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (hovered || dragging) 6.dp else 4.dp)
    BoxWithConstraints(
        modifier
            .height(18.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onChange(f)
                    onCommit(f)
                }
            }
            .pointerInput(Unit) {
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; last = (o.x / size.width).coerceIn(0f, 1f); onChange(last) },
                    onDragEnd = { dragging = false; onCommit(last) },
                    onDragCancel = { dragging = false; onCommit(last) },
                ) { change, _ ->
                    last = (change.position.x / size.width).coerceIn(0f, 1f)
                    onChange(last)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val accent = c.accent
        val track = Color.White.copy(alpha = 0.14f)
        val showThumb = hovered || dragging
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = thickness.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
            val w = size.width * fraction.coerceIn(0f, 1f)
            if (w > 0) drawRoundRect(accent, Offset(0f, y), Size(w, h), r)
            if (showThumb) drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(w, size.height / 2))
        }
    }
}

/** Search field; Esc clears it. */
@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Aura.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) c.surfaceElevated else c.surface)
            .border(1.dp, if (focused) c.accent.copy(alpha = 0.5f) else c.outline, RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AuraIcons.Search, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Buscar músicas, artistas e álbuns  (Ctrl+F)", style = AuraType.body, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = AuraType.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                    .semantics { contentDescription = "Buscar" },
            )
        }
        if (query.isNotEmpty()) AuraIconButton(AuraIcons.Close, "Limpar busca", { onQuery("") }, size = 28.dp, iconSize = 16.dp)
    }
}

/** AURA's mark: two rings and a dot in the accent colour. */
@Composable
fun AuraMark(size: Dp, modifier: Modifier = Modifier) {
    val accent = Aura.colors.accent
    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val unit = this.size.minDimension / 108f
        drawCircle(Color.White.copy(alpha = 0.28f), radius = 24 * unit * 1.6f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * unit * 1.6f))
        drawCircle(Color.White.copy(alpha = 0.62f), radius = 15 * unit * 1.6f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f * unit * 1.6f))
        drawCircle(accent, radius = 6.5f * unit * 1.6f, center = center)
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Aura.colors.outline))
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(Aura.colors.outline))
}
