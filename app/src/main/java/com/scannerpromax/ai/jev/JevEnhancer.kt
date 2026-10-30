package com.scannerpromax.ai.jev

import android.content.Context
import android.util.Log
import com.scannerpromax.ai.cloud.CloudException
import com.scannerpromax.ai.cloud.CloudKeyStore
import com.scannerpromax.ai.cloud.CloudNetwork
import com.scannerpromax.domain.OcrResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.TimeUnit

/** Preferencias de "Mejorar con Jev" y uso acumulado (Ajustes › IA en la nube › Jev). */
data class JevSettings(
    /** Interruptor general. Apagado por defecto y sin efecto si no hay clave. */
    val enabled: Boolean = false,
    val photos: Boolean = true,
    val tables: Boolean = true,
    /** Verificar el OCR dudoso: apagado por defecto (en el banco no mejora el CER, notes_jev.md). */
    val ocr: Boolean = false,
    /**
     * Al capturar o importar: tipo de documento (modo y filtro), carpeta sugerida, rescate del encuadre / "no hay
     * documento" y avisos de calidad. Una petición por foto (≈ 2 600 tokens).
     */
    val capture: Boolean = true,
    /** Firmas y sellos con su color en "Color natural" (en la petición de la página: casi gratis). */
    val inks: Boolean = true,
    val pages: Long = 0,
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val lastError: String? = null,
    /**
     * Consentimiento explícito (divulgación destacada de Google Play) para enviar imágenes a TypeSafe. Sin él no sale
     * ninguna petición aunque [enabled] esté activo (p. ej. ajustes de versiones anteriores).
     */
    val consent: Boolean = false,
) {
    val costUsd: Double get() = JevApi.cost(inputTokens)
    /** Interruptor efectivo: activado Y consentido. */
    val on: Boolean get() = enabled && consent
}

/**
 * "Mejorar con Jev" en la app: clave en [CloudKeyStore] (Keystore, nombre [CloudKeyStore.JEV]), preferencias y uso en
 * SharedPreferences `jev_prefs`, caché por página en disco (la decide quien llama: un archivo por geometría) y llamadas
 * con tiempos CORTOS. Cualquier fallo (sin red, sin clave, error de la API, tiempo agotado) devuelve null: la app hace
 * exactamente lo de siempre. Nunca registra la clave.
 */
class JevEnhancer(private val context: Context, private val keys: CloudKeyStore) {

    private val prefs = context.applicationContext.getSharedPreferences("jev_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<JevSettings> = _settings.asStateFlow()

    @Volatile private var cached: Pair<String, JevClient>? = null
    @Volatile private var cachedQuick: Pair<String, JevClient>? = null

    fun hasKey(): Boolean = keys.has(CloudKeyStore.JEV)

    fun maskedKey(): String? = keys.get(CloudKeyStore.JEV)?.let { CloudKeyStore.masked(it) }

    fun setKey(key: String) { keys.set(CloudKeyStore.JEV, key); cached = null; cachedQuick = null }

    fun removeKey() { keys.remove(CloudKeyStore.JEV); cached = null; cachedQuick = null; update { it.copy(enabled = false, consent = false) } }

    @Synchronized
    fun update(f: (JevSettings) -> JevSettings) {
        val n = f(_settings.value)
        prefs.edit().putBoolean("enabled", n.enabled).putBoolean("photos", n.photos).putBoolean("tables", n.tables).putBoolean("ocr", n.ocr)
            .putBoolean("capture", n.capture).putBoolean("inks", n.inks).putBoolean("consent", n.consent)
            .putLong("pages", n.pages).putLong("requests", n.requests).putLong("tokens", n.inputTokens).putString("lastError", n.lastError).apply()
        _settings.value = n
    }

    private fun load() = JevSettings(
        prefs.getBoolean("enabled", false), prefs.getBoolean("photos", true), prefs.getBoolean("tables", true), prefs.getBoolean("ocr", false),
        prefs.getBoolean("capture", true), prefs.getBoolean("inks", true),
        prefs.getLong("pages", 0), prefs.getLong("requests", 0), prefs.getLong("tokens", 0), prefs.getString("lastError", null),
        prefs.getBoolean("consent", false),
    )

    /** ¿Hay que preguntar algo a Jev? (interruptor + clave + red). */
    fun active(): Boolean = _settings.value.on && hasKey() && CloudNetwork.isAvailable(context, unmeteredOnly = false)

    private fun client(): JevClient? {
        val key = keys.get(CloudKeyStore.JEV) ?: return null
        cached?.let { if (it.first == key) return it.second }
        val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        // dentro del render: pocos reintentos y esperas cortas (si Jev no responde, la página sale como siempre)
        return JevClient(key, OkHttpJevTransport(http), maxTries = 2, baseDelayMs = 600).also { cached = key to it }
    }

    /** Cliente de la captura: la página espera por él antes de procesarse, así que un solo intento y 8 s como mucho. */
    private fun quickClient(): JevClient? {
        val key = keys.get(CloudKeyStore.JEV) ?: return null
        cachedQuick?.let { if (it.first == key) return it.second }
        val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).writeTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
        return JevClient(key, OkHttpJevTransport(http), maxTries = 1).also { cachedQuick = key to it }
    }

