package com.aura.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/** The AURA mark (rings + dot on the dark tile), drawn as the window/taskbar icon. */
object AuraWindowIcon : Painter() {
    override val intrinsicSize = Size(108f, 108f)

    override fun DrawScope.onDraw() {
        val u = size.minDimension / 108f
        val center = Offset(size.width / 2, size.height / 2)
        drawRoundRect(Color(0xFF101114), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24 * u))
        drawCircle(Color(0xFFF5F5F7).copy(alpha = 0.28f), 24 * u, center, style = Stroke(4 * u))
        drawCircle(Color(0xFFF5F5F7).copy(alpha = 0.62f), 15 * u, center, style = Stroke(4 * u))
        drawCircle(Color(0xFFC7C2EE), 6.5f * u, center)
    }
}
