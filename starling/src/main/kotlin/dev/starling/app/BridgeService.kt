package dev.starling.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * Service that starts the headless IN-PROCESS JVM (JNI_CreateJavaVM from the
 * C++ layer of starling_jvm_host) and loads mapping.bin.
 *
 * Due runtime nello stesso processo:
 *  - ART: questo Service + il motore Starling;
 *  - JVM OpenJDK embedded: mod Java + classi dev.starling.bridge.* (dal jar).
 * The objects do NOT cross the boundary: the sink is EngineSink, created INSIDE
 * the embedded JVM from JvmBootstrap; forwards the actions to the engine via C callback.
 *
 * Storage: all in getExternalFilesDir / filesDir / cacheDir → Scoped Storage
 * Android 15 OK, no runtime permission.
 */
class BridgeService : Service() {

    companion object {
        init {
            // Order: bridge Rust first, then the C++ host that uses it.
            System.loadLibrary("bridge_jni")
            System.loadLibrary("starling_jvm_host")
            // libjvm.so of the Android JRE is loaded with dlopen by the host:
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

        // Bridge classes for the embedded JVM (jar, NOT dex).
        val bridgeJar = File(bridgeDir, "bridge-u8.jar")
        assets.open("bridge-u8.jar").use { input ->
            bridgeJar.outputStream().use { input.copyTo(it) }
        }

        // mapping.bin: generated OFFLINE (analysis tool). If absent, the
        // packaged default is used; mods without mapping stay
        // in the offline analysis queue (ModRegistry.scan), never analyzed here.
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
        // The render loop of the Rust engine calls starling_jvm_poll_frame()
        // (C ABI) once per frame: no JNI from the Kotlin side per frame.
        return START_STICKY
    }

    override fun onDestroy() {
        nativeStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            "bridge", "Java mod engine", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, "bridge")
            .setContentTitle("Starling — headless JVM active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }
}