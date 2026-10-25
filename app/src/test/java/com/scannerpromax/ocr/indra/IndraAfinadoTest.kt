package com.scannerpromax.ocr.indra

import com.scannerpromax.domain.OcrBlock
import com.scannerpromax.domain.OcrLine
import com.scannerpromax.domain.OcrRect
import com.scannerpromax.domain.OcrResult
import com.scannerpromax.domain.OcrWord
import com.scannerpromax.ocr.LiveText
import com.scannerpromax.ocr.lexicon.Hunspell
import com.scannerpromax.ocr.lexicon.UserVocabulary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Afinado de Indra sin modelos: página preparada ([IndraPrep]), líneas dudosas ([IndraDoubt], [IndraResult],
 * [LiveText]), léxico hunspell es_ES ([Hunspell]), "Mi vocabulario" ([UserVocabulary]) y corrector ([IndraCorrector]).
 */
class IndraAfinadoTest {

    companion object {
        private val openCv: Boolean by lazy { runCatching { nu.pattern.OpenCV.loadLocally(); Mat(1, 1, 0).release(); true }.getOrDefault(false) }

        /** Léxico real de los assets (los tests JVM corren con el módulo app como directorio de trabajo). */
        val lexicon: Hunspell? by lazy {
            val a = listOf(File("src/main/assets/lexicon"), File("app/src/main/assets/lexicon")).firstOrNull { File(it, "es_ES.dic.gz").isFile }
                ?: return@lazy null
            File(a, "es_ES.aff.gz").inputStream().use { aff -> File(a, "es_ES.dic.gz").inputStream().use { dic -> Hunspell.load(aff, dic) } }
        }
    }

    // ------------------------------------------------------------------ preparación

    @Test fun preparacionBlanqueaSombraYConservaTinta() {
        assumeTrue("OpenCV de escritorio no disponible", openCv)
        // Página 800×600 con una sombra en degradado (papel de 230 a 120) y texto negro encima.
        val m = Mat(600, 800, 0)
        for (y in 0 until 600) for (x in 0 until 800) m.put(y, x, 230.0 - 110.0 * x / 800)
        for (i in 0 until 5) Imgproc.putText(m, "Texto de prueba $i", Point(30.0, 80.0 + 100 * i), Imgproc.FONT_HERSHEY_SIMPLEX, 1.4, Scalar(25.0), 3)
        val g = Gray.fromMat(m); m.release()
        val p = IndraPrep.prepare(g)
        assertEquals(g.w, p.w); assertEquals(g.h, p.h)
        // Papel a la izquierda y a la derecha (en sombra): casi blanco tras normalizar.
        assertTrue("papel claro ${p[20, 40]}", p[20, 40] > 235)
        assertTrue("papel en sombra ${p[780, 40]} (antes ${g[780, 40]})", p[780, 40] > 235 && g[780, 40] < 130)
        // La tinta sigue oscura (buscar el mínimo de una fila de texto).
        var minInk = 255
        for (x in 30 until 600) minInk = minOf(minInk, p[x, 75])
        assertTrue("tinta $minInk", minInk < 80)
    }

    // ------------------------------------------------------------------ dudosas

    @Test fun dudosasSinBasuraEvidente() {
        assertTrue(IndraDoubt.keep("14,0", 0.42f, 1.2f, 2f))
        assertTrue(IndraDoubt.keep("Ing. Sismorresistente", 0.45f, 1.0f, 8f))
        assertTrue(IndraDoubt.keep("Erick Horev", 0.35f, 1.0f, 5f))
        assertFalse("raya", IndraDoubt.keep("-", 0.45f, 1f, 1f))
        assertFalse("casilla", IndraDoubt.keep("I", 0.3f, 1f, 1f))
        assertFalse("puntos", IndraDoubt.keep("...", 0.45f, 1f, 3f))
        assertFalse("letras sueltas", IndraDoubt.keep("gi", 0.44f, 1f, 1f))
        assertFalse("bajo el suelo", IndraDoubt.keep("Texto largo", 0.2f, 1f, 6f))
        assertFalse("renglones fundidos", IndraDoubt.keep("la dei son de", 0.45f, 3.5f, 6f))
        assertFalse("muy baja y corta", IndraDoubt.keep("an t", 0.35f, 1f, 3f))
        assertTrue("dígitos a baja confianza", IndraDoubt.keep("27 50", 0.35f, 1f, 3f))
    }

