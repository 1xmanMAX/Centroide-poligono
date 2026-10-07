package com.scannerpromax.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scannerpromax.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Si la app se cerró de forma inesperada la última vez, muestra el informe para copiarlo o compartirlo. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { CrashReporter.pendingReport(context) }
    }
    val text = report ?: return
    fun dismiss() {
        CrashReporter.clear(context)
        report = null
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("La app se cerró inesperadamente") },
        text = {
            SelectionContainer {
                Text(
                    "Copia este informe y envíalo para poder corregir el problema:\n\n$text",
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Informe de error - ESCÁNER PRO MAX")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Compartir informe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                dismiss()
            }) { Text("Compartir") }
        },
        dismissButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Informe de error", text))
                Toast.makeText(context, "Informe copiado", Toast.LENGTH_SHORT).show()
                dismiss()
            }) { Text("Copiar") }
        },
    )
}
