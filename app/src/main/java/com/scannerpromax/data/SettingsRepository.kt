package com.scannerpromax.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.scannerpromax.domain.ExportQuality
import com.scannerpromax.domain.FilterType
import com.scannerpromax.domain.PageSize
import com.scannerpromax.domain.PdfTextMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Preferencias (DataStore). Los enums se guardan por su name. */
class SettingsRepository(private val context: Context) {
    private val store get() = context.settingsDataStore

    val settings: Flow<AppSettings>
        get() = store.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it.toSettings() }
            .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val old = prefs.toSettings()
            val updated = transform(old).let { u ->
                // API antigua: si solo cambió el interruptor "PDF buscable", se traduce al modo de texto.
                if (u.pdfTextMode == old.pdfTextMode && u.searchablePdf != old.searchablePdf) {
                    u.copy(pdfTextMode = if (u.searchablePdf) PdfTextMode.BUSCABLE else PdfTextMode.SOLO_IMAGEN)
                } else u
            }
            prefs[Keys.DEFAULT_FILTER] = updated.defaultFilter.name
            prefs[Keys.AUTO_CAPTURE] = updated.autoCapture
            prefs[Keys.AUTO_REMOVE_LINES] = updated.autoRemoveLines
            prefs[Keys.PDF_PAGE_SIZE] = updated.pdfPageSize.name
            prefs[Keys.EXPORT_QUALITY] = updated.exportQuality.name
            prefs[Keys.PDF_TEXT_MODE] = updated.pdfTextMode.name
            prefs[Keys.SEARCHABLE_PDF] = updated.pdfTextMode != PdfTextMode.SOLO_IMAGEN
            prefs[Keys.DARK_THEME] = when (updated.darkTheme) {
                null -> THEME_SYSTEM
                true -> THEME_DARK
                false -> THEME_LIGHT
            }
            prefs[Keys.DYNAMIC_COLOR] = updated.dynamicColor
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        // Migración: antes solo existía "searchable_pdf" (true -> BUSCABLE, false -> SOLO_IMAGEN).
        val textMode = enumOrNull<PdfTextMode>(this[Keys.PDF_TEXT_MODE])
            ?: when (this[Keys.SEARCHABLE_PDF]) {
                false -> PdfTextMode.SOLO_IMAGEN
                true -> PdfTextMode.BUSCABLE
                null -> d.pdfTextMode
            }
        return AppSettings(
            // Sólo hay 3 filtros en la interfaz: un ajuste antiguo (Auto, Mágico Pro, Grises...) se mapea al visible
            defaultFilter = enumOr(this[Keys.DEFAULT_FILTER], d.defaultFilter).uiFilter,
            autoCapture = this[Keys.AUTO_CAPTURE] ?: d.autoCapture,
            autoRemoveLines = this[Keys.AUTO_REMOVE_LINES] ?: d.autoRemoveLines,
            pdfPageSize = enumOr(this[Keys.PDF_PAGE_SIZE], d.pdfPageSize),
            exportQuality = enumOr(this[Keys.EXPORT_QUALITY], d.exportQuality),
            searchablePdf = textMode != PdfTextMode.SOLO_IMAGEN,
            pdfTextMode = textMode,
            darkTheme = when (this[Keys.DARK_THEME]) {
                THEME_DARK -> true
                THEME_LIGHT -> false
                else -> null
            },
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: d.dynamicColor,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private object Keys {
        val DEFAULT_FILTER = stringPreferencesKey("default_filter")
        val AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val AUTO_REMOVE_LINES = booleanPreferencesKey("auto_remove_lines")
        val PDF_PAGE_SIZE = stringPreferencesKey("pdf_page_size")
        val EXPORT_QUALITY = stringPreferencesKey("export_quality")
        val SEARCHABLE_PDF = booleanPreferencesKey("searchable_pdf")
        val PDF_TEXT_MODE = stringPreferencesKey("pdf_text_mode")
        val DARK_THEME = stringPreferencesKey("dark_theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    private companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
    }
}

data class AppSettings(
    /** Filtro de las páginas nuevas: uno de [FilterType.visible] ("Texto resaltado" por defecto). */
    val defaultFilter: FilterType = FilterType.DEFAULT,
    val autoCapture: Boolean = true,
    val autoRemoveLines: Boolean = false,
    val pdfPageSize: PageSize = PageSize.AUTO,
    val exportQuality: ExportQuality = ExportQuality.HIGH,
    /** Compatibilidad: true si el modo de texto lleva OCR. Cambiarlo con copy() se traduce a [pdfTextMode]. */
    val searchablePdf: Boolean = true,
    val darkTheme: Boolean? = null, // null = seguir el sistema
    val dynamicColor: Boolean = false,
    /** Modo de texto por defecto de los PDF (Exportar y Compartir PDF desde Inicio). */
    val pdfTextMode: PdfTextMode = PdfTextMode.BUSCABLE,
)
