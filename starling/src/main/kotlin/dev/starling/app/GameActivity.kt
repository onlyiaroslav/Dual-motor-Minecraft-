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
 * Game activity: SurfaceView + voxel Rust renderer (libstarling_render).
 *
 * Tapping Play opens this Activity. A dedicated render thread:
 *  1. starts the embedded headless JVM ([MainActivity.nativeStartGame], same
 *     paths as BridgeService). If the JRE is not installed the startup fails and
 *     the 3D game still runs: the bridge poll returns 0;
 *  2. creates the native engine on the SurfaceView's ANativeWindow
 *     ([nativeCreate]: wgpu/Vulkan, demo world, greedy mesh + X-Ray from
 *     starling-features);
 *  3. calls [nativeFrame] once per frame: bridge poll at 8-bit
 *     (`nativePoll` via host C), tick 20 TPS, movement/collisions, draw,
 *     pacing 60 FPS. No JNI per packet, one call per frame.
 *
 * All native calls are serialized on [lock]: the render thread does frames,
 * the UI thread does touch/resize/jump/X-Ray. `surfaceDestroyed` stops the
 * loop, waits for the thread and releases the ANativeWindow (never use-after-free).
 *
 * Controls: stick to the left (movement), drag to the right (camera), short tap
 * on the world = break block, hold = place; X-Ray / Jump / Exit buttons.
 */
class GameActivity : Activity() {

    companion object {
        init {
            // Required order: bridge Rust → host C++ → renderer Rust.
            // The renderer resolves the C symbols of the host (starling_jvm_poll_frame,
            // starling_jvm_set_action_callback) from the already loaded library.
            System.loadLibrary("bridge_jni")
            System.loadLibrary("starling_jvm_host")
            System.loadLibrary("starling_render")
        }

        /** Creates the engine + renderer on the SurfaceView surface (0 = error). */
        @JvmStatic
        external fun nativeCreate(surface: Surface, width: Int, height: Int): Long

        @JvmStatic
        external fun nativeResize(handle: Long, width: Int, height: Int)

        /** action: 0 = down, 1 = move, 2 = up/cancel. Coordinate in pixel. */
        @JvmStatic
        external fun nativeTouch(handle: Long, id: Int, action: Int, x: Float, y: Float)

        @JvmStatic
        external fun nativeJump(handle: Long)

        /** Returns the mandatory warning for the X-Ray off→on transition. */
        @JvmStatic
        external fun nativeSetXray(handle: Long, on: Boolean): String?

        /** One frame (bridge poll + update + draw + pacing). false = surface lost. */
        @JvmStatic
        external fun nativeFrame(handle: Long): Boolean

        @JvmStatic
        external fun nativeDestroy(handle: Long)
    }

    /** Serializza tutte le chiamate native (render thread + UI thread). */
    private val lock = Any()
    private var handle = 0L
    /** Current render session (under lock): invalidates the stale threads. */
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

        // Minimal overlay: X-Ray (with warning), jump, exit.
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
        // Safety net: if the surface already went away, stopRender already
        // released; here we cover the finish() without surfaceDestroyed case.
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
            // Headless JVM for mods (best-effort: without JRE the game runs).
            startJvmEmbedded()

            val created = runCatching {
                synchronized(lock) { nativeCreate(surface, w, h) }
            }.getOrDefault(0L)
            if (created == 0L) {
                // Error: GPU — report only if this session is still current.
                if (synchronized(lock) { session == mySession }) {
                    running = false
                    runOnUiThread {
                        Toast.makeText(this, R.string.game_render_error, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                return@Thread
            }
            // Adopt the handle only if this session is still the current one
            // (surfaceDestroyed may arrive during JVM startup).
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
                    // Surface lost / GPU error: exit cleanly.
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
        // Invalidate the session: a thread still starting up will not
        // adopt it (it releases it on its own), nor touch the new one.
        synchronized(lock) { session++ }
        // The loop checks `running` at each frame (≤16 ms). Timeout 2 s to
        // avoid an ANR if the thread is still starting the JVM.
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
        // Mandatory warning on activation (singleplayer/private servers only).
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