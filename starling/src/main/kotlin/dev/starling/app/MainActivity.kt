package dev.starling.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File

/**
 * Main activity of the Doge Client launcher.
 *
 * The "Play" button opens [GameActivity]: SurfaceView + voxel Rust renderer
 * (wgpu/Vulkan) with a 60 FPS game loop and a 20 TPS tick. The embedded
 * headless JVM is started there ([nativeStartGame], same logic as
 * BridgeService, shared via startJvmEmbedded in starling_jvm_host.cpp); if no
 * JRE is installed the 3D game still runs and the bridge poll returns 0.
 *
 * At startup, if the user enabled auto-start in Settings, it automatically
 * launches the local AI server (llama-server) on 127.0.0.1:8080 so that mod
 * conversion with the local AI works without any manual configuration.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val DEFAULT_SERVER_EXE = "llama-server"
        private const val DEFAULT_MODEL = "smollm2-360m-instruct-q4_k_m.gguf"
        private const val AI_ASSET_DIR = "ai"

        init {
            // Load the native libraries (order: bridge Rust first, then host C++).
            System.loadLibrary("bridge_jni")
            System.loadLibrary("starling_jvm_host")
        }

        /**
         * Avvia il gioco: dlopen libjvm, JNI_CreateJavaVM, JvmBootstrap.start,
         * ring buffer, tick 20 TPS. Stessa logica di BridgeService.nativeStart.
         * Chiamata dal render thread di GameActivity. Restituisce true se
         * l'avvio riesce.
         *
         * @JvmStatic: the JNI symbol is Java_dev_starling_app_MainActivity_
         * nativeStartGame (as exported by starling_jvm_host.cpp), not
         * on the Companion class.
         */
        @JvmStatic
        external fun nativeStartGame(
            jreLib: String,
            classpath: String,
            modsDir: String,
            lutDir: String,
            mappingPath: String,
            tmpDir: String
        ): Boolean
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Automatic local AI server startup (zero-config) ---
        val aiPrefs = getSharedPreferences("starling_settings", MODE_PRIVATE)
        val aiAutoStart = aiPrefs.getBoolean("ai_auto_start_server", false)
        if (aiAutoStart) {
            val exe = aiPrefs.getString("ai_server_exe_path", DEFAULT_SERVER_EXE) ?: DEFAULT_SERVER_EXE
            val model = aiPrefs.getString("ai_model_path", DEFAULT_MODEL) ?: DEFAULT_MODEL
            startEmbeddedAiServer(exe, model)
        }

        val ctx = applicationContext

        // Paths identical to the ones used by BridgeService (Scoped Storage OK).
        val base = ctx.getExternalFilesDir(null)!!
        val mods = File(base, "mods").apply { mkdirs() }
        val lut = File(base, "lut").apply { mkdirs() }
        val bridgeDir = File(ctx.filesDir, "bridge").apply { mkdirs() }

        // The bridge classes for the embedded JVM (jar, NOT dex).
        // If the asset is missing, we do not block the UI startup: the Play
        // button will have no effect until the jar is added.
        val bridgeJar = File(bridgeDir, "bridge-u8.jar")
        if (!bridgeJar.exists()) {
            runCatching {
                ctx.assets.open("bridge-u8.jar").use { input ->
                    bridgeJar.outputStream().use { input.copyTo(it) }
                }
            }
        }

        // mapping.bin: default offline, if absent the packaged one is used.
        val mapping = File(lut, "mapping.bin")
        if (!mapping.exists()) {
            runCatching {
                ctx.assets.open("mapping.bin").use { input ->
                    mapping.outputStream().use { input.copyTo(it) }
                }
            }
        }

        setContent {
            DogeTheme {
                MainScreen(
                    onStartGame = {
                        // Play → GameActivity: SurfaceView + renderer Rust.
                        // La JVM embedded parte sul render thread di gioco.
                        startActivity(Intent(ctx, GameActivity::class.java))
                    }
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Embedded local AI server: automatic startup (zero-config)
    // -----------------------------------------------------------------------

    /** Starts the local AI server as a child process if it is not already listening.
     *  Looks for the executable and the model in the assets (ai/*) if they are not
     *  yet present in the app's private storage. */
    private fun startEmbeddedAiServer(exePath: String, modelPath: String) {
        val exeFile = extractAssetIfNeeded(exePath, "$AI_ASSET_DIR/${exePath.split("/").last()}")
        val modelFile = extractAssetIfNeeded(modelPath, "$AI_ASSET_DIR/${modelPath.split("/").last()}")

        if (!exeFile.exists() || !modelFile.exists()) {
            return // fallback: the AI stays disabled, no crash
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
        } catch (e: Exception) {
            // Ignored: fallback handled by local-ai (server absent => AI disabled)
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
            dest // stays non-existent, the caller handles the error
        }
    }
}
