//! Export JNI per la schermata "Impostazioni" del launcher Kotlin (Fase 7).
//!
//! Attivato dalla feature `jni`; produce `libmod_converter.so` **separata** da
//! `bridge-jni` e da `libstarling_social.so`. Gli handle sono ID opachi in una
//! registry globale: nessun puntatore raw attraversa il confine JNI.
//!
//! Espone il toggle "AI Locale per Conversione Mod" (`nativeSetAiEnabled`) e la
//! conversione offline di un JAR in `mapping.bin` + `conversion_log.json`.
//! Tutto resta sul dispositivo: nessun upload, zero codice copiato.

// Solo il confine FFI richiede `#[no_mangle]` (attributo unsafe); il resto del
// crate resta sotto `deny(unsafe_code)`.
#![allow(unsafe_code)]

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;

use bridge_u8::NativeAction;
use local_ai::{EngineState, LocalAiConfig, LocalAiEngine};

use crate::{
    convert_jar, load_library, write_conversion_log, write_mapping_bin, ConverterError,
};

/// Stato per istanza della UI: motore AI + libreria di mapping esistente.
struct ConverterHandle {
    engine: LocalAiEngine,
    library: Vec<(u16, NativeAction)>,
}

fn registry() -> &'static Mutex<HashMap<i64, ConverterHandle>> {
    static REG: OnceLock<Mutex<HashMap<i64, ConverterHandle>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> i64 {
    static NEXT: AtomicI64 = AtomicI64::new(1);
    NEXT.fetch_add(1, Ordering::SeqCst)
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
}

fn with_handle<R>(
    handle: i64,
    f: impl FnOnce(&mut ConverterHandle) -> Result<R, ConverterError>,
) -> Result<R, ConverterError> {
    if handle == 0 {
        return Err(ConverterError::Io(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "handle nullo",
        )));
    }
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    match reg.get_mut(&handle) {
        Some(h) => f(h),
        None => Err(ConverterError::Io(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "handle non valido o già liberato",
        ))),
    }
}

macro_rules! guarded {
    ($env:expr, $default:expr, $body:expr) => {
        match catch_unwind(AssertUnwindSafe(|| $body)) {
            Ok(v) => v,
            Err(_) => {
                throw(&mut $env, "panic interno nel convertitore mod");
                $default
            }
        }
    };
}

fn new_string(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|v| v.into())
        .map_err(|e| format!("stringa non valida: {e}"))
}

fn state_str(state: EngineState) -> &'static str {
    match state {
        EngineState::Ready => "ready",
        EngineState::NotReady => "not_ready",
        EngineState::Disarmed => "disarmed",
    }
}

// --- Export ---------------------------------------------------------------

/// Crea un convertitore. `endpoint` è l'URL del server AI locale
/// OpenAI-compatible (es. `http://127.0.0.1:8080/v1/chat/completions`);
/// vuoto o non-URL = endpoint predefinito. `libraryPath` è il `mapping.bin`
/// esistente (vuoto = nessuna libreria). L'AI parte **disattivata** (default
/// privacy): la UI la attiva tramite `nativeSetAiEnabled` in base alla
/// preferenza salvata.
#[no_mangle]
pub extern "system" fn Java_dev_starling_converter_AiConverterBridge_nativeCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    endpoint: JString<'local>,
    library_path: JString<'local>,
) -> jlong {
    guarded!(env, 0, {
        let endpoint: String = match get_string(&mut env, &endpoint) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e);
                return 0;
            }
        };
        let lib_path: String = match get_string(&mut env, &library_path) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e);
                return 0;
            }
        };

        let mut config = LocalAiConfig::default();
        let trimmed = endpoint.trim();
        if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
            config.endpoint = trimmed.to_string();
        }
        let engine = LocalAiEngine::new(config);
        engine.disarm("default off");

        let library = if !lib_path.trim().is_empty() && std::path::Path::new(&lib_path).exists() {
            match load_library(&lib_path) {
                Ok(l) => l,
                Err(e) => {
                    throw(&mut env, &e.to_string());
                    return 0;
                }
            }
        } else {
            Vec::new()
        };

        let handle = next_handle();
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(handle, ConverterHandle { engine, library });
        handle
    })
}

