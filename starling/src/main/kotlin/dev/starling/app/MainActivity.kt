package dev.starling.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File

/**
 * Activity principale del launcher Doge Client.
 *
 * Il pulsante "Play" chiama [nativeStartGame], che avvia la JVM headless
 * in-process (stessa logica di BridgeService, condivisa via startJvmEmbedded
 * in starling_jvm_host.cpp). Nessun file Rust/JNI/HTTP è stato modificato.
 *
 * All'avvio, se l'utente ha abilitato l'auto-start nelle impostazioni, lancia
 * automaticamente il server AI locale (llama-server) su 127.0.0.1:8080
 * affinché la conversione mod con AI locale funzioni senza alcuna
 * configurazione manuale.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val DEFAULT_SERVER_EXE = "llama-server"
        private const val DEFAULT_MODEL = "smollm2-360m-instruct-q4_k_m.gguf"
        private const val AI_ASSET_DIR = "ai"

        init {
            // Carica le librerie native (ordine: bridge Rust prima, poi host C++).
            System.loadLibrary("bridge_jni")
            System.loadLibrary("starling_jvm_host")
        }

        /**
         * Avvia il gioco: dlopen libjvm, JNI_CreateJavaVM, JvmBootstrap.start,
         * ring buffer, tick 20 TPS. Stessa logica di BridgeService.nativeStart.
         * Restituisce true se l'avvio riesce.
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

        // --- Avvio automatico server AI locale (zero-config) ---
        val aiPrefs = getSharedPreferences("starling_settings", MODE_PRIVATE)
        val aiAutoStart = aiPrefs.getBoolean("ai_auto_start_server", false)
        if (aiAutoStart) {
            val exe = aiPrefs.getString("ai_server_exe_path", DEFAULT_SERVER_EXE) ?: DEFAULT_SERVER_EXE
            val model = aiPrefs.getString("ai_model_path", DEFAULT_MODEL) ?: DEFAULT_MODEL
            startEmbeddedAiServer(exe, model)
        }

        val ctx = applicationContext

        // Percorsi identici a quelli usati da BridgeService (Scoped Storage OK).
        val base = ctx.getExternalFilesDir(null)!!
        val mods = File(base, "mods").apply { mkdirs() }
        val lut = File(base, "lut").apply { mkdirs() }
        val bridgeDir = File(ctx.filesDir, "bridge").apply { mkdirs() }

        // Classi del bridge per la JVM embedded (jar, NON dex).
        // Se l'asset non è presente, non blocchiamo l'avvio della UI: il
        // pulsante Play resterà senza effetto finché il jar non viene aggiunto.
        val bridgeJar = File(bridgeDir, "bridge-u8.jar")
        if (!bridgeJar.exists()) {
            runCatching {
                ctx.assets.open("bridge-u8.jar").use { input ->
                    bridgeJar.outputStream().use { input.copyTo(it) }
                }
            }
        }

        // mapping.bin: default offline, se assente si usa quello impacchettato.
        val mapping = File(lut, "mapping.bin")
        if (!mapping.exists()) {
            runCatching {
                ctx.assets.open("mapping.bin").use { input ->
                    mapping.outputStream().use { input.copyTo(it) }
                }
            }
        }

        val jreLib = File(ctx.applicationInfo.nativeLibraryDir, "libjvm.so")

        setContent {
            DogeTheme {
                MainScreen(
                    onStartGame = {
                        nativeStartGame(
                            jreLib.absolutePath,
                            bridgeJar.absolutePath,
                            mods.absolutePath,
                            lut.absolutePath,
                            mapping.absolutePath,
                            ctx.cacheDir.absolutePath
                        )
                    }
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Server AI locale embedded: avvio automatico (zero configurazione utente)
    // -----------------------------------------------------------------------

    /** Avvia il server AI locale come processo figlio se non è già in ascolto.
     *  Cerca l'eseguibile e il modello negli asset (ai/*) se non esistono
     *  ancora nello storage privato dell'app. */
    private fun startEmbeddedAiServer(exePath: String, modelPath: String) {
        val exeFile = extractAssetIfNeeded(exePath, "$AI_ASSET_DIR/${exePath.split("/").last()}")
        val modelFile = extractAssetIfNeeded(modelPath, "$AI_ASSET_DIR/${modelPath.split("/").last()}")

        if (!exeFile.exists() || !modelFile.exists()) {
            return // fallback: l'AI resta disattivata, nessun crash
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
            // Ignorato: fallback gestito da local-ai (server assente ⇒ AI disattivata)
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
}
