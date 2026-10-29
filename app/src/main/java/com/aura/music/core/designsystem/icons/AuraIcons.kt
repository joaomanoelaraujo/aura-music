package com.aura.music.core.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * AURA's own icon set: 24-unit grid, 1.75 stroke, rounded caps and joins.
 * Transport controls are filled so they read as primary actions.
 */
object AuraIcons {
    val Home by lazy { stroke("Home", "M4.5 10.2 12 4.5l7.5 5.7V18.5a1 1 0 0 1-1 1H15v-5.5H9v5.5H5.5a1 1 0 0 1-1-1z") }
    val Library by lazy { stroke("Library", "M5 4.5v15 M9.5 4.5v15 M14 5.5l4.6 14") }
    val Search by lazy { stroke("Search", "M11 4.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13z M16 16l3.8 3.8") }
    val Tune by lazy {
        stroke("Tune", "M4 7h8.5 M17.5 7H20 M4 17h2.5 M11.5 17H20 M15 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4z M9 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z")
    }
    val Play by lazy { fill("Play", "M8 5.6v12.8a1 1 0 0 0 1.53.85l10.1-6.4a1 1 0 0 0 0-1.7L9.53 4.75A1 1 0 0 0 8 5.6z") }
    val Pause by lazy {
        fill(
            "Pause",
            "M7.5 5h2.5a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7.5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z " +
                "M14 5h2.5a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H14a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
        )
    }
    val Next by lazy {
        fill(
            "Next",
            "M5 6.3v11.4a.9.9 0 0 0 1.4.74l8.3-5.7a.9.9 0 0 0 0-1.48L6.4 5.56A.9.9 0 0 0 5 6.3z " +
                "M17.6 5.5a1 1 0 0 1 1 1v11a1 1 0 0 1-2 0v-11a1 1 0 0 1 1-1z",
        )
    }
    val Previous by lazy {
        fill(
            "Previous",
            "M19 6.3v11.4a.9.9 0 0 1-1.4.74l-8.3-5.7a.9.9 0 0 1 0-1.48l8.3-5.7A.9.9 0 0 1 19 6.3z " +
                "M6.4 5.5a1 1 0 0 0-1 1v11a1 1 0 0 0 2 0v-11a1 1 0 0 0-1-1z",
        )
    }
    val Shuffle by lazy {
        stroke(
            "Shuffle",
            "M4 7h3.2c2 0 3.2 1 4.3 2.7l2.3 3.6c1.1 1.7 2.3 2.7 4.3 2.7H20 M4 17h3.2c1.4 0 2.4-.5 3.2-1.4 " +
                "M14.6 8.4c.8-.9 1.8-1.4 3.2-1.4H20 M17.6 4.6 20 7l-2.4 2.4 M17.6 14.6 20 17l-2.4 2.4",
        )
    }
    val Repeat by lazy {
        stroke("Repeat", "M4.5 11V9.5A2.5 2.5 0 0 1 7 7h12 M16.6 4.6 19 7l-2.4 2.4 M19.5 13v1.5a2.5 2.5 0 0 1-2.5 2.5H5 M7.4 19.4 5 17l2.4-2.4")
    }
    val RepeatOne by lazy {
        stroke(
            "RepeatOne",
            "M4.5 11V9.5A2.5 2.5 0 0 1 7 7h12 M16.6 4.6 19 7l-2.4 2.4 M19.5 13v1.5a2.5 2.5 0 0 1-2.5 2.5H5 " +
                "M7.4 19.4 5 17l2.4-2.4 M11.3 10.6l1.2-.6v4.2",
        )
    }
    private const val HEART = "M12 19.3s-7.5-4.3-7.5-9.5A4.1 4.1 0 0 1 8.6 5.6c1.5 0 2.7.8 3.4 2 .7-1.2 1.9-2 3.4-2a4.1 4.1 0 0 1 4.1 4.2c0 5.2-7.5 9.5-7.5 9.5z"
    val Heart by lazy { stroke("Heart", HEART) }
    val HeartFilled by lazy { fill("HeartFilled", HEART) }
    val Queue by lazy { stroke("Queue", "M4 6.5h12 M4 11.5h12 M4 16.5h6.5 M14.5 14v5.5l4.5-2.75z") }
    val More by lazy {
        fill(
            "More",
            "M6 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z M12 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z " +
                "M18 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z",
        )
    }
    val Back by lazy { stroke("Back", "M14.5 5.5 8 12l6.5 6.5") }
    val ChevronDown by lazy { stroke("ChevronDown", "M6 9.5l6 6 6-6") }
    val ChevronRight by lazy { stroke("ChevronRight", "M9.5 6l6 6-6 6") }
    val Plus by lazy { stroke("Plus", "M12 5v14 M5 12h14") }
    val Close by lazy { stroke("Close", "M6.5 6.5l11 11 M17.5 6.5l-11 11") }
    val DragHandle by lazy { stroke("DragHandle", "M5 9.5h14 M5 14.5h14") }
    val Folder by lazy {
        stroke("Folder", "M3.5 7.5A1.5 1.5 0 0 1 5 6h4l2 2h8a1.5 1.5 0 0 1 1.5 1.5v8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z")
    }
    val Album by lazy { stroke("Album", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z M12 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4z") }
    val Artist by lazy { stroke("Artist", "M12 4.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7z M5 19.5c.8-3.3 3.6-5.5 7-5.5s6.2 2.2 7 5.5") }
    val Playlist by lazy { stroke("Playlist", "M4 6.5h11 M4 11.5h11 M4 16.5h6 M15 16.5a2 2 0 1 0 2 2V9l3-1") }
    val Note by lazy {
        stroke("Note", "M9 17.5V6.5L19 4v11 M6.5 15a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z M16.5 12.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z")
    }
    val Wave by lazy { stroke("Wave", "M4 11v2 M7.5 8v8 M11 5v14 M14.5 9v6 M18 7v10 M21 11v2") }
    val Grid by lazy {
        stroke(
            "Grid",
            "M5.5 4.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z " +
                "M15.5 4.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z " +
                "M5.5 14.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z " +
                "M15.5 14.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z",
        )
    }
    val ListLayout by lazy { stroke("ListLayout", "M4 6.5h16 M4 12h16 M4 17.5h16") }
    val Sort by lazy { stroke("Sort", "M7 4.5v15 M4 16.5l3 3 3-3 M13 7h7 M13 12h5 M13 17h3") }
    val Info by lazy { stroke("Info", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z M12 11v5 M12 7.9v.1") }
    val Check by lazy { stroke("Check", "M5 12.5l4.5 4.5L19 7.5") }
    val Trash by lazy {
        stroke(
            "Trash",
            "M4.5 7h15 M9.5 7V5.5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1V7 M6.5 7l.8 11.2a1.5 1.5 0 0 0 1.5 1.3h6.4a1.5 1.5 0 0 0 1.5-1.3L17.5 7",
        )
    }
    val Edit by lazy { stroke("Edit", "M14.5 5.5l4 4 M4.5 19.5l1-4.5L15.8 4.7a1.4 1.4 0 0 1 2 0l1.5 1.5a1.4 1.4 0 0 1 0 2L9 18.5z") }
    val PlayNext by lazy { stroke("PlayNext", "M4 6.5h10 M4 11.5h10 M4 16.5h6 M15 13.5l5 3-5 3z") }
    val AddToQueue by lazy { stroke("AddToQueue", "M4 6.5h12 M4 11.5h12 M4 16.5h7 M17 13.5v6 M14 16.5h6") }
    val Refresh by lazy { stroke("Refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3 M19.5 4.5v4h-4") }
    val Save by lazy { stroke("Save", "M4 6.5h12 M4 11.5h12 M4 16.5h6 M14.5 16.5l2.2 2.2 3.8-4.2") }
    val Together by lazy {
        stroke("Together", "M9 5a3 3 0 1 1 0 6a3 3 0 1 1 0-6z M3.5 19c.6-3 2.8-5 5.5-5s4.9 2 5.5 5 M16 6.2a2.6 2.6 0 1 1 0 5.2 M17.5 14.3c1.7.6 2.8 2.3 3 4.7")
    }
    val Download by lazy { stroke("Download", "M12 4.5v10 M7.5 10.5 12 15l4.5-4.5 M5 19.5h14") }
    val Discover by lazy { stroke("Discover", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z M15.5 8.5l-2 5-5 2 2-5z") }
    val History by lazy { stroke("History", "M4.5 12a7.5 7.5 0 1 0 2.2-5.3 M4.5 4.5v3.7h3.7 M12 8v4.3l2.8 1.7") }
    val Lyrics by lazy { stroke("Lyrics", "M4 6.5h10 M4 11h7 M4 15.5h5 M15.5 19a2 2 0 1 1 0-4h2V8.5l2.5-1") }
    val Timer by lazy { stroke("Timer", "M12 7a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13z M12 10.5v3l2 1.5 M9.5 3.5h5") }
    val Equalizer by lazy { stroke("Equalizer", "M6 4.5v15 M12 4.5v15 M18 4.5v15 M4 14h4 M10 8h4 M16 16h4") }
    val Expand by lazy { stroke("Expand", "M14.5 4.5h5v5 M9.5 19.5h-5v-5 M19.5 4.5 13.5 10.5 M4.5 19.5l6-6") }
    val Shrink by lazy { stroke("Shrink", "M19.5 9.5h-5v-5 M4.5 14.5h5v5 M14.5 9.5l5-5 M9.5 14.5l-5 5") }
    val Image by lazy {
        stroke("Image", "M5.5 4.5h13a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z M4.5 16l4.5-4.5 4 4 2.5-2.5 4 4 M15 8.5h.01")
    }
    val Mic by lazy { stroke("Mic", "M12 3.5a2.8 2.8 0 0 1 2.8 2.8v5.2a2.8 2.8 0 0 1-5.6 0V6.3A2.8 2.8 0 0 1 12 3.5z M6.5 11.5a5.5 5.5 0 0 0 11 0 M12 17v3.5") }
    val Chat by lazy { stroke("Chat", "M5.5 5h13a1.5 1.5 0 0 1 1.5 1.5v8.5a1.5 1.5 0 0 1-1.5 1.5H10l-4 3.5v-3.5h-.5A1.5 1.5 0 0 1 4 15V6.5A1.5 1.5 0 0 1 5.5 5z") }
    val EyeOff by lazy { stroke("EyeOff", "M4 4l16 16 M10.6 6.1A8.7 8.7 0 0 1 12 6c5 0 8.5 6 8.5 6a14 14 0 0 1-2.4 3 M6.2 7.8C4.4 9.4 3.5 12 3.5 12s3.5 6 8.5 6a7.6 7.6 0 0 0 3.6-.9") }
    val Eye by lazy { stroke("Eye", "M3.5 12s3.5-6 8.5-6 8.5 6 8.5 6-3.5 6-8.5 6-8.5-6-8.5-6z M12 9.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z") }
    val Minus by lazy { stroke("Minus", "M5 12h14") }
    val ArrowUp by lazy { stroke("ArrowUp", "M12 19V5 M6 11l6-6 6 6") }
    val ArrowDown by lazy { stroke("ArrowDown", "M12 5v14 M6 13l6 6 6-6") }
    val FileText by lazy { stroke("FileText", "M13.5 3.5H7a1.5 1.5 0 0 0-1.5 1.5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8.5z M13.5 3.5v5h5 M9 13h6 M9 16.5h4") }

    private fun stroke(name: String, path: String): ImageVector = build(name) {
        addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun fill(name: String, path: String): ImageVector = build(name) {
        addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
    }

    private inline fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()
}