/// Toggle "AI Locale per Conversione Mod". `true` = arma (carica il modello),
/// `false` = disarma (solo libreria, API sconosciute scartate). Ritorna lo stato
/// risultante: `ready` / `not_ready` / `disarmed`.
#[no_mangle]
pub extern "system" fn Java_dev_starling_converter_AiConverterBridge_nativeSetAiEnabled<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    enabled: jboolean,
) -> jstring {
    guarded!(env, std::ptr::null_mut(), {
        let state = match with_handle(handle, |h| {
            if enabled != 0 {
                // arm() imposta Ready se il server locale risponde, altrimenti NotReady.
                let _ = h.engine.arm();
            } else {
                h.engine.disarm("toggle off");
            }
            Ok(h.engine.state())
        }) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e.to_string());
                return std::ptr::null_mut();
            }
        };
        new_string(&mut env, state_str(state))
    })
}

/// Stato corrente del motore AI: `ready` / `not_ready` / `disarmed`.
#[no_mangle]
pub extern "system" fn Java_dev_starling_converter_AiConverterBridge_nativeAiState<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jstring {
    guarded!(env, std::ptr::null_mut(), {
        let state = match with_handle(handle, |h| Ok(h.engine.state())) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e.to_string());
                return std::ptr::null_mut();
            }
        };
        new_string(&mut env, state_str(state))
    })
}

/// Traduce una singola firma Java in un'azione nativa. Ritorna l'`action_id`
/// (0..=11) oppure `-1` se nessuna azione (AI disattivata/assente/fallback).
#[no_mangle]
pub extern "system" fn Java_dev_starling_converter_AiConverterBridge_nativeTranslateFragment<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    signature: JString<'local>,
) -> jint {
    guarded!(env, -1, {
        let sig: String = match get_string(&mut env, &signature) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e);
                return -1;
            }
        };
        match with_handle(handle, |h| Ok(h.engine.translate_fragment(&sig))) {
            Ok(Some(action)) => action.as_u16() as jint,
            Ok(None) => -1,
            Err(e) => {
                throw(&mut env, &e.to_string());
                -1
            }
        }
    })
}

/// Converte un JAR e scrive `mapping.bin` + `conversion_log.json` in `outDir`.
/// Ritorna un riepilogo `mapped=N ai=X library=Y discarded=Z ai_ready=B`.
#[no_mangle]
pub extern "system" fn Java_dev_starling_converter_AiConverterBridge_nativeConvertJar<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    jar_path: JString<'local>,
    out_dir: JString<'local>,
) -> jstring {
    guarded!(env, std::ptr::null_mut(), {
        let jar: String = match get_string(&mut env, &jar_path) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e);
                return std::ptr::null_mut();
            }
        };
        let out: String = match get_string(&mut env, &out_dir) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e);
                return std::ptr::null_mut();
            }
        };
        let summary = match with_handle(handle, |h| {
            let bytes = std::fs::read(&jar)?;
            let report = convert_jar(&bytes, &h.engine, &h.library)?;
            let out_dir = PathBuf::from(&out);
            std::fs::create_dir_all(&out_dir)?;
            write_mapping_bin(&report, out_dir.join("mapping.bin"))?;
            write_conversion_log(&report, out_dir.join("conversion_log.json"))?;
            Ok(format!(
                "mapped={} ai={} library={} discarded={} ai_ready={}",
                report.entries.len(),
                report.ai_used,
                report.library_used,
                report.discarded,
                report.ai_was_ready
            ))
        }) {
            Ok(s) => s,
            Err(e) => {
                throw(&mut env, &e.to_string());
                return std::ptr::null_mut();
            }
        };
        new_string(&mut env, &summary)
    })
}

/// Libera l'handle del convertitore (rilascia motore AI e libreria).
#[no_mangle]
pub extern "system" fn Java_dev_starling_converter_AiConverterBridge_nativeFree<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    if handle != 0 {
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle);
    }
}