    private fun line(text: String, conf: Float, y: Float, doubtful: Boolean = false, wconf: Float = conf): IndraOcr.Line {
        var x = 10f
        val words = text.split(' ').filter { it.isNotEmpty() }.map { w ->
            val b = floatArrayOf(x, y, x + 12f * w.length, y + 20f); x += 12f * w.length + 10f
            IndraOcr.Word(w, b, wconf, FloatArray(w.length) { wconf })
        }
        return IndraOcr.Line(floatArrayOf(10f, y, x, y + 20f), text, conf, words, doubtful)
    }

    @Test fun dudosasFueraDelTextoYDeLosBloques() {
        val lines = listOf(line("EXAMEN PARCIAL", 0.99f, 10f), line("14,0", 0.42f, 60f, doubtful = true), line("Observaciones", 0.97f, 110f))
        val page = IndraOcr.Page(lines, listOf(IndraOcr.Block("Text", floatArrayOf(0f, 0f, 300f, 140f), listOf(0, 1, 2))), 0, 0.0, 400, 300, emptyMap())
        val r = IndraResult.toOcrResult(page, 800, 600, 2f)
        assertEquals("EXAMEN PARCIAL\nObservaciones", r.text)
        assertEquals(listOf("EXAMEN PARCIAL", "Observaciones"), r.blocks.single().lines.map { it.text })
        val d = r.doubtful.single()
        assertEquals("14,0", d.text)
        assertTrue(d.isDoubtful)
        assertEquals(120f, d.box.top, 0.01f) // escalado ×2
        assertFalse(r.blocks.single().lines.first().isDoubtful)
        // Escalas distintas en x e y (Indra leyó la versión de solo geometría a otro tamaño).
        val r2 = IndraResult.toOcrResult(page, 800, 900, 2f, 3f)
        assertEquals(180f, r2.doubtful.single().box.top, 0.01f)
        assertEquals(20f, r2.doubtful.single().box.left, 0.01f)
    }

    @Test fun textoEnVivoMarcaLasDudosasEnOrden() {
        fun l(t: String, top: Float, c: Float = 0.9f) = OcrLine(t, OcrRect(0f, top, 100f, top + 10f), listOf(OcrWord(t, OcrRect(0f, top, 100f, top + 10f), c)), confidence = c)
        val a = l("Arriba", 0f); val b = l("Abajo", 100f)
        val res = OcrResult(
            "Arriba\n\nAbajo", 200, 200,
            listOf(OcrBlock("Arriba", a.box, listOf(a)), OcrBlock("Abajo", b.box, listOf(b))),
            doubtful = listOf(l("14,0", 50f, 0.4f)),
        )
        val w = LiveText.words(res)
        assertEquals(listOf("Arriba", "14,0", "Abajo"), w.map { it.text })
        assertEquals(listOf(false, true, false), w.map { it.doubtful })
        assertEquals(1, LiveText.doubtfulBoxes(w).size)
        assertEquals("Arriba\n\n14,0\n\nAbajo", LiveText.text(w, LiveText.selectAll(w)))
        // Palabra dudosa dentro de una línea segura.
        val mixed = OcrLine("hola mundo", OcrRect(0f, 0f, 100f, 10f), listOf(OcrWord("hola", OcrRect(0f, 0f, 40f, 10f), 0.95f), OcrWord("mundo", OcrRect(50f, 0f, 100f, 10f), 0.3f)), confidence = 0.8f)
        val w2 = LiveText.words(OcrResult("hola mundo", 100, 10, listOf(OcrBlock("hola mundo", mixed.box, listOf(mixed)))))
        assertEquals(listOf(false, true), w2.map { it.doubtful })
    }