    /** ¿Se pregunta sobre la foto al capturar? (interruptor general + subopción + clave). */
    fun captureEnabled(): Boolean = _settings.value.let { it.on && it.capture } && hasKey()

    /**
     * Tipo, carpeta, encuadre y calidad de la FOTO capturada [photo] (RGB/RGBA, sin recortar). Usa [cacheFile] si ya
     * existe; si no, pregunta y la guarda. null = sin Jev (apagado, sin clave/red, error).
     */
    fun captureHints(photo: Mat, cacheFile: File): CaptureHints? {
        if (!captureEnabled()) return null
        CaptureHints.read(cacheFile)?.let { return it }
        if (!active()) return null
        val cli = quickClient() ?: return null
        return try {
            val h = JevCapture.analyze(photo, cli)
            runCatching { CaptureHints.write(cacheFile, h) }
            record(h.inputTokens, 1, page = false, error = null)
            h
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Jev no disponible para la captura: ${e.javaClass.simpleName}")
            record(0, 0, page = false, error = (e as? CloudException)?.message ?: "Sin conexión con Jev")
            null
        }
    }

    /** Zona gris de los duplicados: probabilidad de que [a] y [b] sean la misma hoja, o null sin Jev. */
    fun samePage(a: Mat, b: Mat): Double? {
        if (!_settings.value.on || !active()) return null
        val cli = client() ?: return null
        return try {
            val (p, tok) = JevDuplicates.samePage(a, b, cli)
            record(tok, 1, page = false, error = null)
            p
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Jev no disponible para los duplicados: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Fotos y tablas de la página rectificada [geo] (RGB/RGBA). Usa [cacheFile] si ya tiene lo pedido; si no, pregunta
     * y la guarda. null = sin Jev (apagado, sin clave/red, error).
     */
    fun pageHints(geo: Mat, cacheFile: File, photos: Boolean, tables: Boolean, inks: Boolean = false): JevPageResult? {
        val s = _settings.value
        val wantP = photos && s.photos; val wantT = tables && s.tables; val wantI = inks && s.inks
        if (!s.on || !(wantP || wantT || wantI)) return null
        JevPageResult.read(cacheFile)?.takeIf { it.covers(wantP, wantT, wantI) }?.let { return it }
        if (!active()) return null
        val cli = client() ?: return null
        return try {
            val r = JevPage.analyze(geo, cli, wantP, wantT, wantI)
            runCatching { JevPageResult.write(cacheFile, r) }
            record(r.inputTokens, 1, page = true, error = null)
            r
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Jev no disponible para la página: ${e.javaClass.simpleName}")
            record(0, 0, page = false, error = (e as? CloudException)?.message ?: "Sin conexión con Jev")
            null
        }
    }

    /** OCR dudoso verificado con Jev sobre [image] (la página que leyó el OCR). Si no aplica o falla, [res] tal cual. */
    fun fixOcr(res: OcrResult, image: Mat, suggest: (String) -> List<String>): OcrResult {
        val s = _settings.value
        if (!s.on || !s.ocr || (res.doubtful.isEmpty() && res.blocks.none { b -> b.lines.any { l -> l.words.any { it.isDoubtful } } })) return res
        if (!active()) return res
        val cli = client() ?: return res
        return try {
            val (out, rep) = JevOcr(suggest).apply(res, image, cli)
            record(rep.inputTokens, rep.requests, page = false, error = null)
            out
        } catch (e: Exception) {
            Log.w(TAG, "Jev no disponible para el OCR: ${e.javaClass.simpleName}")
            res
        }
    }

    /** Comprueba la clave con una pregunta de texto (≈ 30 tokens). Lanza [CloudException] si falla. */
    fun testKey() {
        val cli = client() ?: throw CloudException.Auth(401, "sin clave")
        cli.ask(JevRequest(JsonPrimitive("The sky is blue."), mapOf("ok" to JevQuestion.Noul("Is the sky described as blue?"))))
        record(cli.inputTokens.coerceAtMost(100), 1, page = false, error = null)
    }

    private fun record(tokens: Long, requests: Int, page: Boolean, error: String?) = update {
        it.copy(inputTokens = it.inputTokens + tokens, requests = it.requests + requests, pages = it.pages + if (page) 1 else 0, lastError = error)
    }

    companion object {
        private const val TAG = "JevEnhancer"

        /** Tokens típicos por página (fotos + tablas + firmas a 1568 px, medido): ~6 400; OCR dudoso: ~9 000 más. */
        const val TYPICAL_PAGE_TOKENS = 6_400L
        const val TYPICAL_OCR_TOKENS = 9_000L
        /** Petición de la captura (foto a 1024 px, 9 preguntas): mediana medida 2 860 tokens (p90 3 380). */
        const val TYPICAL_CAPTURE_TOKENS = 2_900L

        fun typicalPageCost(ocr: Boolean, capture: Boolean = true): Double =
            JevApi.cost(TYPICAL_PAGE_TOKENS + (if (ocr) TYPICAL_OCR_TOKENS else 0) + if (capture) TYPICAL_CAPTURE_TOKENS else 0)
    }
}
