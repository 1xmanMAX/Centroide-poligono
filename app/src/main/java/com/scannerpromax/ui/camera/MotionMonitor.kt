package com.scannerpromax.ui.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detecta si el teléfono se está moviendo (giroscopio; si no hay, acelerómetro) para no disparar fotos movidas.
 * No necesita permisos. [start]/[stop] siguen el ciclo de vida de la pantalla de cámara (sin sensores en
 * segundo plano).
 */
internal class MotionMonitor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = if (gyro == null) sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null

    /** Último instante (elapsedRealtime) en que se detectó movimiento. */
    @Volatile private var lastMotionAt = 0L
    @Volatile private var running = false
    private var gravity = 0f

    /** ¿Hay algún sensor útil? Sin sensores se considera siempre estable. */
    val available: Boolean get() = gyro != null || accel != null

    fun start() {
        val m = sm ?: return
        if (running) return
        val s = gyro ?: accel ?: return
        running = runCatching { m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) }.getOrDefault(false)
        lastMotionAt = 0L
        gravity = 0f
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { sm?.unregisterListener(this) }
    }

    /** Milisegundos que lleva quieto (muy grande si no hay sensores o no se ha movido). */
    fun stillForMs(): Long = if (!running) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastMotionAt

    /**
     * Espera a que el teléfono lleve [stillMs] quieto, como mucho [timeoutMs] (nunca bloquea el disparo).
     * Devuelve true si se estabilizó.
     */
    suspend fun awaitStill(stillMs: Long = 300L, timeoutMs: Long = 1_500L): Boolean {
        if (!running) return true
        val start = SystemClock.elapsedRealtime()
        while (stillForMs() < stillMs) {
            if (SystemClock.elapsedRealtime() - start >= timeoutMs) return false
            delay(30)
        }
        return true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val moving = when (event.sensor.type) {
            // Velocidad angular (rad/s): el pulso de una mano firme ronda 0.02-0.06; > 0.12 ya emborrona a 1/30 s.
            Sensor.TYPE_GYROSCOPE -> sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) > GYRO_THRESHOLD
            else -> {
                // Acelerómetro: desviación del módulo respecto a su media lenta (gravedad).
                val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                gravity = if (gravity == 0f) m else gravity * 0.9f + m * 0.1f
                abs(m - gravity) > ACCEL_THRESHOLD
            }
        }
        if (moving) lastMotionAt = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val GYRO_THRESHOLD = 0.12f
        const val ACCEL_THRESHOLD = 0.35f
    }
}
