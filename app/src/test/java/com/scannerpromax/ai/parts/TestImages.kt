package com.scannerpromax.ai.parts

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Imágenes en los tests JVM: el classpath de compilación es android.jar (sin javax.imageio), pero la JVM de los tests
 * sí lo tiene → se usa por reflexión. Solo para tests con archivos del banco.
 */
object TestImages {
    private val imageIO by lazy { Class.forName("javax.imageio.ImageIO") }

    fun read(f: File): Rgb {
        val bi = imageIO.getMethod("read", File::class.java).invoke(null, f) ?: error("no se pudo leer $f")
        val w = bi.javaClass.getMethod("getWidth").invoke(bi) as Int
        val h = bi.javaClass.getMethod("getHeight").invoke(bi) as Int
        val px = IntArray(w * h)
        bi.javaClass.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(bi, 0, 0, w, h, px, 0, w)
        return Rgb(w, h, px)
    }

    /** Escribe [img] como PNG (o JPEG si [format] = "jpg"). */
    fun write(img: Rgb, f: File, format: String = "png") {
        f.parentFile?.mkdirs()
        val cls = Class.forName("java.awt.image.BufferedImage")
        val bi = cls.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(img.w, img.h, 1 /* TYPE_INT_RGB */)
        cls.getMethod("setRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(bi, 0, 0, img.w, img.h, img.px, 0, img.w)
        imageIO.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java).invoke(null, bi, format, f)
    }

    fun pngBytes(img: Rgb): ByteArray {
        val f = File.createTempFile("r2png", ".png")
        try { write(img, f); return f.readBytes() } finally { f.delete() }
    }

    /** Giro antihorario de [deg] grados (múltiplo de 90). */
    fun rotateCcw(img: Rgb, deg: Int): Rgb {
        val k = ((deg / 90) % 4 + 4) % 4
        var cur = img
        repeat(k) {
            val w = cur.w; val h = cur.h
            val out = IntArray(w * h)
            // antihorario 90: (x, y) → (y, w - 1 - x); nueva imagen h x w
            for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = cur.px[y * w + x]
            cur = Rgb(h, w, out)
        }
        return cur
    }

    @Suppress("unused")
    private fun unused() = ByteArrayOutputStream()
}
