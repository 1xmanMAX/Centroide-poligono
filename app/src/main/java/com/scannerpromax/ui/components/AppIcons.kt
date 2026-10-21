package com.scannerpromax.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Iconos de trazo fino (24×24, trazo 1,8, extremos redondeados) copiados de la maqueta aprobada.
 * Un único estilo para toda la interfaz rediseñada; se tiñen con `Icon(tint = …)` como cualquier ImageVector.
 * Se construyen una vez (lazy) y se reutilizan.
 */
object AppIcons {
    /** ••• (relleno). */
    val More: ImageVector by lazy { filled("More", circle(5f, 12f, 1.8f) + circle(12f, 12f, 1.8f) + circle(19f, 12f, 1.8f)) }
    val Search by lazy { stroke("Search", circle(11f, 11f, 7f) + "M20 20l-3.5-3.5", width = 2f) }
    val Gallery by lazy { stroke("Gallery", rect(3f, 4f, 18f, 16f, 3f) + circle(9f, 10f, 1.8f) + "M21 16l-5-5-9 9") }
    val Scan by lazy { stroke("Scan", "M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M7 12h10", width = 2f) }
    val Back by lazy { stroke("Back", "M15 5l-7 7 7 7", width = 2.2f) }
    val Chevron by lazy { stroke("Chevron", "M9 5l7 7-7 7", width = 2.4f) }
    val Check by lazy { stroke("Check", "M5 12.5l4.5 4.5L19 7.5", width = 2.2f) }
    val Close by lazy { stroke("Close", "M6 6l12 12M18 6L6 18", width = 2f) }
    /** Círculo relleno con aspa (borrar búsqueda). */
    val ClearFilled by lazy {
        ImageVector.Builder("ClearFilled", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes(circle(12f, 12f, 9f)), fill = SolidColor(Color.Black))
        }.build()
    }
    val Select by lazy { stroke("Select", "M9 12l2 2 4-4" + circle(12f, 12f, 9f)) }
    val List by lazy { stroke("List", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01") }
    val Grid by lazy { stroke("Grid", rect(4f, 4f, 7f, 7f, 1.5f) + rect(13f, 4f, 7f, 7f, 1.5f) + rect(4f, 13f, 7f, 7f, 1.5f) + rect(13f, 13f, 7f, 7f, 1.5f)) }
    val Sort by lazy { stroke("Sort", "M3 6h18M6 12h12M10 18h4") }
    val ImportPhotos by lazy { stroke("ImportPhotos", "M3 7a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3zM21 16l-5-5-9 9") }
    val Compress by lazy { stroke("Compress", "M8 3h6l5 5v13H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM10 13l2 2 2-2M12 9v6") }
    val Settings by lazy { stroke("Settings", circle(12f, 12f, 3f) + "M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1") }
    val Rename by lazy { stroke("Rename", "M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17zM14 7l3 3") }
    val Duplicate by lazy { stroke("Duplicate", rect(8f, 8f, 12f, 12f, 2f) + "M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2") }
    val Share by lazy { stroke("Share", "M12 3v12M7 8l5-5 5 5M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5") }
    val Trash by lazy { stroke("Trash", "M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3") }
    val Plus by lazy { stroke("Plus", "M12 5v14M5 12h14", width = 1.9f) }
    val Text by lazy { stroke("Text", "M4 7V5h16v2M9 19h6M12 5v14", width = 1.9f) }
    val Wand by lazy { stroke("Wand", "M15 4V2M15 10V8M11 6h2M17 6h2M5 20l11-11 1.5 1.5L6.5 21.5z", width = 1.9f) }
    val Document by lazy { stroke("Document", "M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM14 3v5h5M9 13h6M9 17h4") }

    // Ajustes (dentro de cuadrados de color, trazo 2)
    val FilterHalf by lazy { stroke("FilterHalf", circle(12f, 12f, 9f) + "M12 3v18", width = 2f) }
    val Bolt by lazy { stroke("Bolt", "M13 2L4 14h7l-1 8 9-12h-7z", width = 2f) }
    val Eraser by lazy { stroke("Eraser", "M20 20H9l-5-5 9-9 7 7-5 5", width = 2f) }
    val Camera by lazy { stroke("Camera", "M4 8a2 2 0 0 1 2-2h2l1.5-2h5L16 6h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z" + circle(12f, 13f, 3.5f), width = 2f) }
    val Download by lazy { stroke("Download", "M12 3v12M7 10l5 5 5-5M5 21h14", width = 2f) }
    val Cloud by lazy { stroke("Cloud", "M7 18a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17 8.5a4.5 4.5 0 0 1 0 9.5z", width = 2f) }
    val Export by lazy { stroke("Export", "M12 3v12M7 8l5-5 5 5M5 14v5h14v-5", width = 2f) }
    val Moon by lazy { stroke("Moon", "M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9z", width = 2f) }
    val Info by lazy { stroke("Info", "M12 8h.01M11 12h1v5h1" + circle(12f, 12f, 9f), width = 2f) }
    val Page by lazy { stroke("Page", "M7 3h7l5 5v13H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM14 3v5h5", width = 2f) }
    val Quality by lazy { stroke("Quality", "M12 3l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.4 6.8 19.1l1-5.8L3.5 9.2l5.9-.9z", width = 2f) }
    val Palette by lazy { stroke("Palette", "M12 3a9 9 0 0 0 0 18c1.1 0 1.6-.8 1.6-1.6 0-.9-.7-1.3-.7-2.1 0-.9.7-1.6 1.6-1.6H17a4 4 0 0 0 4-4c0-4.7-4-8.7-9-8.7z" + circle(7.5f, 11f, 0.8f) + circle(10f, 7.5f, 0.8f) + circle(14.5f, 7.5f, 0.8f), width = 2f) }
    val Phone by lazy { stroke("Phone", rect(7f, 2f, 10f, 20f, 2f) + "M11 18h2", width = 2f) }
    val Speed by lazy { stroke("Speed", "M4 16a8 8 0 1 1 16 0M12 16l4-5", width = 2f) }
    val Chip by lazy { stroke("Chip", rect(6f, 6f, 12f, 12f, 2f) + "M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4", width = 2f) }
    val Resolution by lazy { stroke("Resolution", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5", width = 2f) }
    val Shield by lazy { stroke("Shield", "M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z", width = 2f) }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}" +
            "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

    private fun stroke(name: String, path: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        }.build()
}
