package com.scannerpromax.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.scannerpromax.ai.RecognitionEngine
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
            prefs[Keys.USE_SYSTEM_CAMERA] = updated.useSystemCamera
            prefs[Keys.RECOGNITION_ENGINE] = updated.recognitionEngine.name
            prefs[Keys.AI_WIFI_ONLY] = updated.aiWifiOnly
            prefs[Keys.AI_QUANTIZE_ON_DEVICE] = updated.aiQuantizeOnDevice
            prefs[Keys.AI_AUTO_ENHANCE] = updated.aiAutoEnhance
            prefs[Keys.AI_FRAME_RESCUE] = updated.aiFrameRescue
            prefs[Keys.CLOUD_WIFI_ONLY] = updated.cloudWifiOnly
            prefs[Keys.CLOUD_CONSENT_DEEPSEEK] = updated.cloudConsentDeepSeek
            prefs[Keys.CLOUD_CONSENT_CLAUDE] = updated.cloudConsentClaude
            prefs[Keys.ATTACH_ANY_FORMAT] = updated.attachAnyFormat.name
            prefs[Keys.ATTACH_SAVE_COPY] = updated.attachSaveCopy
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
            useSystemCamera = this[Keys.USE_SYSTEM_CAMERA] ?: d.useSystemCamera,
            recognitionEngine = enumOr(this[Keys.RECOGNITION_ENGINE], d.recognitionEngine),
            aiWifiOnly = this[Keys.AI_WIFI_ONLY] ?: d.aiWifiOnly,
            aiQuantizeOnDevice = this[Keys.AI_QUANTIZE_ON_DEVICE] ?: d.aiQuantizeOnDevice,
            aiAutoEnhance = this[Keys.AI_AUTO_ENHANCE] ?: d.aiAutoEnhance,
            aiFrameRescue = this[Keys.AI_FRAME_RESCUE] ?: d.aiFrameRescue,
            cloudWifiOnly = this[Keys.CLOUD_WIFI_ONLY] ?: d.cloudWifiOnly,
            cloudConsentDeepSeek = this[Keys.CLOUD_CONSENT_DEEPSEEK] ?: d.cloudConsentDeepSeek,
            cloudConsentClaude = this[Keys.CLOUD_CONSENT_CLAUDE] ?: d.cloudConsentClaude,
            attachAnyFormat = enumOr(this[Keys.ATTACH_ANY_FORMAT], d.attachAnyFormat),
            attachSaveCopy = this[Keys.ATTACH_SAVE_COPY] ?: d.attachSaveCopy,
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
        val USE_SYSTEM_CAMERA = booleanPreferencesKey("use_system_camera")
        val RECOGNITION_ENGINE = stringPreferencesKey("recognition_engine")
        val AI_WIFI_ONLY = booleanPreferencesKey("ai_wifi_only")
        val AI_QUANTIZE_ON_DEVICE = booleanPreferencesKey("ai_quantize_on_device")
        val AI_AUTO_ENHANCE = booleanPreferencesKey("ai_auto_enhance")
        val AI_FRAME_RESCUE = booleanPreferencesKey("ai_frame_rescue")
        val CLOUD_WIFI_ONLY = booleanPreferencesKey("cloud_wifi_only")
        val CLOUD_CONSENT_DEEPSEEK = booleanPreferencesKey("cloud_consent_deepseek")
        val CLOUD_CONSENT_CLAUDE = booleanPreferencesKey("cloud_consent_claude")
        val ATTACH_ANY_FORMAT = stringPreferencesKey("attach_any_format")
        val ATTACH_SAVE_COPY = booleanPreferencesKey("attach_save_copy")
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
    /**
     * Al abrir el escáner, lanzar directamente la app de cámara del teléfono (su procesado multi-cuadro, HDR y
     * nitidez) y procesar la foto devuelta en el flujo normal del modo. La cámara propia sigue disponible.
     */
    val useSystemCamera: Boolean = false,
    /**
     * Motor de reconocimiento principal (Ajustes > Modelos IA). BASIC = ML Kit incluido; los otros requieren
     * haber descargado el modelo (si no está, la tubería debe caer a BASIC).
     */
    val recognitionEngine: RecognitionEngine = RecognitionEngine.BASIC,
    /** Descargar modelos de IA solo con Wi-Fi (red no medida). */
    val aiWifiOnly: Boolean = true,
    /** Tras descargar el Lector de documentos, cuantizarlo en el móvil (Q8_0: ~mitad de RAM y disco). */
    val aiQuantizeOnDevice: Boolean = false,
    /**
     * Con el Asistente visual descargado: clasificar cada página (recibo, pizarra, pantalla…) y, si sigue con el filtro
     * por defecto, elegir la mejora automática adecuada a ese tipo.
     */
    val aiAutoEnhance: Boolean = true,
    /** Con el Asistente visual descargado: si no se detectan los bordes, pedirle dónde está el documento. */
    val aiFrameRescue: Boolean = true,
    /** IA en la nube: enviar páginas solo con Wi-Fi / red no medida (si no, esperan en la cola). */
    val cloudWifiOnly: Boolean = false,
    /** Consentimiento explícito (una vez por proveedor) para enviar páginas a sus servidores. */
    val cloudConsentDeepSeek: Boolean = false,
    val cloudConsentClaude: Boolean = false,
    /**
     * Integración con otras apps: formato que se devuelve cuando la otra app acepta cualquier tipo de archivo
     * (Adjuntar con "cualquier archivo"). Si pide solo imágenes o solo PDF, se respeta lo que pide.
     */
    val attachAnyFormat: com.scannerpromax.integration.AnyFormatDefault = com.scannerpromax.integration.AnyFormatDefault.PDF,
    /** Integración con otras apps: guardar también el escaneo en la biblioteca (Documentos). */
    val attachSaveCopy: Boolean = true,
) {
    fun cloudConsent(id: com.scannerpromax.ai.cloud.CloudProviderId): Boolean = when (id) {
        com.scannerpromax.ai.cloud.CloudProviderId.DEEPSEEK -> cloudConsentDeepSeek
        com.scannerpromax.ai.cloud.CloudProviderId.CLAUDE_HAIKU -> cloudConsentClaude
    }

    fun withCloudConsent(id: com.scannerpromax.ai.cloud.CloudProviderId, v: Boolean): AppSettings = when (id) {
        com.scannerpromax.ai.cloud.CloudProviderId.DEEPSEEK -> copy(cloudConsentDeepSeek = v)
        com.scannerpromax.ai.cloud.CloudProviderId.CLAUDE_HAIKU -> copy(cloudConsentClaude = v)
    }
}