    @Test fun jsonAntiguoSinDudosas() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"text":"a","imageWidth":10,"imageHeight":10,"blocks":[{"text":"a","box":{"left":0,"top":0,"right":1,"bottom":1},"lines":[{"text":"a","box":{"left":0,"top":0,"right":1,"bottom":1}}]}]}"""
        val r = json.decodeFromString(OcrResult.serializer(), old)
        assertTrue(r.doubtful.isEmpty())
        assertFalse(r.blocks[0].lines[0].isDoubtful) // confianza desconocida (-1): no es dudosa
        val back = json.decodeFromString(OcrResult.serializer(), json.encodeToString(OcrResult.serializer(), r.copy(doubtful = listOf(r.blocks[0].lines[0].copy(confidence = 0.4f)))))
        assertEquals(0.4f, back.doubtful.single().confidence, 1e-6f)
    }

    // ------------------------------------------------------------------ léxico

    @Test fun hunspellMinimoConAfijos() {
        val aff = """
            SET UTF-8
            FLAG UTF-8
            PFX r Y 1
            PFX r 0 re .
            SFX S Y 2
            SFX S 0 s [aeiou]
            SFX S 0 es [^aeiou]
            SFX A Y 1
            SFX A r ción/S ar
        """.trimIndent()
        val dic = "4\ncasa/S\nhacer/r\nevaluar/A\nMadrid\n"
        val h = Hunspell.load(aff.byteInputStream(), dic.byteInputStream())
        assertTrue(h.contains("casa")); assertTrue(h.contains("casas")); assertFalse(h.contains("casaes"))
        assertTrue(h.contains("rehacer")); assertTrue(h.contains("evaluación")); assertTrue("continuación /S", h.contains("evaluaciónes"))
        assertTrue(h.contains("Madrid")); assertTrue(h.contains("MADRID")); assertTrue(h.contains("Casas"))
        assertFalse(h.contains("madrid".replace("m", "x")))
    }

    @Test fun lexicoEspanolReal() {
        val h = lexicon
        assumeTrue("léxico es_ES no encontrado", h != null)
        h!!
        assertTrue(h.size > 50_000)
        for (w in listOf("evaluación", "evaluaciones", "sísmico", "concreto", "armado", "edificación", "desplazamiento", "características", "Académica", "estribos"))
            assertTrue(w, h.contains(w))
        for (w in listOf("evaluacion", "analisis", "deplazamiento", "luerza", "xyzzy")) assertFalse(w, h.contains(w))
    }

    // ------------------------------------------------------------------ corrector

    private fun page(vararg lines: IndraOcr.Line) = IndraOcr.Page(lines.toList(), emptyList(), 0, 0.0, 1000, 1000, emptyMap())

    private val spanishContext = line("el análisis de la estructura con concreto armado para la edificación", 0.99f, 0f)

    @Test fun corrigeTildesEnPaginaEspanola() {
        val lx = lexicon; assumeTrue(lx != null)
        val c = IndraCorrector(lx, UserVocabulary(emptyList()))
        val r = c.correct(page(spanishContext, line("la evaluacion del analisis", 0.8f, 30f, wconf = 0.7f)))
        assertTrue(r.spanish)
        assertEquals("la evaluación del análisis", r.page.lines[1].text)
        assertEquals(listOf("la", "evaluación", "del", "análisis"), r.page.lines[1].words.map { it.text })
        // Palabra leída con seguridad: no se toca aunque no esté en el léxico.
        val r2 = c.correct(page(spanishContext, line("la evaluacion", 0.98f, 30f, wconf = 0.97f)))
        assertEquals("la evaluacion", r2.page.lines[1].text)
    }

    @Test fun noTocaCodigosNumerosNiNombres() {
        val lx = lexicon; assumeTrue(lx != null)
        val c = IndraCorrector(lx, UserVocabulary(emptyList()))
        val r = c.correct(page(spanishContext, line("CÓDIGO U20191A543 total 152.40 Erick Hotev", 0.7f, 30f, wconf = 0.5f)))
        assertEquals("CÓDIGO U20191A543 total 152.40 Erick Hotev", r.page.lines[1].text)
    }

    @Test fun paginaInglesaSinLexicoEspanol() {
        val lx = lexicon; assumeTrue(lx != null)
        val c = IndraCorrector(lx, UserVocabulary(emptyList()))
        val en = line("the quick brown fox jumps over the lazy dog and the cat", 0.99f, 0f)
        val r = c.correct(page(en, line("the evaluacion form", 0.7f, 30f, wconf = 0.5f)))
        assertFalse(r.spanish)
        assertEquals("the evaluacion form", r.page.lines[1].text)
    }

    @Test fun vocabularioDelUsuarioTienePrioridad() {
        val v = UserVocabulary(listOf("sismorresistente", "E030", "concreto armado", "sísmica", " ", "sísmica"))
        assertEquals(listOf("sismorresistente", "E030", "concreto armado", "sísmica"), v.terms)
        assertTrue(v.containsWord("Armado")); assertTrue("sin tildes", v.containsWord("sismica"))
        val c = IndraCorrector(lexicon, v)
        val r = c.correct(page(spanishContext, line("Ing. Sismorresistenta norma E03O sismica", 0.7f, 30f, wconf = 0.6f)))
        assertEquals("Ing. Sismorresistente norma E030 sísmica", r.page.lines[1].text)
        assertTrue(r.changes.all { it.source == "vocabulario" })
        // Lejos del término (más de una edición en 6 letras): no se fuerza ("parado" no es "armado").
        val r2 = c.correct(page(spanishContext, line("parado hermano", 0.7f, 30f, wconf = 0.5f)))
        assertEquals("parado hermano", r2.page.lines[1].text)
        // Sin léxico (diccionario desactivado) el vocabulario sigue funcionando.
        val r3 = IndraCorrector(null, v).correct(page(line("sismorresistenta", 0.7f, 0f, wconf = 0.5f)))
        assertEquals("sismorresistente", r3.page.lines[0].text)
    }

    @Test fun sesgoDelLmPorVocabularioYLexico() {
        val c = IndraCorrector(lexicon, UserVocabulary(listOf("sismorresistente")))
        val b = c.bias(lexWeight = 1.0, vocabWeight = 0.5, vocabEnd = 1.0)
        assertTrue(b.active)
        assertEquals(0.5 * 4, b.partial("sism"), 1e-9)
        assertEquals(0.0, b.partial("sixm"), 1e-9)
        assertEquals(0.5 * 16 + 1.0, b.complete("sismorresistente"), 1e-9)
        if (lexicon != null) assertEquals(1.0, b.complete("concreto"), 1e-9)
        assertEquals(0.0, b.complete("xyzzy"), 1e-9)
        assertTrue(b.maxStep >= 2.5)
        assertFalse(IndraCorrector(null, UserVocabulary(emptyList())).bias(lexWeight = 1.0).active)
    }

    @Test fun distanciaPonderadaPorConfianza() {
        // Cambiar un carácter dudoso cuesta menos que uno seguro; una tilde casi nada.
        val dudoso = IndraCorrector.weightedDistance("luerza", "fuerza", floatArrayOf(0.2f, 1f, 1f, 1f, 1f, 1f))
        val seguro = IndraCorrector.weightedDistance("luerza", "fuerza", floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f))
        assertTrue(dudoso < seguro)
        assertEquals(0.1, IndraCorrector.weightedDistance("evaluacion", "evaluación", null), 1e-9)
        assertTrue(IndraCorrector.edits1("casa").contains("caso"))
        assertNull(IndraCorrector(lexicon, UserVocabulary(emptyList())).fixWord(IndraOcr.Word("12,50", floatArrayOf(0f, 0f, 1f, 1f), 0.3f), true))
    }
}
