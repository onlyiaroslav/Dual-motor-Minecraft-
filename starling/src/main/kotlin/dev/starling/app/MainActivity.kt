package dev.starling.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/**
 * Activity principale del launcher Doge Client.
 *
 * Il pulsante "Play" apre [GameActivity]: SurfaceView + renderer voxel Rust
 * (wgpu/Vulkan) con game loop a 60 FPS e tick 20 TPS. La JVM headless embedded
 * viene avviata là ([nativeStartGame], stessa logica di BridgeService, condivisa
 * via startJvmEmbedded in starling_jvm_host.cpp); senza JRE installata il gioco
 * 3D gira lo stesso e il poll del bridge ritorna 0.
 *
 * All'avvio, se abilitato nelle impostazioni, lancia il server AI locale.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** True se le librerie native sono state caricate senza errori. */
        var nativeLibsLoaded: Boolean = false
            private set

        /** Dettaglio del fallimento (quale libreria e messaggio del linker). */
        var nativeLoadError: String? = null
            private set

        init {
            // Ordine: prima il bridge Rust, poi l'host C++ che lo usa.
            // Se una libreria manca o fallisce non crashiamo: la UI parte e il
            // pulsante Gioca mostra QUALE libreria fallisce e perché.
            val errors = mutableListOf<String>()
            for (lib in listOf("bridge_jni", "starling_jvm_host")) {
                try {
                    System.loadLibrary(lib)
                } catch (t: Throwable) {
                    errors += "$lib → ${t.message ?: t.javaClass.simpleName}"
                }
            }
            nativeLibsLoaded = errors.isEmpty()
            nativeLoadError = if (errors.isEmpty()) null else errors.joinToString("\n")
        }

        /**
         * Avvia il gioco: dlopen libjvm, JNI_CreateJavaVM, JvmBootstrap.start,
         * ring buffer, tick 20 TPS. Stessa logica di BridgeService.nativeStart.
         * Chiamata dal render thread di GameActivity. Restituisce true se
         * l'avvio riesce.
         *
         * @JvmStatic: il simbolo JNI è Java_dev_starling_app_MainActivity_
         * nativeStartGame (come esportato da starling_jvm_host.cpp), non
         * sulla classe Companion.
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val ctx = applicationContext
        val aiPrefs = getSharedPreferences("starling_settings", MODE_PRIVATE)

        // --- Avvio automatico server AI locale (zero-config) ---------------
        if (aiPrefs.getBoolean("ai_auto_start_server", false)) {
            val exe = aiPrefs.getString("ai_server_exe_path", AiLocalServer.DEFAULT_SERVER_EXE)
                ?: AiLocalServer.DEFAULT_SERVER_EXE
            val model = aiPrefs.getString("ai_model_path", AiLocalServer.DEFAULT_MODEL)
                ?: AiLocalServer.DEFAULT_MODEL
            thread(name = "doge-ai-server") {
                AiLocalServer.startIfNeeded(ctx, exe, model)
            }
        }

        // --- Percorsi (identici a BridgeService: Scoped Storage OK) --------
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
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

        setContent {
            DogeTheme {
                var account by remember { mutableStateOf(AccountStore.load(ctx)) }
                var install by remember { mutableStateOf(InstallState()) }
                val scope = rememberCoroutineScope()

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
}
