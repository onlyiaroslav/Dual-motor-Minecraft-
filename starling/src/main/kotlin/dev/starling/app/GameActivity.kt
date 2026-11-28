package dev.starling.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

/**
 * Activity di gioco: SurfaceView + renderer voxel Rust (libstarling_render).
 *
 * Al tap su Play il launcher apre questa Activity. Un render thread dedicato:
 *  1. avvia la JVM headless embedded ([MainActivity.nativeStartGame], stessi
 *     percorsi di BridgeService). Se la JRE non è installata l'avvio fallisce
 *     e il gioco 3D gira lo stesso: il poll del bridge ritorna 0;
 *  2. crea il motore native sulla ANativeWindow della SurfaceView
 *     ([nativeCreate]: wgpu/Vulkan, mondo demo, mesh greedy + X-Ray di
 *     starling-features);
 *  3. chiama [nativeFrame] una volta per frame: poll del bridge a 8 bit
 *     (`nativePoll` via host C), tick 20 TPS, movimento/collisioni, draw,
 *     pacing 60 FPS. Niente JNI per pacchetto, una sola chiamata per frame.
 *
 * Tutte le chiamate native sono serializzate su [lock]: il render thread fa
 * frame, la UI thread fa touch/resize/jump/X-Ray. `surfaceDestroyed` ferma il
 * loop, attende il thread e rilascia la ANativeWindow (mai use-after-free).
 *
 * Controlli: stick a sinistra (movimento), drag a destra (camera), tap breve
 * sul mondo = rompi blocco, hold = piazza; pulsanti X-Ray / Salta / Esci.
 */
class GameActivity : Activity() {

    companion object {
        init {
            // Ordine richiesto: bridge Rust → host C++ → renderer Rust.
            // Il renderer risolve i simboli C dell'host (starling_jvm_poll_frame,
            // starling_jvm_set_action_callback) dalla libreria già caricata.
            System.loadLibrary("bridge_jni")
            System.loadLibrary("starling_jvm_host")
            System.loadLibrary("starling_render")
        }

        /** Crea motore + renderer sulla Surface della SurfaceView (0 = errore). */
        @JvmStatic
        external fun nativeCreate(surface: Surface, width: Int, height: Int): Long

        @JvmStatic
        external fun nativeResize(handle: Long, width: Int, height: Int)

        /** action: 0 = down, 1 = move, 2 = up/cancel. Coordinate in pixel. */
        @JvmStatic
        external fun nativeTouch(handle: Long, id: Int, action: Int, x: Float, y: Float)

        @JvmStatic
        external fun nativeJump(handle: Long)

        /** Ritorna l'avviso obbligatorio alla transizione X-Ray off→on. */
        @JvmStatic
        external fun nativeSetXray(handle: Long, on: Boolean): String?

        /** Un frame (poll bridge + update + draw + pacing). false = superficie persa. */
        @JvmStatic
        external fun nativeFrame(handle: Long): Boolean

        @JvmStatic
        external fun nativeDestroy(handle: Long)
    }

