package com.scannerpromax.ai.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** Uso acumulado de un proveedor (páginas, tokens y coste estimado en USD con [CloudPricing]). */
data class CloudUsage(
    val pages: Long = 0,
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    /** Último error de cuenta/red mostrado en Ajustes (null = ninguno). */
    val lastError: String? = null,
) {
    /** Suma una respuesta (facturada aunque luego no se pudiera usar). */
    fun plus(provider: CloudProviderId, u: CloudUsageTokens, at: Instant, page: Boolean): CloudUsage = copy(
        pages = pages + if (page) 1 else 0,
        requests = requests + 1,
        inputTokens = inputTokens + u.inputTokens,
        cachedInputTokens = cachedInputTokens + u.cachedInputTokens,
        outputTokens = outputTokens + u.outputTokens,
        costUsd = costUsd + CloudPricing.cost(provider, u, at),
    )
}

/** Contadores de uso por proveedor (SharedPreferences `cloud_usage`). */
class CloudUsageStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_usage", Context.MODE_PRIVATE)
    private val _usage = MutableStateFlow(CloudProviderId.entries.associateWith { load(it) })
    val usage: StateFlow<Map<CloudProviderId, CloudUsage>> = _usage.asStateFlow()

    fun get(id: CloudProviderId): CloudUsage = _usage.value[id] ?: CloudUsage()

    @Synchronized
    fun record(id: CloudProviderId, tokens: CloudUsageTokens, page: Boolean, at: Instant = Instant.now()) =
        save(id, get(id).plus(id, tokens, at, page).copy(lastError = null))

    /** Suma una respuesta con su coste ya calculado (modelos con otra tarifa que la del proveedor, p. ej. Sonnet). */
    @Synchronized
    fun recordCost(id: CloudProviderId, tokens: CloudUsageTokens, costUsd: Double, page: Boolean) {
        val u = get(id)
        save(
            id,
            u.copy(
                pages = u.pages + if (page) 1 else 0, requests = u.requests + 1,
                inputTokens = u.inputTokens + tokens.inputTokens, cachedInputTokens = u.cachedInputTokens + tokens.cachedInputTokens,
                outputTokens = u.outputTokens + tokens.outputTokens, costUsd = u.costUsd + costUsd, lastError = null,
            ),
        )
    }

    @Synchronized
    fun setError(id: CloudProviderId, message: String?) = save(id, get(id).copy(lastError = message))

    @Synchronized
    fun reset(id: CloudProviderId) = save(id, CloudUsage(lastError = get(id).lastError))

    private fun save(id: CloudProviderId, u: CloudUsage) {
        val p = id.name
        prefs.edit()
            .putLong("$p.pages", u.pages).putLong("$p.requests", u.requests)
            .putLong("$p.in", u.inputTokens).putLong("$p.cached", u.cachedInputTokens).putLong("$p.out", u.outputTokens)
            .putLong("$p.cost", java.lang.Double.doubleToRawLongBits(u.costUsd))
            .apply { if (u.lastError == null) remove("$p.error") else putString("$p.error", u.lastError) }
            .apply()
        _usage.value = _usage.value + (id to u)
    }

    private fun load(id: CloudProviderId): CloudUsage {
        val p = id.name
        return CloudUsage(
            pages = prefs.getLong("$p.pages", 0), requests = prefs.getLong("$p.requests", 0),
            inputTokens = prefs.getLong("$p.in", 0), cachedInputTokens = prefs.getLong("$p.cached", 0),
            outputTokens = prefs.getLong("$p.out", 0),
            costUsd = java.lang.Double.longBitsToDouble(prefs.getLong("$p.cost", 0)),
            lastError = prefs.getString("$p.error", null),
        )
    }
}
