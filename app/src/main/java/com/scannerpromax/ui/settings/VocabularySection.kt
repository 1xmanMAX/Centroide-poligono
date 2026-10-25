package com.scannerpromax.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.scannerpromax.data.AppSettings
import com.scannerpromax.ocr.lexicon.UserVocabulary
import com.scannerpromax.ui.components.GroupRow
import com.scannerpromax.ui.components.InsetGroup
import com.scannerpromax.ui.components.ToggleRow
import com.scannerpromax.ui.theme.ui

/**
 * Ajustes › Reconocimiento › "Corrección del texto": interruptor del diccionario español y "Mi vocabulario" (lista
 * editable de términos propios con prioridad en la corrección y en el modelo de lenguaje de Indra).
 */
@Composable
internal fun VocabularySection(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    val ui = MaterialTheme.ui
    var draft by rememberSaveable { mutableStateOf("") }
    fun add() {
        val new = UserVocabulary.parse(draft)
        if (new.isEmpty()) return
        onUpdate { s -> s.copy(userVocabulary = (s.userVocabulary + new).distinct()) }
        draft = ""
    }
    InsetGroup(
        header = "Corrección del texto",
        footer = "Solo se corrigen palabras leídas con poca seguridad cuando la corrección es clara (una letra de diferencia " +
            "o un término de Mi vocabulario muy parecido). Números y códigos no se tocan. Las líneas dudosas aparecen " +
            "subrayadas en Texto en vivo. Diccionario: hunspell es_ES (RLA-ES), licencia MPL/LGPL/GPL.",
    ) {
        item {
            ToggleRow(
                title = "Corregir con diccionario español",
                subtitle = "Tildes y letras mal leídas en palabras dudosas.",
                checked = settings.spellCheck,
                onCheckedChange = { v -> onUpdate { it.copy(spellCheck = v) } },
                icon = Icons.Rounded.Spellcheck,
            )
        }
        item {
            GroupRow(
                title = "Mi vocabulario",
                subtitle = "Nombres, cursos y términos técnicos (p. ej. sismorresistente, E030, concreto armado).",
                icon = Icons.Rounded.MenuBook,
            )
        }
        for (term in settings.userVocabulary) item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(term, style = MaterialTheme.typography.bodyLarge, color = ui.label, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onUpdate { s -> s.copy(userVocabulary = s.userVocabulary - term) } }
                        .semantics { contentDescription = "Quitar $term" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = ui.secondaryLabel, modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = ui.label)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(ui.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(
                            Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(ui.fill).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (draft.isEmpty()) Text("Añadir término (o varios separados por comas)", style = textStyle, color = ui.secondaryLabel, maxLines = 1)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = draft.isNotBlank(), role = Role.Button) { add() }
                        .semantics { contentDescription = "Añadir al vocabulario" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = if (draft.isNotBlank()) ui.accent else ui.tertiaryLabel, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
