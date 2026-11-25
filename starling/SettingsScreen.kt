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
        private const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions"
        private const val LIBRARY_REL = "mapping.bin"
        private const val OUT_REL = "conversion"
    }

    private lateinit var converter: AiConverterBridge
    private lateinit var aiSwitch: Switch
    private lateinit var statusView: TextView
    private lateinit var jarInput: EditText
    private lateinit var resultView: TextView

    private var endpoint: String = DEFAULT_ENDPOINT
    private lateinit var libraryFile: File
    private lateinit var outDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val base = getExternalFilesDir(null)
        libraryFile = File(base, LIBRARY_REL)
        outDir = File(base, OUT_REL)

        // L'AI parte in base alla preferenza salvata (default: disattivata).
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val aiEnabled = prefs.getBoolean(KEY_AI_MOD, false)
        endpoint = prefs.getString(KEY_AI_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

        converter = AiConverterBridge(
            endpoint,
            if (libraryFile.exists()) libraryFile.absolutePath else ""
        )

        setContentView(buildUi())

        aiSwitch.isChecked = aiEnabled
        // Applica lo stato iniziale al layer nativo e aggiorna la status.
        applyAiState(aiEnabled)
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

    override fun onDestroy() {
        runCatching { converter.close() }
        super.onDestroy()
    }
}
