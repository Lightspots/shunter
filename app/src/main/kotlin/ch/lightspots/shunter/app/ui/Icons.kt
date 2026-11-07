package ch.lightspots.shunter.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The stroke icons of the design (24×24 view box, 2 px stroke), drawn black and tinted where used. */
object Icons {
    val library = icon("M12 3 3 8l9 5 9-5-9-5Z", "m3 13 9 5 9-5")
    val search = icon(circle(11f, 11f, 7f), "m20 20-4-4")
    val updates = icon("M12 19V5", "m5 12 7-7 7 7")
    val settings = icon(
        circle(12f, 12f, 3f),
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0" +
            "v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1" +
            "H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9" +
            "a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1" +
            "a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z",
    )
    val refresh = icon("M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5")
    val download = icon("M12 15V3", "m7 10 5 5 5-5", "M4 21h16")
    val warning = icon("M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z", "M12 9v4", "M12 17h.01")
    val check = icon("m5 12 5 5 9-10")
    val link = icon("M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7")
    val brokenLink = icon(
        "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7",
        "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7",
        "m3 3 18 18",
    )
    val folderWarning = icon("M3 7h6l2 2h10v10H3Z", "M12 13v2", "M12 18h.01")
    val arrowRight = icon("M5 12h14", "m13 6 6 6-6 6")
    val close = icon("M6 6l12 12", "M18 6 6 18")
    val chevronDown = icon("m6 9 6 6 6-6")
    val copies = icon("M8 8h12v12H8Z", "M4 16V4h12")

    /** The logo: a track curving up to a buffer stop, in the accent color. */
    val logo = ImageVector.Builder("logo", 28.dp, 28.dp, 28f, 28f).apply {
        stroked("M4 22h20", width = 2.4f)
        stroked("M4 22c8 0 10-14 20-14", width = 2.4f)
        addPath(addPathNodes(circle(24f, 8f, 2.2f)), fill = SolidColor(Color.Black))
    }.build()

    private fun icon(vararg paths: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply { paths.forEach { stroked(it) } }.build()

    private fun ImageVector.Builder.stroked(path: String, width: Float = 2f) {
        addPath(
            addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"
}
