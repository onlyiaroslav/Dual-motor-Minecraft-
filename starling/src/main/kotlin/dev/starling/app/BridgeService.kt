package dev.starling.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * Service Android che avvia la JVM headless IN-PROCESS (JNI_CreateJavaVM dal
 * layer C++ di starling_jvm_host) e carica mapping.bin.
 *
 * Due runtime nello stesso processo:
 *  - ART: questo Service + il motore Starling;
 *  - JVM OpenJDK embedded: mod Java + classi dev.starling.bridge.* (dal jar).
 * Gli oggetti NON attraversano il confine: il sink è EngineSink, creato DENTRO
 * la JVM embedded da JvmBootstrap; inoltra le azioni al motore via callback C.
 *
 * Storage: tutto in getExternalFilesDir / filesDir / cacheDir → Scoped Storage
 * Android 15 OK, nessun permesso runtime.
 */
class BridgeService : Service() {

    companion object {
        init {
            // Ordine: prima il bridge Rust, poi l'host C++ che lo usa.
            System.loadLibrary("bridge_jni")
            System.loadLibrary("starling_jvm_host")
            // libjvm.so della JRE Android viene caricata con dlopen dall'host:
            // deve stare in nativeLibraryDir (jniLibs/arm64-v8a).
        }

        @JvmStatic
        external fun nativeStart(
            jreLib: String, classpath: String, modsDir: String,
            lutDir: String, mappingPath: String, tmpDir: String
        ): Boolean

        @JvmStatic
        external fun nativeStop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())

        val base = getExternalFilesDir(null)!!
        val mods = File(base, "mods").apply { mkdirs() }
        val lut = File(base, "lut").apply { mkdirs() }
        val bridgeDir = File(filesDir, "bridge").apply { mkdirs() }

        // Classi del bridge per la JVM embedded (jar, NON dex).
        val bridgeJar = File(bridgeDir, "bridge-u8.jar")
        assets.open("bridge-u8.jar").use { input ->
            bridgeJar.outputStream().use { input.copyTo(it) }
        }

        // mapping.bin: generato OFFLINE (tool di analisi). Se assente, si usa
        // il default impacchettato negli asset; le mod senza mapping restano
        // in coda di analisi offline (ModRegistry.scan), mai analizzate qui.
        val mapping = File(lut, "mapping.bin")
        if (!mapping.exists()) {
            assets.open("mapping.bin").use { input ->
                mapping.outputStream().use { input.copyTo(it) }
            }
        }

        val jreLib = File(applicationInfo.nativeLibraryDir, "libjvm.so")
        val ok = nativeStart(
            jreLib.absolutePath,
            bridgeJar.absolutePath,
            mods.absolutePath,
            lut.absolutePath,
            mapping.absolutePath,
            cacheDir.absolutePath
        )
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Il render loop del motore Rust chiama starling_jvm_poll_frame()
        // (C ABI) una volta per frame: niente JNI dal lato Kotlin per frame.
        return START_STICKY
    }

    override fun onDestroy() {
        nativeStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            "bridge", "Motore mod Java", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, "bridge")
            .setContentTitle("Starling — JVM headless attiva")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }
}