    /** Serializza tutte le chiamate native (render thread + UI thread). */
    private val lock = Any()
    private var handle = 0L
    /** Sessione di render corrente (sotto lock): invalida i thread superstiti. */
    private var session = 0
    private var renderThread: Thread? = null
    @Volatile private var running = false
    private var xrayOn = false
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Fullscreen immersivo (minSdk 33).
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        surfaceView = SurfaceView(this)
        val root = FrameLayout(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Overlay minimale: X-Ray (con avviso), salto, uscita.
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = getString(R.string.game_xray)
            setOnClickListener { toggleXray() }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.game_jump)
            setOnClickListener { synchronized(lock) { if (handle != 0L) nativeJump(handle) } }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.game_exit)
            setOnClickListener { finish() }
        })
        root.addView(
            buttons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        setContentView(root)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startRender(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(lock) { if (handle != 0L) nativeResize(handle, width, height) }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopRender()
            }
        })
        surfaceView.setOnTouchListener { _, event -> dispatchTouch(event); true }
    }

    override fun onDestroy() {
        // Rete di sicurezza: se la surface è già andata via, stopRender ha già
        // rilasciato; qui copriamo il caso di finish() senza surfaceDestroyed.
        stopRender()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Render thread
    // -----------------------------------------------------------------------

    private fun startRender(surface: Surface) {
        if (running) return
        running = true
        val mySession = synchronized(lock) { ++session }
        val w = surfaceView.width
        val h = surfaceView.height
        renderThread = Thread({
            // JVM headless per le mod (best-effort: senza JRE il gioco gira).
            startJvmEmbedded()

            val created = runCatching {
                synchronized(lock) { nativeCreate(surface, w, h) }
            }.getOrDefault(0L)
            if (created == 0L) {
                // Errore GPU: segnala solo se questa sessione è ancora corrente.
                if (synchronized(lock) { session == mySession }) {
                    running = false
                    runOnUiThread {
                        Toast.makeText(this, R.string.game_render_error, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                return@Thread
            }
            // Adotta l'handle solo se questa sessione è ancora quella corrente
            // (surfaceDestroyed può essere arrivato durante l'avvio JVM).
            val adopted = synchronized(lock) {
                if (session == mySession) {
                    handle = created
                    true
                } else {
                    false
                }
            }
            if (!adopted) {
                nativeDestroy(created)
                return@Thread
            }

            var ok = true
            while (running && ok) {
                ok = synchronized(lock) {
                    session == mySession && handle != 0L && nativeFrame(handle)
                }
            }

            val stillMine = synchronized(lock) { session == mySession }
            if (stillMine) {
                synchronized(lock) {
                    if (handle != 0L) {
                        nativeDestroy(handle)
                        handle = 0L
                    }
                }
                if (running) {
                    // Superficie persa / errore GPU: esce pulito.
                    runOnUiThread {
                        Toast.makeText(this, R.string.game_render_error, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }, "starling-render").apply { start() }
    }

    private fun stopRender() {
        running = false
        // Invalida la sessione: un thread ancora in avvio JVM non adotterà
        // mai il suo handle (lo rilascia da solo), né toccherà quello nuovo.
        synchronized(lock) { session++ }
        // Il loop controlla `running` a ogni frame (≤16 ms). Timeout 2 s per
        // non rischiare ANR se il thread è ancora nell'avvio JVM.
        renderThread?.join(2000)
        renderThread = null
        synchronized(lock) {
            if (handle != 0L) {
                nativeDestroy(handle)
                handle = 0L
            }
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    private fun dispatchTouch(ev: MotionEvent) {
        synchronized(lock) {
            if (handle == 0L) return
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until ev.pointerCount) {
                        nativeTouch(handle, ev.getPointerId(i), 1, ev.getX(i), ev.getY(i))
                    }
                }
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = ev.actionIndex
                    nativeTouch(handle, ev.getPointerId(i), 0, ev.getX(i), ev.getY(i))
                }
                else -> { // UP, POINTER_UP, CANCEL
                    val i = ev.actionIndex
                    nativeTouch(handle, ev.getPointerId(i), 2, ev.getX(i), ev.getY(i))
                }
            }
        }
    }

    private fun toggleXray() {
        xrayOn = !xrayOn
        val warning = synchronized(lock) {
            if (handle != 0L) nativeSetXray(handle, xrayOn) else null
        }
        // Avviso obbligatorio all'attivazione (solo singleplayer/server privati).
        if (warning != null) {
            Toast.makeText(this, warning, Toast.LENGTH_LONG).show()
        }
    }

    // -----------------------------------------------------------------------
    // JVM embedded (stessi percorsi di MainActivity/BridgeService)
    // -----------------------------------------------------------------------

    private fun startJvmEmbedded() {
        val ctx = applicationContext
        val base = ctx.getExternalFilesDir(null) ?: return
        val mods = File(base, "mods").apply { mkdirs() }
        val lut = File(base, "lut").apply { mkdirs() }
        val bridgeDir = File(ctx.filesDir, "bridge").apply { mkdirs() }

        val bridgeJar = File(bridgeDir, "bridge-u8.jar")
        if (!bridgeJar.exists()) {
            runCatching {
                ctx.assets.open("bridge-u8.jar").use { input ->
                    bridgeJar.outputStream().use { input.copyTo(it) }
                }
            }
        }
        val mapping = File(lut, "mapping.bin")
        if (!mapping.exists()) {
            runCatching {
                ctx.assets.open("mapping.bin").use { input ->
                    mapping.outputStream().use { input.copyTo(it) }
                }
            }
        }
        val jreLib = File(ctx.applicationInfo.nativeLibraryDir, "libjvm.so")
        runCatching {
            MainActivity.nativeStartGame(
                jreLib.absolutePath,
                bridgeJar.absolutePath,
                mods.absolutePath,
                lut.absolutePath,
                mapping.absolutePath,
                ctx.cacheDir.absolutePath
            )
        }
    }
}