package com.scannerpromax.ocr.indra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Partes puras del puerto de Indra (sin modelos ni OpenCV). */
class IndraUnitTest {

    @Test fun vocabDecodificaYEspacioDeComandos() {
        val v = IndraVocab.fromJson("""{"blank": 0, "chars": [" ", "a", "b", "\\alpha", "x"]}""")
        assertEquals(1, v.spaceId)
        assertEquals("ab a", v.decode(intArrayOf(2, 3, 1, 2)))
        // "\alpha" + "x" no debe leerse "\alphax"
        assertEquals("\\alpha x", v.decode(intArrayOf(4, 5)))
        assertEquals("", v.decode(intArrayOf(0, 99)))
    }

    @Test fun ordenPorParesYAgrupacion() {
        // a[i][j] > 0: i antes que j. Orden real 2, 0, 1.
        val pos = intArrayOf(1, 2, 0)
        val a = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 0.0 else if (pos[i] < pos[j]) 4.0 else -4.0 } }
        assertEquals(listOf(2, 0, 1), IndraOrder.decodeOrder(a).toList())
        // Dos grupos {0,1} y {2,3}.
        val same = Array(4) { i -> FloatArray(4) { j -> if ((i < 2) == (j < 2)) 3f else -3f } }
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), IndraOrder.cluster(same).sortedBy { it.first() })
    }

    @Test fun xyCutDosColumnas() {
        val b = listOf(
            floatArrayOf(10f, 10f, 90f, 20f), floatArrayOf(110f, 10f, 190f, 20f),
            floatArrayOf(10f, 30f, 90f, 40f), floatArrayOf(110f, 30f, 190f, 40f),
        )
        assertEquals(listOf(0, 2, 1, 3), IndraOrder.orderXyCut(b))
        assertEquals(listOf(0, 1, 2, 3), IndraOrder.orderTopDown(b))
    }

    @Test fun rasgosDeLinea() {
        val f = IndraOrder.lineFeatures(listOf(floatArrayOf(0f, 0f, 100f, 10f)), 200f, 100f, listOf("12) Hola."))
        assertEquals(16, f.size)
        assertEquals(0.5f, f[2], 1e-6f)
        assertEquals(1f, f[12])  // empieza por enumerador
        assertEquals(1f, f[14])  // termina en punto
        assertEquals(0f, f[15])  // "1" no es mayúscula
    }

    @Test fun lectorNpyYModeloDeLenguaje() {
        // LM de orden 2 con 11 bits: unigramas y bigramas de 2 tokens (0 y 1).
        val tmp = File.createTempFile("indra_lm", ".npz")
        try {
            ZipOutputStream(tmp.outputStream()).use { z ->
                fun put(name: String, descr: String, shape: String, bytes: ByteArray) {
                    z.putNextEntry(ZipEntry("$name.npy")); z.write(npy(descr, shape, bytes)); z.closeEntry()
                }
                fun longs(vararg v: Long) = ByteBuffer.allocate(8 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putLong(it) } }.array()
                fun floats(vararg v: Float) = ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
                put("order", "<i8", "()", longs(2)); put("vocab_size", "<i8", "()", longs(2))
                put("k0", "<u8", "(2,)", longs(1, 2)); put("v0", "<f4", "(2,)", floats(-1f, -2f))
                put("hk0", "<u8", "(1,)", longs(0)); put("hb0", "<f4", "(1,)", floats(0f))
                // historia token 0 (1) → token 1 (2): clave (1 << 11) | 2
                put("k1", "<u8", "(1,)", longs((1L shl 11) or 2)); put("v1", "<f4", "(1,)", floats(-0.1f))
                put("hk1", "<u8", "(2,)", longs(0, 1)); put("hb1", "<f4", "(2,)", floats(-0.5f, -0.7f))
            }
            val lm = IndraLm.load(tmp)
            assertEquals(2, lm.order)
            assertEquals(-0.1, lm.logpKey((1L shl 11) or 2), 1e-6)            // bigrama visto
            assertEquals(-0.7 + -1.0, lm.logpKey((1L shl 11) or 1), 1e-6)     // retroceso al unigrama
            assertEquals(-0.5 + -2.0, lm.logpKey(2), 1e-6)                    // historia = inicio de línea
            // Búsqueda en haz: un solo candidato claro por paso.
            val ln = { p: Double -> kotlin.math.ln(p).toFloat() }
            val blank = floatArrayOf(ln(0.01), ln(0.99), ln(0.01))
            val topv = floatArrayOf(ln(0.98), ln(0.01), ln(0.99), ln(0.005), ln(0.98), ln(0.01))
            val topi = longArrayOf(1, 0, 0, 2, 2, 0)
            val (ids, steps) = lm.beamSearch(blank, topv, topi, 2, 3, 0.5, 1.5)
            assertEquals(listOf(1, 2), ids.toList())
            assertEquals(2, steps.size)
            assertEquals(0, steps[0])
        } finally {
            tmp.delete()
        }
    }

    private fun npy(descr: String, shape: String, data: ByteArray): ByteArray {
        var header = "{'descr': '$descr', 'fortran_order': False, 'shape': $shape, }"
        while ((10 + header.length + 1) % 64 != 0) header += " "
        header += "\n"
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x93.toByte()) + "NUMPY".toByteArray() + byteArrayOf(1, 0))
        out.write(byteArrayOf((header.length and 0xFF).toByte(), (header.length shr 8).toByte()))
        out.write(header.toByteArray(Charsets.ISO_8859_1)); out.write(data)
        return out.toByteArray()
    }

    @Test fun cajasDeVueltaALaImagenOriginal() {
        // Imagen original 100x200; Indra la giró en sentido horario (rotation 90): página girada 200x100.
        val l = IndraOcr.Line(floatArrayOf(175f, 5f, 185f, 15f), "x", 0.9f, listOf(IndraOcr.Word("x", floatArrayOf(175f, 5f, 185f, 15f))))
        val page = IndraOcr.Page(listOf(l), listOf(IndraOcr.Block("Text", l.box, listOf(0))), 90, 0.0, 200, 100, emptyMap())
        val r = IndraResult.toOcrResult(page, 100, 200)
        val b = r.blocks.single().lines.single().box
        // El punto (10, 20) del original está en (180, 10) de la girada: su caja debe contenerlo.
        assertEquals(5f, b.left, 1e-4f); assertEquals(15f, b.top, 1e-4f); assertEquals(15f, b.right, 1e-4f); assertEquals(25f, b.bottom, 1e-4f)
        assertEquals(-90f, r.blocks.single().lines.single().angle, 1e-4f)
        assertEquals("x", r.text)
        // Sin giro y con escala: cajas multiplicadas y recortadas a la imagen.
        val p0 = IndraOcr.Page(listOf(l), listOf(IndraOcr.Block("Text", l.box, listOf(0))), 0, 1.5, 200, 100, emptyMap())
        val r0 = IndraResult.toOcrResult(p0, 300, 150, 1.5f)
        val b0 = r0.blocks.single().lines.single().box
        assertEquals(262.5f, b0.left, 1e-3f); assertEquals(1.5f, r0.blocks.single().lines.single().angle, 1e-4f)
        assertTrue(b0.right <= 300f)
    }

    @Test fun lotesPorAncho() {
        val b = IndraImage.widthBatches(intArrayOf(100, 30, 500, 110, 40))
        assertEquals(listOf(listOf(1, 4), listOf(0, 3), listOf(2)), b)
    }
}
