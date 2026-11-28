//! Connessione al bridge a 8 bit (`crates/bridge-jni`, `nativePoll`).
//!
//! Il render loop chiama `starling_jvm_poll_frame()` (C ABI di
//! `starling/native/starling_jvm_host.cpp`) una volta per frame: l'host fa
//! `NativeTransport.nativePoll(handle)` sulla JVM embedded, la LUT Rust
//! dispatcha e il sink (`EngineSink`) inoltra gli ID azione alla callback
//! registrata con `starling_jvm_set_action_callback`. È il flusso documentato
//! in docs/STARLING_INTEGRATION.md §3: niente JNI dal lato Kotlin per frame.
//!
//! Ordine di caricamento richiesto (GameActivity): `bridge_jni`,
//! `starling_jvm_host`, `starling_render` — i simboli C dell'host sono risolti
//! dalla libreria già caricata nello stesso namespace.
//!
//! Su host (non Android) il bridge non c'è: stub innocui, il gioco gira lo
//! stesso (è anche la modalità senza JRE installata).

/// Azioni risolte dalla LUT, in attesa di essere consumate dal game loop.
static ACTIONS: std::sync::Mutex<Vec<i32>> = std::sync::Mutex::new(Vec::new());

#[cfg(target_os = "android")]
mod sys {
    extern "C" {
        /// 1×/frame dal render thread: byte consumati, 0 = nulla, -1 = errore.
        pub fn starling_jvm_poll_frame() -> i32;
        /// Callback del motore per gli ID azione del sink (JVM embedded).
        pub fn starling_jvm_set_action_callback(
            cb: extern "C" fn(ids: *const i32, count: usize),
        );
    }

    extern "C" fn on_actions(ids: *const i32, count: usize) {
        if ids.is_null() || count == 0 {
            return;
        }
        // SAFETY: l'host passa `count` elementi validi da GetIntArrayElements
        // per la durata della chiamata (starling_jvm_host.cpp, EngineSink).
        let slice = unsafe { std::slice::from_raw_parts(ids, count) };
        if let Ok(mut q) = super::ACTIONS.lock() {
            q.extend_from_slice(slice);
        }
    }

    /// Registra la callback azioni (una volta, alla creazione del motore).
    pub fn install() {
        // SAFETY: funzione C dell'host, stesso processo, libreria caricata prima.
        unsafe { starling_jvm_set_action_callback(on_actions) };
    }

    /// Poll del ring per questo frame (0 se la JVM non è avviata).
    pub fn poll() -> i32 {
        // SAFETY: funzione C dell'host; attach del thread gestito lì.
        unsafe { starling_jvm_poll_frame() }
    }
}

#[cfg(not(target_os = "android"))]
mod sys {
    pub fn install() {}
    pub fn poll() -> i32 {
        0
    }
}

pub use sys::{install, poll};

/// Consuma gli ID azione arrivati dal bridge dall'ultimo frame.
pub fn take_actions() -> Vec<i32> {
    match ACTIONS.lock() {
        Ok(mut q) => std::mem::take(&mut *q),
        Err(_) => Vec::new(),
    }
}