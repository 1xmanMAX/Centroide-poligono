package com.scannerpromax.ui.docgen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.scannerpromax.ai.docgen.DocGenModel
import com.scannerpromax.di.AppContainer
import com.scannerpromax.ui.components.CheckRow
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.SeparatorInsetPlain

/**
 * Ajustes › IA en la nube › "Reconstrucción de documentos": modelo de Claude para la ALTA CALIDAD (Sonnet 5.5 por
 * defecto, o Haiku 5.5 más barato) y para la versión RÁPIDA (Haiku o DeepSeek), con sus precios por millón de tokens.
 */
@Composable
fun DocGenSettingsGroup(container: AppContainer) {
    val prefs = container.docGenPrefs
    var quality by remember { mutableStateOf(prefs.qualityModel) }
    var fast by remember { mutableStateOf(prefs.fastModel) }
    InsetGroup(
        header = "Reconstrucción Word / PDF · Alta calidad",
        separatorInset = SeparatorInsetPlain,
        footer = "Modelo de Claude que reconstruye el documento con herramientas en «Alta calidad» (Exportar › Reconstruir con IA). " +
            "Usa tu clave de Anthropic.",
    ) {
        listOf(DocGenModel.CLAUDE_SONNET, DocGenModel.CLAUDE_HAIKU).forEach { m ->
            item {
                CheckRow(
                    m.label + if (m == DocGenModel.CLAUDE_SONNET) " (recomendado)" else " (más barato)",
                    selected = quality == m,
                    onClick = { quality = m; prefs.qualityModel = m },
                    subtitle = priceLabel(m),
                )
            }
        }
    }
    InsetGroup(
        header = "Reconstrucción Word / PDF · Rápida",
        separatorInset = SeparatorInsetPlain,
        footer = "Una lectura por página con la respuesta ya estructurada: la opción más barata.",
    ) {
        listOf(DocGenModel.CLAUDE_HAIKU, DocGenModel.DEEPSEEK_FLASH).forEach { m ->
            item { CheckRow(m.label, selected = fast == m, onClick = { fast = m; prefs.fastModel = m }, subtitle = priceLabel(m)) }
        }
    }
}
