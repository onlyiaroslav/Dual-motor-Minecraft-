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
 * Settings screen of the Starling launcher (Phase 7).
 *
 * Exposes the **"AI Local for Mod Conversion"** toggle:
 *  - ENABLED: the local AI server (SmolLM2 via HTTP, OpenAI-compatible API)
 *    translates unknown APIs and updates `mapping.bin`;
 *  - DISABLED: the tool does not use the AI, it only uses the existing
 *    library (`mapping.bin`) and discards unknown APIs by logging "AI disabled";
 *  - server not reachable or timeout ⇒ fallback, as if it were disabled.
 *
 * Privacy (zero code copied): the conversion produces only the dictionary
 * `mapping.bin` and the log `conversion_log.json`. No bytecode or asset is
 * kept/exported, no upload: everything stays on the device.
 *
 * Additional layer: it does not touch the 8-bit bridge, the game loop or the social layer.
 *
 * Android packaging: include `libmod_converter.so` in
 * `src/main/jniLibs/arm64-v8a/` (produced by
 * `cargo ndk ... build --package mod-converter --features jni`) and the
 * `dev.starling.converter.AiConverterBridge` class in the APK. The SmolLM2 model runs
 * in a local OpenAI-compatible server (e.g. `llama-server`) on
 * `http://127.0.0.1:8080`; the endpoint is overridable with the preference
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

    // --- Embedded local AI server (automatic startup, zero-config) ---
    private var aiAutoStart = false
    private var serverExe = DEFAULT_SERVER_EXE
    private var modelPath = DEFAULT_MODEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val base = getExternalFilesDir(null)
        libraryFile = File(base, LIBRARY_REL)
        outDir = File(base, OUT_REL)

        // The AI is driven by the saved preference (default: disabled).
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val aiEnabled = prefs.getBoolean(KEY_AI_MOD, false)
        endpoint = prefs.getString(KEY_AI_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

        // Embedded server preferences (zero-config local AI).
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
        // Apply the initial state to the native layer and update the status.
        applyAiState(aiEnabled)

        // Start the local AI server in the background if requested (zero-config).
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
            text = "Settings"
            textSize = 26f
        })

        root.addView(TextView(this).apply {
            text = "Mod conversion"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })

        aiSwitch = Switch(this).apply {
            text = "AI Local for Mod Conversion"
            textSize = 16f
            setOnCheckedChangeListener { _, isChecked -> onAiToggled(isChecked) }
        }
        root.addView(aiSwitch)

        // --- Embedded local AI server (automatic startup) ---
        aiAutoStartSwitch = Switch(this).apply {
            text = "Automatically start local AI server"
            textSize = 14f
        }
        root.addView(aiAutoStartSwitch)

        exeInput = EditText(this).apply {
            hint = "AI server executable (e.g. llama-server)"
            text = serverExe
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine()
        }
        root.addView(exeInput)

        modelInput = EditText(this).apply {
            hint = "GGUF model (e.g. smollm2-360m-instruct-q4_k_m.gguf)"
            text = modelPath
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
            text = "If enabled, the local AI server (SmolLM2 via HTTP) translates " +
                "unknown APIs and updates mapping.bin. If disabled, only the existing " +
                "library is used and unknown APIs are discarded " +
                "(log: \"AI disabled\"). Server not reachable or timeout ⇒ fallback. " +
                "No code or assets are copied or sent: everything stays on the device."
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        root.addView(TextView(this).apply {
            text = "Convert a JAR"
            textSize = 18f
            setPadding(0, 32, 0, 8)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        jarInput = EditText(this).apply {
            hint = "Path of the .jar file"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine()
        }
        row.addView(jarInput)
        row.addView(Button(this).apply {
            text = "Convert"
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

    /** Persists the preference and applies the toggle to the native layer. */
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
            "error"
        }
        statusView.text = describeState(state)
    }

    private fun describeState(state: String): String {
        return when (state) {
            "ready" -> "AI is active and ready (local server: $endpoint)."
            "not_ready" -> "AI is active but the server is not reachable ($endpoint): AI fallback."
            "disarmed" -> "AI disabled: conversion uses only the mapping.bin library."
            else -> "AI state: $state"
        }
    }

    private fun convert() {
        val path = jarInput.text.toString().trim()
        if (path.isEmpty()) {
            toast("Enter the path of a .jar file")
            return
        }
        val jar = File(path)
        if (!jar.exists()) {
            toast("File not found: $path")
            return
        }
        try {
            val summary = converter.convertJar(jar.absolutePath, outDir.absolutePath)
            resultView.text = "Conversion completed.\n$summary\nOutput: ${outDir.absolutePath}"
            toast("mapping.bin and conversion_log.json generated")
        } catch (e: IllegalStateException) {
            resultView.text = "Conversion error: ${e.message}"
            toast("Conversion failed: ${e.message}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // -----------------------------------------------------------------------
    // Embedded local AI server: automatic startup (zero user config)
    // -----------------------------------------------------------------------

    /** Starts the local AI server as a child process if not already listening.
     *  Looks for the executable and the model in the assets (ai/*) if they are not
     *  yet present in the app's private storage. */
    private fun startEmbeddedAiServerIfNeeded() {
        val exeFile = extractAssetIfNeeded(serverExe, "$AI_ASSET_DIR/${serverExe.split("/").last()}")
        val modelFile = extractAssetIfNeeded(modelPath, "$AI_ASSET_DIR/${modelPath.split("/").last()}")

        if (!exeFile.exists()) {
            statusView.text = "AI executable not found: $exeFile"
            return
        }
        if (!modelFile.exists()) {
            statusView.text = "AI model not found: $modelFile"
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
            statusView.text = "Local AI server starting on 127.0.0.1:8080…"
        } catch (e: Exception) {
            statusView.text = "Unable to start the AI server: ${e.message}"
        }
    }

    /** Copies an asset into private storage if the destination file does not exist. */
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
