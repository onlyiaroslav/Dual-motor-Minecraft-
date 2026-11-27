package dev.starling.app

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.starling.converter.AiConverterBridge
import java.io.File

/**
 * Schermata "Impostazioni" del launcher Starling (Fase 7).
 *
 * Espone l'interruttore **"AI Locale per Conversione Mod"**:
 *  - ATTIVO: il server AI locale (SmolLM2 via HTTP, API OpenAI-compatible)
 *    traduce le API sconosciute e aggiorna `mapping.bin`;
 *  - DISATTIVO: il tool non usa l'AI, si limita alla libreria esistente
 *    (`mapping.bin`) e scarta le API sconosciute loggando "AI disattivata";
 *  - server non raggiungibile o timeout ⇒ fallback, come se fosse disattivato.
 *
 * Privacy (zero codice copiato): la conversione produce solo il dizionario
 * `mapping.bin` e il log `conversion_log.json`. Nessun bytecode o asset viene
 * conservato/esportato, nessun upload: tutto resta sul dispositivo.
 *
 * Layer aggiuntivo: non tocca il bridge a 8 bit, il game loop o il layer social.
 *
 * Packaging Android: includere `libmod_converter.so` in
 * `src/main/jniLibs/arm64-v8a/` (prodotta da
 * `cargo ndk ... build -p mod-converter --features jni`) e la classe
 * `dev.starling.converter.AiConverterBridge` nell'APK. Il modello SmolLM2 gira
 * in un server OpenAI-compatible locale (es. `llama-server`) su
 * `http://127.0.0.1:8080`; l'endpoint è sovrascrivibile con la preferenza
 * `ai_locale_endpoint`.
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "starling_settings"
        private const val KEY_AI_MOD = "ai_locale_conversione_mod"
        private const val KEY_AI_ENDPOINT = "ai_locale_endpoint"
        private const val KEY_AI_AUTO_START = "ai_auto_start_server"
        private const val KEY_AI_SERVER_EXE = "ai_server_exe_path"
        private const val KEY_AI_MODEL_PATH = "ai_model_path"
        private const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
        private const val DEFAULT_SERVER_EXE = "llama-server"
        private const val DEFAULT_MODEL = "smollm2-360m-instruct-q4_k_m.gguf"
        private const val AI_ASSET_DIR = "ai"
        private const val LIBRARY_REL = "mapping.bin"
        private const val OUT_REL = "conversion"
    }

    private lateinit var converter: AiConverterBridge
    private lateinit var aiSwitch: Switch
    private lateinit var aiAutoStartSwitch: Switch
    private lateinit var statusView: TextView
    private lateinit var jarInput: EditText
    private lateinit var exeInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var resultView: TextView

    private var endpoint: String = DEFAULT_ENDPOINT
    private lateinit var libraryFile: File
    private lateinit var outDir: File

    // --- Server AI locale embedded (avvio automatico, zero-config) ---
    private var aiAutoStart = false
    private var serverExe = DEFAULT_SERVER_EXE
    private var modelPath = DEFAULT_MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val base = getExternalFilesDir(null)
        libraryFile = File(base, LIBRARY_REL)
        outDir = File(base, OUT_REL)

        // L'AI parte in base alla preferenza salvata (default: disattivata).
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val aiEnabled = prefs.getBoolean(KEY_AI_MOD, false)
        endpoint = prefs.getString(KEY_AI_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

        // Preferenze server embedded (zero-config AI locale).
        aiAutoStart = prefs.getBoolean(KEY_AI_AUTO_START, false)
        serverExe = prefs.getString(KEY_AI_SERVER_EXE, DEFAULT_SERVER_EXE) ?: DEFAULT_SERVER_EXE
        modelPath = prefs.getString(KEY_AI_MODEL_PATH, DEFAULT_MODEL) ?: DEFAULT_MODEL

        converter = AiConverterBridge(
            endpoint,
            if (libraryFile.exists()) libraryFile.absolutePath else ""
        )

        setContentView(buildUi())

        aiSwitch.isChecked = aiEnabled
        aiAutoStartSwitch.isChecked = aiAutoStart
        exeInput.setText(serverExe)
        modelInput.setText(modelPath)
        // Applica lo stato iniziale al layer nativo e aggiorna la status.
        applyAiState(aiEnabled)

        // Avvia il server AI locale in background se richiesto (zero-config).
        if (aiAutoStart) {
            startEmbeddedAiServerIfNeeded()
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 24)
        }

        root.addView(TextView(this).apply {
            text = "Impostazioni"
            textSize = 26f
        })

        root.addView(TextView(this).apply {
            text = "Conversione Mod"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })

        aiSwitch = Switch(this).apply {
            text = "AI Locale per Conversione Mod"
            textSize = 16f
            setOnCheckedChangeListener { _, isChecked -> onAiToggled(isChecked) }
        }
        root.addView(aiSwitch)

        // --- Server AI locale embedded (avvio automatico) ---
        aiAutoStartSwitch = Switch(this).apply {
            text = "Avvia server AI locale automaticamente"
            textSize = 14f
        }
        root.addView(aiAutoStartSwitch)

        exeInput = EditText(this).apply {
            hint = "Eseguibile server AI (es. llama-server)"
            setText(serverExe)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine()
        }
        root.addView(exeInput)

        modelInput = EditText(this).apply {
            hint = "Modello GGUF (es. smollm2-360m-instruct-q4_k_m.gguf)"
            setText(modelPath)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine()
        }
        root.addView(modelInput)

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 8)
        }
        root.addView(statusView)

        root.addView(TextView(this).apply {
            text = "Se attiva, il server AI locale (SmolLM2 via HTTP) traduce le API " +
                "sconosciute e aggiorna mapping.bin. Se disattivata, si usa solo la " +
                "libreria esistente e le API sconosciute vengono scartate " +
                "(log: \"AI disattivata\"). Server non raggiungibile o timeout ⇒ fallback. " +
                "Nessun codice o asset viene copiato o inviato: tutto resta sul dispositivo."
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        root.addView(TextView(this).apply {
            text = "Converti un JAR"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        jarInput = EditText(this).apply {
            hint = "Percorso del file .jar"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine()
        }
        row.addView(jarInput)
        row.addView(Button(this).apply {
            text = "Converti"
            setOnClickListener { convert() }
        })
        root.addView(row)

        resultView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 0)
        }
        root.addView(resultView)

        return root
    }

    /** Persiste la preferenza e applica il toggle al layer nativo. */
    private fun onAiToggled(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AI_MOD, enabled)
            .apply()
        applyAiState(enabled)
    }

    private fun applyAiState(enabled: Boolean) {
        val state = try {
            converter.setAiEnabled(enabled)
        } catch (e: IllegalStateException) {
            "errore"
        }
        statusView.text = describeState(state)
    }

    private fun describeState(state: String): String {
        return when (state) {
            "ready" -> "AI attiva e pronta (server locale: $endpoint)."
            "not_ready" -> "AI attiva ma server non raggiungibile ($endpoint): fallback senza AI."
            "disarmed" -> "AI disattivata: conversione con sola libreria mapping.bin."
            else -> "Stato AI: $state"
        }
    }

    private fun convert() {
        val path = jarInput.text.toString().trim()
        if (path.isEmpty()) {
            toast("Inserisci il percorso di un file .jar")
            return
        }
        val jar = File(path)
        if (!jar.exists()) {
            toast("File non trovato: $path")
            return
        }
        try {
            val summary = converter.convertJar(jar.absolutePath, outDir.absolutePath)
            resultView.text = "Conversione completata.\n$summary\nOutput: ${outDir.absolutePath}"
            toast("mapping.bin e conversion_log.json generati")
        } catch (e: IllegalStateException) {
            resultView.text = "Errore conversione: ${e.message}"
            toast("Conversione fallita: ${e.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // -----------------------------------------------------------------------
    // Server AI locale embedded: avvio automatico (zero configurazione utente)
    // -----------------------------------------------------------------------

    /** Avvia il server AI locale come processo figlio se non è già in ascolto.
     *  Cerca l'eseguibile e il modello negli asset (cartella ai) se non esistono
     *  ancora nello storage privato dell'app. */
    private fun startEmbeddedAiServerIfNeeded() {
        val exeFile = extractAssetIfNeeded(serverExe, "$AI_ASSET_DIR/${serverExe.split("/").last()}")
        val modelFile = extractAssetIfNeeded(modelPath, "$AI_ASSET_DIR/${modelPath.split("/").last()}")

        if (!exeFile.exists()) {
            statusView.text = "Eseguibile AI non trovato: $exeFile"
            return
        }
        if (!modelFile.exists()) {
            statusView.text = "Modello AI non trovato: $modelFile"
            return
        }

        try {
            val pb = ProcessBuilder(
                exeFile.absolutePath,
                "-m", modelFile.absolutePath,
                "--host", "127.0.0.1",
                "--port", "8080",
                "-c", "512"
            )
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            pb.start()
            statusView.text = "Server AI locale in avvio su 127.0.0.1:8080…"
        } catch (e: Exception) {
            statusView.text = "Impossibile avviare server AI: ${e.message}"
        }
    }

    /** Copia un asset nello storage privato se il file di destinazione non esiste. */
    private fun extractAssetIfNeeded(path: String, assetName: String): File {
        val dest = File(path)
        if (dest.exists()) return dest
        return try {
            assets.open(assetName).use { input ->
                dest.parentFile?.mkdirs()
                input.copyTo(dest.outputStream())
            }
            dest
        } catch (e: Exception) {
            dest // resta inesistente, il chiamante gestisce l'errore
        }
    }

    override fun onDestroy() {
        runCatching { converter.close() }
        super.onDestroy()
    }
}
