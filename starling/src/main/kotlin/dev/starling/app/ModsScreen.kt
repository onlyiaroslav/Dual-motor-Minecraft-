package dev.starling.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.starling.converter.AiConverterBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MOD_PREFS = "starling_settings"
private const val MOD_ENDPOINT_KEY = "ai_locale_endpoint"
private const val MOD_DEFAULT_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"

/**
 * Sezione "Mod": elenco, attivazione/disattivazione, importazione e
 * aggiornamento (conversione nativa) delle mod installate.
 */
@Composable
fun ModsContent(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var mods by remember { mutableStateOf<List<ModManager.ModEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ModManager.ModEntry?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        mods = withContext(Dispatchers.IO) { ModManager.list(ctx) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { ModManager.import(ctx, uri) }
                busy = false
                result
                    .onSuccess {
                        message = "Mod importata: ${it.name}"
                        messageIsError = false
                        refresh++
                    }
                    .onFailure {
                        message = it.message ?: "Importazione fallita"
                        messageIsError = true
                    }
            }
        }
    }

    fun convert(entry: ModManager.ModEntry): Result<String> = runCatching {
        val prefs = ctx.getSharedPreferences(MOD_PREFS, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(MOD_ENDPOINT_KEY, MOD_DEFAULT_ENDPOINT) ?: MOD_DEFAULT_ENDPOINT
        val converter = AiConverterBridge(endpoint, ModManager.libraryPath(ctx))
        try {
            converter.convertJar(entry.file.absolutePath, ModManager.outDir(ctx).absolutePath)
        } finally {
            runCatching { converter.close() }
        }
    }

    fun update(entry: ModManager.ModEntry) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { convert(entry) }
            busy = false
            result
                .onSuccess {
                    message = "Aggiornata ${entry.name}: $it"
                    messageIsError = false
                }
                .onFailure {
                    message = "Aggiornamento di ${entry.name} fallito: ${it.message}"
                    messageIsError = true
                }
        }
    }

    fun updateAll() {
        val targets = mods.filter { it.enabled }
        if (targets.isEmpty()) {
            message = "Nessuna mod attiva da aggiornare."
            messageIsError = true
            return
        }
        scope.launch {
            busy = true
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                targets.forEach { entry ->
                    convert(entry).onSuccess { ok++ }.onFailure { failed++ }
                }
            }
            busy = false
            message = "Aggiornamento completato: $ok ok, $failed falliti."
            messageIsError = failed > 0
        }
    }

    fun toggle(entry: ModManager.ModEntry, enabled: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { ModManager.setEnabled(ctx, entry, enabled) }
            result
                .onSuccess { refresh++ }
                .onFailure {
                    message = it.message ?: "Operazione fallita"
                    messageIsError = true
                }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Mod",
                    style = MaterialTheme.typography.headlineLarge,
                    color = DogeTextPrimary,
                )
                Text(
                    "${mods.count { it.enabled }} attive · ${mods.count { !it.enabled }} disattivate",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                )
            }
            DogePill(
                text = "${mods.size} TOTALI",
                color = DogePrimary,
                dot = false,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DogePrimaryButton(
                text = "Aggiungi",
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = {
                    picker.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                },
            )
            DogeGhostButton(
                text = "Aggiorna tutte",
                modifier = Modifier.weight(1f),
                enabled = !busy,
                onClick = { updateAll() },
            )
        }

        Spacer(Modifier.height(10.dp))

        val currentMessage = message
        if (currentMessage != null) {
            Text(
                currentMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = if (messageIsError) DogeDanger else DogeSuccess,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (mods.isEmpty()) {
            DogeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🧩", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Nessuna mod installata",
                            style = MaterialTheme.typography.titleMedium,
                            color = DogeTextPrimary,
                        )
                        Text(
                            "Usa \"Aggiungi\" per importare un file .jar, oppure copialo in " +
                                ModManager.modsDir(ctx).absolutePath,
                            style = MaterialTheme.typography.bodyMedium,
                            color = DogeTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(mods, key = { it.file.absolutePath }) { entry ->
                    ModCard(
                        entry = entry,
                        busy = busy,
                        onToggle = { enabled -> toggle(entry, enabled) },
                        onUpdate = { update(entry) },
                        onDelete = { deleteTarget = entry },
                    )
                }
            }
        }
    }

    val target = deleteTarget
    if (target != null) {
        DogeDialog(
            title = "Eliminare ${target.name}?",
            onDismiss = { deleteTarget = null },
            confirmText = "Elimina",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { ModManager.delete(target) }
                    result
                        .onSuccess {
                            message = "Mod eliminata: ${target.name}"
                            messageIsError = false
                            refresh++
                        }
                        .onFailure {
                            message = it.message ?: "Eliminazione fallita"
                            messageIsError = true
                        }
                }
            },
            dismissText = "Annulla",
        ) {
            Text(
                "Il file .jar verrà rimosso dal dispositivo. L'operazione non è reversibile.",
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
            )
        }
    }
}

@Composable
private fun ModCard(
    entry: ModManager.ModEntry,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    DogeCard(padding = 14.dp, accent = entry.enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DogePrimary.copy(alpha = 0.12f))
                    .border(1.dp, DogePrimary.copy(alpha = 0.30f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🧩", fontSize = 19.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = DogeTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatMb(entry.size)} · ${if (entry.enabled) "attiva" else "disattivata"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.enabled) DogeSuccess else DogeTextSecondary,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A1608),
                    checkedTrackColor = DogePrimary,
                    uncheckedThumbColor = DogeTextSecondary,
                    uncheckedTrackColor = DogeSurfaceHigh,
                    uncheckedBorderColor = DogeOutline,
                ),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onUpdate, enabled = !busy && entry.enabled) {
                Text(
                    "AGGIORNA",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (entry.enabled) DogePrimary else DogeTextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(
                    "ELIMINA",
                    style = MaterialTheme.typography.labelLarge,
                    color = DogeDanger,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
