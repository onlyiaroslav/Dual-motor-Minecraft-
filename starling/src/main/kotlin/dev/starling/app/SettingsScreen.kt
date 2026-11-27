package dev.starling.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.starling.converter.AiConverterBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Schermata "Impostazioni" (Fase 7) in Compose.
 *
 * La logica nativa è invariata: [AiConverterBridge] espone il toggle AI locale
 * e la conversione JAR. Qui cambia solo la UI (card, switch e campi dark).
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            DogeTheme {
                DogeScreenBackground {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                    ) {
                        SettingsContent()
                    }
                }
            }
        }
    }
}

private const val PREFS = "starling_settings"
private const val KEY_AI_MOD = "ai_locale_conversione_mod"
private const val KEY_AI_ENDPOINT = "ai_locale_endpoint"
private const val KEY_AI_AUTO_START = "ai_auto_start_server"
private const val KEY_AI_SERVER_EXE = "ai_server_exe_path"
private const val KEY_AI_MODEL_PATH = "ai_model_path"
private const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
private const val LIBRARY_REL = "mapping.bin"
private const val OUT_REL = "conversion"
private const val KEY_XRAY = "doge_xray"
private const val KEY_ENGINE_OPT = "doge_engine_optimizations"

@Composable
fun SettingsContent(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var aiEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_AI_MOD, false)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean(KEY_AI_AUTO_START, false)) }
    var endpoint by remember {
        mutableStateOf(prefs.getString(KEY_AI_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT)
    }
    var serverExe by remember {
        mutableStateOf(
            prefs.getString(KEY_AI_SERVER_EXE, AiLocalServer.DEFAULT_SERVER_EXE)
                ?: AiLocalServer.DEFAULT_SERVER_EXE
        )
    }
    var modelPath by remember {
        mutableStateOf(
            prefs.getString(KEY_AI_MODEL_PATH, AiLocalServer.DEFAULT_MODEL)
                ?: AiLocalServer.DEFAULT_MODEL
        )
    }

    var converter by remember { mutableStateOf<AiConverterBridge?>(null) }
    var status by remember { mutableStateOf("Inizializzazione convertitore…") }
    var jarPath by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var manifestUrl by remember { mutableStateOf(GameInstaller.manifestUrl(ctx)) }
    var refresh by remember { mutableIntStateOf(0) }

    // --- Toggle specifici di Doge Client -------------------------------
    var xrayEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_XRAY, false)) }
    var engineOptimized by remember { mutableStateOf(prefs.getBoolean(KEY_ENGINE_OPT, true)) }
    var showXrayWarning by remember { mutableStateOf(false) }

    fun libraryPath(): String {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val library = File(base, LIBRARY_REL)
        return if (library.exists()) library.absolutePath else ""
    }

    fun buildConverter(): Result<AiConverterBridge> =
        runCatching { AiConverterBridge(endpoint, libraryPath()) }

    fun applyAiState(enabled: Boolean) {
        val bridge = converter
        scope.launch {
            val state = if (bridge == null) {
                "errore"
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { bridge.setAiEnabled(enabled) }.getOrElse { "errore" }
                }
            }
            status = describeAiState(state, endpoint)
        }
    }

    LaunchedEffect(Unit) {
        val built = withContext(Dispatchers.IO) { buildConverter() }
        built.onSuccess { bridge ->
            converter = bridge
            val state = withContext(Dispatchers.IO) {
                runCatching { bridge.setAiEnabled(aiEnabled) }.getOrElse { "errore" }
            }
            status = describeAiState(state, endpoint)
        }
        built.onFailure {
            status = "Convertitore non disponibile: ${it.message}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            converter?.let { bridge -> runCatching { bridge.close() } }
        }
    }

    fun reconnect() {
        scope.launch {
            busy = true
            converter?.let { runCatching { it.close() } }
            val built = withContext(Dispatchers.IO) { buildConverter() }
            busy = false
            built.onSuccess { bridge ->
                converter = bridge
                val state = withContext(Dispatchers.IO) {
                    runCatching { bridge.setAiEnabled(aiEnabled) }.getOrElse { "errore" }
                }
                status = describeAiState(state, endpoint)
            }
            built.onFailure {
                converter = null
                status = "Convertitore non disponibile: ${it.message}"
            }
        }
    }

    fun convertJar() {
        val bridge = converter
        if (bridge == null) {
            result = "Convertitore non disponibile."
            return
        }
        val path = jarPath.trim()
        if (path.isEmpty()) {
            result = "Inserisci il percorso di un file .jar"
            return
        }
        val jar = File(path)
        if (!jar.exists()) {
            result = "File non trovato: $path"
            return
        }
        val outDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, OUT_REL)
        scope.launch {
            busy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching { bridge.convertJar(jar.absolutePath, outDir.absolutePath) }
            }
            busy = false
            outcome
                .onSuccess {
                    result = "Conversione completata.\n$it\nOutput: ${outDir.absolutePath}"
                    toast(ctx, "mapping.bin e conversion_log.json generati")
                }
                .onFailure {
                    result = "Errore conversione: ${it.message}"
                }
        }
    }

    val runtime = remember(refresh) { GameInstaller.effectiveRuntimeLib(ctx) }
    val bridgeJar = remember(refresh) { GameInstaller.bridgeJar(ctx) }
    val modsDir = remember(refresh) {
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "mods")
    }
    val modCount = remember(refresh) {
        modsDir.listFiles { file -> file.isFile && file.extension.equals("jar", true) }?.size ?: 0
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Impostazioni",
                    style = MaterialTheme.typography.headlineLarge,
                    color = DogeTextPrimary,
                )
                Text(
                    "AI locale, conversione mod e file di gioco",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                )
            }
            DogePill(
                text = if (aiEnabled) "AI ATTIVA" else "AI OFF",
                color = if (aiEnabled) DogeSuccess else DogeTextSecondary,
            )
        }

        // --- Toggle Doge Client -------------------------------------------
        DogeCard {
            DogeSectionTitle("Doge Client")
            Spacer(Modifier.height(10.dp))

            ToggleRow(
                title = "X-Ray",
                subtitle = "Visibilità attraverso i blocchi",
                checked = xrayEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        showXrayWarning = true
                    } else {
                        xrayEnabled = false
                        prefs.edit().putBoolean(KEY_XRAY, false).apply()
                    }
                },
            )

            ToggleRow(
                title = "Ottimizzazioni motore Rust",
                subtitle = "Tick e render ottimizzati per il bridge nativo",
                checked = engineOptimized,
                onCheckedChange = { enabled ->
                    engineOptimized = enabled
                    prefs.edit().putBoolean(KEY_ENGINE_OPT, enabled).apply()
                },
            )

            Spacer(Modifier.height(6.dp))
            Text(
                "X-Ray è pensato per singleplayer e server privati di tua proprietà.",
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        // --- AI locale -----------------------------------------------------
        DogeCard {
            DogeSectionTitle("Conversione mod")
            Spacer(Modifier.height(10.dp))

            ToggleRow(
                title = "AI locale per conversione mod",
                subtitle = "Traduce le API sconosciute e aggiorna mapping.bin",
                checked = aiEnabled,
                onCheckedChange = { enabled ->
                    aiEnabled = enabled
                    prefs.edit().putBoolean(KEY_AI_MOD, enabled).apply()
                    applyAiState(enabled)
                },
            )

            ToggleRow(
                title = "Avvia server AI automaticamente",
                subtitle = "llama-server su 127.0.0.1:8080 all'avvio",
                checked = autoStart,
                onCheckedChange = { enabled ->
                    autoStart = enabled
                    prefs.edit().putBoolean(KEY_AI_AUTO_START, enabled).apply()
                    if (enabled) {
                        scope.launch {
                            val started = withContext(Dispatchers.IO) {
                                AiLocalServer.startIfNeeded(ctx, serverExe, modelPath)
                            }
                            started
                                .onSuccess { status = it }
                                .onFailure { status = "Server AI: ${it.message}" }
                        }
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            DogeTextField(
                value = endpoint,
                onValueChange = {
                    endpoint = it
                    prefs.edit().putString(KEY_AI_ENDPOINT, it).apply()
                },
                label = "Endpoint AI (OpenAI-compatible)",
                placeholder = DEFAULT_ENDPOINT,
                keyboardType = KeyboardType.Uri,
            )

            Spacer(Modifier.height(10.dp))

            DogeTextField(
                value = serverExe,
                onValueChange = {
                    serverExe = it
                    prefs.edit().putString(KEY_AI_SERVER_EXE, it).apply()
                },
                label = "Eseguibile server AI",
                placeholder = AiLocalServer.DEFAULT_SERVER_EXE,
            )

            Spacer(Modifier.height(10.dp))

            DogeTextField(
                value = modelPath,
                onValueChange = {
                    modelPath = it
                    prefs.edit().putString(KEY_AI_MODEL_PATH, it).apply()
                },
                label = "Modello GGUF",
                placeholder = AiLocalServer.DEFAULT_MODEL,
            )

            Spacer(Modifier.height(12.dp))

            DogeGhostButton(
                text = "Riconnetti AI",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = { reconnect() },
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(DogePrimary))
                Spacer(Modifier.width(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- Conversione JAR ----------------------------------------------
        DogeCard {
            DogeSectionTitle("Converti un JAR")
            Spacer(Modifier.height(10.dp))
            Text(
                "Genera mapping.bin e conversion_log.json nella cartella conversion/. " +
                    "Nessun bytecode viene conservato o inviato.",
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            DogeTextField(
                value = jarPath,
                onValueChange = { jarPath = it },
                label = "Percorso del file .jar",
                placeholder = "/storage/emulated/0/Download/mod.jar",
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(10.dp))
            DogePrimaryButton(
                text = "Converti",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = { convertJar() },
            )
            val current = result
            if (current != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    current,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current.startsWith("Errore")) DogeDanger else DogeSuccess,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- File di gioco ------------------------------------------------
        DogeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DogeSectionTitle("File di gioco")
                Spacer(Modifier.weight(1f))
                Text(
                    "AGGIORNA",
                    style = MaterialTheme.typography.labelSmall,
                    color = DogePrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(DogePrimary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clickable { refresh++ },
                )
            }
            Spacer(Modifier.height(10.dp))

            StatusRow(
                label = "Runtime Java (libjvm.so)",
                ok = runtime.exists(),
                detail = if (runtime.exists()) formatMb(runtime.length()) else "mancante",
            )
            StatusRow(
                label = "Bridge Java (bridge-u8.jar)",
                ok = bridgeJar.exists(),
                detail = if (bridgeJar.exists()) formatMb(bridgeJar.length()) else "mancante",
            )
            StatusRow(
                label = "Mod installate",
                ok = true,
                detail = modCount.toString(),
            )
            DogeInfoRow("Cartella giochi", "minecraft/")
            DogeInfoRow("Versione", GameInstaller.MC_VERSION)

            Spacer(Modifier.height(12.dp))

            DogeTextField(
                value = manifestUrl,
                onValueChange = {
                    manifestUrl = it
                    GameInstaller.setManifestUrl(ctx, it)
                },
                label = "Manifest download (opzionale)",
                placeholder = "https://…/manifest.json",
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Se impostato, il pulsante Gioca scarica i file elencati nel manifest " +
                    "mostrando MB e percentuale. Vuoto = verifica dei soli file locali.",
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        DogeCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = DogeTextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Privacy: la conversione produce solo il dizionario mapping.bin e il " +
                        "log conversion_log.json. Nessun bytecode o asset viene conservato " +
                        "o esportato: tutto resta sul dispositivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (showXrayWarning) {
        DogeDialog(
            title = "Attivare X-Ray?",
            onDismiss = { showXrayWarning = false },
            confirmText = "Ho capito, attiva",
            onConfirm = {
                showXrayWarning = false
                xrayEnabled = true
                prefs.edit().putBoolean(KEY_XRAY, true).apply()
            },
            dismissText = "Annulla",
        ) {
            Text(
                "X-Ray mostra i blocchi attraverso le pareti. Usalo solo in " +
                    "singleplayer o su server privati di tua proprietà: sui server " +
                    "pubblici può comportare sanzioni o ban.",
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Componenti locali
// ---------------------------------------------------------------------------
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = DogeTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF1A1608),
                checkedTrackColor = DogePrimary,
                uncheckedThumbColor = DogeTextSecondary,
                uncheckedTrackColor = DogeSurfaceHigh,
                uncheckedBorderColor = DogeOutline,
            ),
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (ok) DogeSuccess else DogeDanger,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = DogeTextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) DogeTextSecondary else DogeDanger,
            fontSize = 12.sp,
        )
    }
}

private fun describeAiState(state: String, endpoint: String): String = when (state) {
    "ready" -> "AI attiva e pronta (server locale: $endpoint)."
    "not_ready" -> "AI attiva ma server non raggiungibile ($endpoint): fallback senza AI."
    "disarmed" -> "AI disattivata: conversione con sola libreria mapping.bin."
    else -> "Stato AI: $state"
}

private fun toast(ctx: Context, message: String) {
    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
}
