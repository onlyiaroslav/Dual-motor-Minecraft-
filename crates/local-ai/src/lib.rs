//! # local-ai
//!
//! Fase 7: motore AI locale per la conversione offline dei mod.
//!
//! - `backend`: interfaccia `AiBackend` + implementazione [`HttpAiBackend`],
//!   un **client HTTP** verso un server OpenAI-compatible in ascolto su
//!   `127.0.0.1` (llama-server di llama.cpp, LM Studio, Ollama, ...). Niente
//!   FFI, niente compilazione C++: il modello gira in un processo separato.
//! - `engine`: orchestrazione del toggle, gestione di `Ready` / `NotReady` /
//!   `Disarmed`, timeout, fallback e job in background.
//!
//! Vincolo Fase 7: l'AI vede **solo** firme di metodi (`java_method_signature`)
//! e restituisce una `NativeAction` della fase di bridge, per costruire il
//! vocabolario di mapping `mapping.bin` senza mai copiare codice.

#![deny(unsafe_code)]
#![warn(missing_docs)]

pub mod backend;
pub mod engine;

pub use backend::{
    AiBackend, AiBackendError, HttpAiBackend, HttpTransport, TransportError, DEFAULT_ENDPOINT,
    INFERENCE_TIMEOUT_SECS,
};
pub use engine::{
    EngineState, LocalAiConfig, LocalAiEngine, LocalAiError, TranslationOutcome, TranslationSource,
};
