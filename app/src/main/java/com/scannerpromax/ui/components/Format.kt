package com.scannerpromax.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CoPresent
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.ui.graphics.vector.ImageVector
import com.scannerpromax.domain.ScanMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Locale español usado en todos los formatos visibles. */
val SpanishLocale: Locale = Locale.forLanguageTag("es-ES")

/** "845 KB", "1,4 MB"… */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(SpanishLocale, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(SpanishLocale, if (mb < 10) "%.2f MB" else "%.1f MB", mb)
    return String.format(SpanishLocale, "%.2f GB", mb / 1024.0)
}

/** Fecha relativa en español: "Ahora mismo", "Hace 5 min", "Ayer, 14:32", "lunes", "3 oct 2026". */
fun relativeTime(time: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - time
    if (diff < 60_000L) return "Ahora mismo"
    if (diff < 3_600_000L) return "Hace ${diff / 60_000L} min"
    val cal = Calendar.getInstance().apply { timeInMillis = time }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Hace ${diff / 3_600_000L} h"
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val hour = SimpleDateFormat("HH:mm", SpanishLocale).format(Date(time))
    if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) return "Ayer, $hour"
    if (diff in 0 until 6L * 24 * 3_600_000L) {
        return SimpleDateFormat("EEEE", SpanishLocale).format(Date(time)).replaceFirstChar { it.titlecase(SpanishLocale) } + ", $hour"
    }
    val pattern = if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, SpanishLocale).format(Date(time))
}

/** "1 página" / "N páginas". */
fun pagesLabel(count: Int): String = if (count == 1) "1 página" else "$count páginas"

/** Icono representativo de cada modo de escaneo. */
val ScanMode.icon: ImageVector
    get() = when (this) {
        ScanMode.DOCUMENT -> Icons.Rounded.Description
        ScanMode.BOOK -> Icons.AutoMirrored.Rounded.MenuBook
        ScanMode.ID_CARD -> Icons.Rounded.Badge
        ScanMode.RECEIPT -> Icons.AutoMirrored.Rounded.ReceiptLong
        ScanMode.WHITEBOARD -> Icons.Rounded.CoPresent
        ScanMode.PHOTO -> Icons.Rounded.PhotoCamera
    }

/** Etiqueta corta para chips (los labels del dominio pueden ser largos). */
val ScanMode.shortLabel: String
    get() = when (this) {
        ScanMode.DOCUMENT -> "Documento"
        ScanMode.BOOK -> "Libro"
        ScanMode.ID_CARD -> "DNI"
        ScanMode.RECEIPT -> "Recibo"
        ScanMode.WHITEBOARD -> "Pizarra"
        ScanMode.PHOTO -> "Foto"
    }

/** Nombre de archivo seguro a partir de un título. */
fun safeFileName(title: String, fallback: String = "Escaneo"): String =
    title.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100).trim().ifEmpty { fallback }
