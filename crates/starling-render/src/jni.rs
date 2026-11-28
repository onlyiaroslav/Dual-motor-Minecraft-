//! Entry point JNI per `dev.starling.app.GameActivity` (SurfaceView).
//!
//! Flusso (docs/STARLING_INTEGRATION.md §3):
//! - `nativeCreate(surface, w, h)`: device Vulkan sulla ANativeWindow +
//!   renderer + mondo demo; registra la callback azioni del bridge.
//! - `nativeFrame()`: 1×/frame dal render thread Kotlin — poll del bridge
//!   (`nativePoll` via host C), update del gioco (tick 20 TPS), draw, pacing.
//! - `nativeTouch(id, action, x, y)`: eventi `MotionEvent` (multi-touch).
//! - `nativeSetXray(on)`: toggle X-Ray; ritorna l'avviso obbligatorio off→on.
//! - `nativeDestroy()`: rilascia tutto (ANativeWindow inclusa).
//!
//! Ordine di caricamento librerie (companion di GameActivity):
//! `bridge_jni` → `starling_jvm_host` → `starling_render`.

use std::time::{Duration, Instant};

use jni::objects::{JClass, JObject};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::game::{pace_frame, Game};
use crate::renderer::Renderer;
use crate::surface::GpuSurface;
use crate::touch::Phase;

/// Budget di frame: 60 FPS, come `bridge-jni/examples/game_loop.rs`.
const FRAME_BUDGET: Duration = Duration::from_micros(16_667);

/// Stato per istanza di gioco (handle opaco restituito a Kotlin).
struct Engine {
    gpu: GpuSurface,
    renderer: Renderer,
    game: Game,
    last: Instant,
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

/// `GameActivity.nativeCreate(Surface, int, int) -> long` (0 = errore).
#[no_mangle]
pub extern "system" fn Java_dev_starling_app_GameActivity_nativeCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    surface: JObject<'local>,
    width: jint,
    height: jint,
) -> jlong {
    let (w, h) = (width.max(1) as u32, height.max(1) as u32);
    // Logcat (tag starling-render): init_once è idempotente tra create/destroy.
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("starling-render"),
    );
    let gpu = match GpuSurface::new(&env, &surface, w, h) {
        Ok(g) => g,
        Err(e) => {
            throw(&mut env, &e);
            return 0;
        }
    };
    let renderer = Renderer::new(&gpu);
    let game = Game::new(w, h);
    // Callback azioni del bridge (EngineSink → C → coda del game loop).
    crate::bridge::install();
    let engine = Engine { gpu, renderer, game, last: Instant::now() };
    Box::into_raw(Box::new(engine)) as jlong
}

/// `GameActivity.nativeResize(long, int, int)` — surfaceChanged.
#[no_mangle]
pub extern "system" fn Java_dev_starling_app_GameActivity_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) {
    if handle == 0 || width <= 0 || height <= 0 {
        return;
    }
    // SAFETY: handle creato da nativeCreate, non ancora destroyato (contratto Kotlin).
    let e = unsafe { &mut *(handle as *mut Engine) };
    let (w, h) = (width as u32, height as u32);
    e.gpu.resize(w, h);
    e.renderer.resize(&e.gpu);
    e.game.resize(w, h);
}

/// `GameActivity.nativeTouch(long, int id, int action, float x, float y)`.
/// action: 0 = down, 1 = move, 2 = up/cancel (mappato da Kotlin).
#[no_mangle]
pub extern "system" fn Java_dev_starling_app_GameActivity_nativeTouch(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jint,
    action: jint,
    x: jfloat,
    y: jfloat,
) {
    if handle == 0 {
        return;
    }
    let phase = match action {
        0 => Phase::Down,
        1 => Phase::Move,
        _ => Phase::Up,
    };
    // SAFETY: handle valido per contratto Kotlin.
    let e = unsafe { &mut *(handle as *mut Engine) };
    e.game.touch(id, phase, x, y);
}

/// `GameActivity.nativeJump(long)` — pulsante di salto a schermo.
#[no_mangle]
pub extern "system" fn Java_dev_starling_app_GameActivity_nativeJump(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: handle valido per contratto Kotlin.
        let e = unsafe { &mut *(handle as *mut Engine) };
        e.game.jump();
    }
}

/// `GameActivity.nativeSetXray(long, boolean) -> String` — l'avviso
/// obbligatorio alla transizione off→on (null altrimenti).
#[no_mangle]
pub extern "system" fn Java_dev_starling_app_GameActivity_nativeSetXray<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    on: jboolean,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: handle valido per contratto Kotlin.
    let e = unsafe { &mut *(handle as *mut Engine) };
    match e.game.set_xray(on == JNI_TRUE) {
        Some(warning) => env
            .new_string(warning)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// `GameActivity.nativeFrame(long) -> boolean` — un frame completo.
/// false = superficie persa/errore: Kotlin ferma il loop.
#[no_mangle]
pub extern "system" fn Java_dev_starling_app_GameActivity_nativeFrame(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }
    // SAFETY: handle valido per contratto Kotlin; un solo thread (il render
    // thread di GameActivity) chiama frame/resize/touch/destroy.
    let e = unsafe { &mut *(handle as *mut Engine) };
    let start = Instant::now();
    let dt = start.duration_since(e.last);
    e.last = start;

    e.game.update(dt);
    let frame = match e.gpu.frame() {
        Ok(f) => f,
        Err(err) => {
            log::warn!("starling-render: {err}");
            return JNI_FALSE;
        }
    };
    e.renderer.draw(&e.gpu, &e.game, frame);
    pace_frame(start, FRAME_BUDGET);
    JNI_TRUE
}

/// `GameActivity.nativeDestroy(long)` — rilascia motore, renderer e superficie.
#[no_mangle]
pub extern "system" fn Java_dev_starling_app_GameActivity_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: handle creato da nativeCreate; dopo destroy Kotlin non lo usa più.
        unsafe { drop(Box::from_raw(handle as *mut Engine)) };
    }
}