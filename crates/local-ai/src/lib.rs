//! # local-ai
//!
//! Fase 7: motore AI locale per la conversione offline dei mod.
//!
//! - `backend`: interfaccia `AiBackend` + implementazione `LlamaCppBackend`
//!   (wrapper del runtime llama.cpp per SmolLM2, GGUF Q4_K_M).
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

pub use backend::{AiBackend, AiBackendError, LlamaCppBackend};
pub use engine::{
    EngineState, LocalAiConfig, LocalAiEngine, LocalAiError, TranslationOutcome, TranslationSource,
};
