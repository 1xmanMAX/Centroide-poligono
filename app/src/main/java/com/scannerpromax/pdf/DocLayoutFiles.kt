package com.scannerpromax.pdf

import com.scannerpromax.domain.DocLayout
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

/**
 * Lectura del [DocLayout] guardado junto al procesado de una página (`<procesado>.layout.json`, lo escribe la tubería
 * de IA). Lógica pura (JVM).
 */
object DocLayoutFiles {
    const val SUFFIX = ".layout.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun fileFor(processed: File): File = File(processed.parentFile, processed.name + SUFFIX)

    fun exists(processed: File): Boolean = fileFor(processed).let { it.isFile && it.length() > 0L }

    fun parse(text: String): DocLayout? = try {
        json.decodeFromString(DocLayout.serializer(), text).takeIf { it.imageWidth > 0 && it.imageHeight > 0 }
    } catch (_: Exception) {
        null
    }

    /**
     * Maquetación de [processed] o null si no hay, no se puede leer o es de otra versión de la página (proporción
     * distinta a la imagen [imageW] x [imageH], si se da).
     */
    fun read(processed: File, imageW: Int = 0, imageH: Int = 0): DocLayout? {
        val f = fileFor(processed)
        if (!f.isFile) return null
        val layout = try { parse(f.readText()) } catch (_: Exception) { null } ?: return null
        if (imageW > 0 && imageH > 0 && !sameAspect(layout, imageW, imageH)) return null
        return layout
    }

    fun sameAspect(layout: DocLayout, w: Int, h: Int): Boolean {
        val a = w.toFloat() / h
        val b = layout.imageWidth.toFloat() / layout.imageHeight
        return abs(a - b) / a < 0.03f
    }

    /** ¿Hay algo que reconstruir? (bloques con texto, tablas o imágenes) */
    fun hasContent(layout: DocLayout): Boolean = layout.blocks.isNotEmpty()
}
