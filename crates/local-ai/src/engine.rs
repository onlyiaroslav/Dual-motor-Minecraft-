//! Orchestrazione del motore AI locale: stato del toggle, timeout, fallback.
//!
//! Logica del toggle (Fase 7):
//! - **ATTIVO** (`arm`): il modello viene caricato; se presente lo stato è
//!   [`EngineState::Ready`] e le firme sconosciute vengono tradotte da SmolLM2.
//! - **DISATTIVO** (`disarm`): lo stato è [`EngineState::Disarmed`] e l'AI
//!   **non viene mai interrogata**; il convertitore scarta l'API o usa solo la
//!   libreria esistente (`mapping.bin`), loggando "AI disattivata".
//! - **Fallback**: modello assente o timeout ⇒ ci si comporta come se il toggle
//!   fosse disattivato ([`TranslationSource::Fallback`]).

use std::fmt;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Instant;

use crate::backend::{
    AiBackend, AiBackendError, EmbeddedHttpBackend, EmbeddedServerConfig, HttpAiBackend,
    DEFAULT_ENDPOINT,
};
use bridge_u8::NativeAction;

/// Configurazione del motore AI locale.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LocalAiConfig {
    /// Endpoint OpenAI-compatible del server locale
    /// (default: [`DEFAULT_ENDPOINT`]).
    pub endpoint: String,
    /// Contesto del modello (token). Informativo: il server usa la propria
    /// configurazione; il valore è mantenuto per compatibilità e diagnostica.
    pub n_ctx: u32,
    /// Timeout in ms per singola inferenza.
    pub timeout_ms: u64,
}

impl Default for LocalAiConfig {
    fn default() -> Self {
        Self {
            endpoint: DEFAULT_ENDPOINT.to_string(),
            n_ctx: 512,
            timeout_ms: 5000,
        }
    }
}

/// Stato del motore AI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EngineState {
    /// AI abilitata e server locale raggiungibile.
    Ready,
    /// AI abilitata ma server locale assente / non pronto.
    NotReady,
    /// AI disattivata dall'utente (fallback senza AI).
    Disarmed,
}

/// Origine della decisione di conversione.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TranslationSource {
    /// Decisione prodotta dal modello locale.
    SmolLm2,
    /// AI assente / timeout: fallback deliberato (come toggle disattivato).
    Fallback,
    /// AI disattivata dall'utente: scarto della firma/azione.
    Disabled,
}

/// Esito di una traduzione AI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslationOutcome {
    /// Azione nativa proposta, oppure `None` (fallback / scarto).
    pub native_action: Option<NativeAction>,
    /// Origine della decisione.
    pub source: TranslationSource,
    /// Durata dell'inferenza in ms, se eseguita.
    pub took_ms: Option<u64>,
}

impl TranslationOutcome {
    /// Esito senza azione (fallback / disattivato).
    pub fn fallback(source: TranslationSource) -> Self {
        Self {
            native_action: None,
            source,
            took_ms: None,
        }
    }

    /// `true` se non è stata prodotta alcuna azione nativa.
    pub fn is_fallback(&self) -> bool {
        self.native_action.is_none()
    }
}

/// Errore del motore AI locale.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LocalAiError {
    /// Motivazione leggibile dell'errore.
    pub reason: String,
}

impl fmt::Display for LocalAiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "errore AI locale: {}", self.reason)
    }
}

impl std::error::Error for LocalAiError {}

/// Motore AI locale con toggle (Fase 7).
pub struct LocalAiEngine {
    config: LocalAiConfig,
    state: Arc<Mutex<EngineState>>,
    backend: Arc<Mutex<dyn AiBackend + Send + Sync>>,
}

impl LocalAiEngine {
    /// Crea un motore con backend HTTP reale. Lo stato iniziale è `NotReady`:
    /// `arm()` esegue l'health check del server locale e imposta `Ready` se
    /// raggiungibile.
    pub fn new(config: LocalAiConfig) -> Self {
        let endpoint = config.endpoint.clone();
        Self {
            config,
            state: Arc::new(Mutex::new(EngineState::NotReady)),
            backend: Arc::new(Mutex::new(HttpAiBackend::new(endpoint))),
        }
    }

    /// Crea un motore con backend **embedded**: avvia automaticamente un
    /// server AI locale come processo figlio se non è già in ascolto.
    /// Usato per il "tutto in locale, zero configurazione utente".
    pub fn with_embedded(
        config: LocalAiConfig,
        embedded: EmbeddedServerConfig,
    ) -> Self {
        let endpoint = config.endpoint.clone();
        Self {
            config,
            state: Arc::new(Mutex::new(EngineState::NotReady)),
            backend: Arc::new(Mutex::new(EmbeddedHttpBackend::new(embedded))),
        }
    }

    /// Crea un motore con un backend iniettivo (usato nei test).
    pub fn with_backend(
        config: LocalAiConfig,
        backend: Arc<Mutex<dyn AiBackend + Send + Sync>>,
    ) -> Self {
        Self {
            config,
            state: Arc::new(Mutex::new(EngineState::NotReady)),
            backend,
        }
    }

    /// Configurazione attiva (contesto / timeout).
    pub fn config(&self) -> &LocalAiConfig {
        &self.config
    }

    /// Attiva il motore (toggle ON): carica il backend e il modello.
    /// Imposta `Ready` se il caricamento riesce, altrimenti `NotReady`.
    pub fn arm(&self) -> Result<(), LocalAiError> {
        let mut b = self.backend.lock().unwrap_or_else(|e| e.into_inner());
        match b.load() {
            Ok(()) => {
                *self.state.lock().unwrap_or_else(|e| e.into_inner()) = EngineState::Ready;
                Ok(())
            }
            Err(e) => {
                *self.state.lock().unwrap_or_else(|e| e.into_inner()) = EngineState::NotReady;
                Err(LocalAiError {
                    reason: e.to_string(),
                })
            }
        }
    }

    /// Disattiva l'AI (toggle OFF): da questo momento l'AI non è più interrogata.
    pub fn disarm(&self, reason: impl Into<String>) {
        let _ = reason;
        *self.state.lock().unwrap_or_else(|e| e.into_inner()) = EngineState::Disarmed;
    }

    /// Stato corrente del motore.
    pub fn state(&self) -> EngineState {
        *self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// `true` se il modello è caricato e pronto a tradurre.
    pub fn is_available(&self) -> bool {
        self.state() == EngineState::Ready
    }

    /// API richiesta dalla Fase 7: traduce una firma Java in un'azione nativa.
    /// Restituisce `None` se l'AI è disattivata, assente, in timeout o non
    /// propone alcuna azione (fallback).
    pub fn translate_fragment(&self, java_method_signature: &str) -> Option<NativeAction> {
        self.translate(java_method_signature).native_action
    }

    /// Traduzione completa con metadati (origine, durata).
    ///
    /// Se lo stato è `Disarmed` o `NotReady` l'AI **non viene interrogata**.
    /// Se l'inferenza supera `config.timeout_ms` l'esito è un fallback.
    pub fn translate(&self, java_method_signature: &str) -> TranslationOutcome {
        let state = self.state();
        match state {
            EngineState::Disarmed => TranslationOutcome::fallback(TranslationSource::Disabled),
            EngineState::NotReady => TranslationOutcome::fallback(TranslationSource::Fallback),
            EngineState::Ready => self.translate_ready(java_method_signature),
        }
    }

    /// Inferenza vera e propria (solo quando lo stato è `Ready`).
    fn translate_ready(&self, java_method_signature: &str) -> TranslationOutcome {
        let backend = self.backend.lock().unwrap_or_else(|e| e.into_inner());
        let started = Instant::now();
        let result = backend.translate_fragment(java_method_signature);
        let took_ms = started.elapsed().as_millis() as u64;

        // Timeout: ci si comporta come se il toggle fosse disattivato.
        if took_ms > self.config.timeout_ms {
            return TranslationOutcome {
                native_action: None,
                source: TranslationSource::Fallback,
                took_ms: Some(took_ms),
            };
        }

        match result {
            Ok(Some(action)) => TranslationOutcome {
                native_action: Some(action),
                source: TranslationSource::SmolLm2,
                took_ms: Some(took_ms),
            },
            Ok(None) => TranslationOutcome {
                native_action: None,
                source: TranslationSource::Fallback,
                took_ms: Some(took_ms),
            },
            Err(AiBackendError::Timeout) => TranslationOutcome {
                native_action: None,
                source: TranslationSource::Fallback,
                took_ms: Some(took_ms),
            },
            Err(_) => TranslationOutcome {
                native_action: None,
                source: TranslationSource::Fallback,
                took_ms: Some(took_ms),
            },
        }
    }

    /// Traduzione su thread dedicato (la UI non blocca il gameplay).
    pub fn translate_in_background(
        &self,
        java_method_signature: &str,
    ) -> thread::JoinHandle<TranslationOutcome> {
        let backend = Arc::clone(&self.backend);
        let state = Arc::clone(&self.state);
        let timeout_ms = self.config.timeout_ms;
        let signature = java_method_signature.to_string();
        thread::spawn(move || {
            let current = *state.lock().unwrap_or_else(|e| e.into_inner());
            match current {
                EngineState::Disarmed => {
                    return TranslationOutcome::fallback(TranslationSource::Disabled);
                }
                EngineState::NotReady => {
                    return TranslationOutcome::fallback(TranslationSource::Fallback);
                }
                EngineState::Ready => {}
            }
            let backend = backend.lock().unwrap_or_else(|e| e.into_inner());
            let started = Instant::now();
            let result = backend.translate_fragment(&signature);
            let took_ms = started.elapsed().as_millis() as u64;
            if took_ms > timeout_ms {
                return TranslationOutcome {
                    native_action: None,
                    source: TranslationSource::Fallback,
                    took_ms: Some(took_ms),
                };
            }
            match result {
                Ok(Some(action)) => TranslationOutcome {
                    native_action: Some(action),
                    source: TranslationSource::SmolLm2,
                    took_ms: Some(took_ms),
                },
                _ => TranslationOutcome {
                    native_action: None,
                    source: TranslationSource::Fallback,
                    took_ms: Some(took_ms),
                },
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    /// Backend fittizio per testare l'orchestrazione senza server HTTP.
    struct MockBackend {
        load_ok: bool,
        action: Option<NativeAction>,
        err: Option<AiBackendError>,
        delay_ms: u64,
        calls: Arc<AtomicUsize>,
    }

    impl MockBackend {
        fn new() -> Self {
            Self {
                load_ok: true,
                action: None,
                err: None,
                delay_ms: 0,
                calls: Arc::new(AtomicUsize::new(0)),
            }
        }
    }

    impl AiBackend for MockBackend {
        fn load(&mut self) -> Result<(), AiBackendError> {
            if self.load_ok {
                Ok(())
            } else {
                Err(AiBackendError::ModelNotAvailable)
            }
        }
        fn translate_fragment(
            &self,
            _sig: &str,
        ) -> Result<Option<NativeAction>, AiBackendError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.delay_ms > 0 {
                std::thread::sleep(Duration::from_millis(self.delay_ms));
            }
            if let Some(e) = &self.err {
                return Err(e.clone());
            }
            Ok(self.action)
        }
    }

    fn config() -> LocalAiConfig {
        LocalAiConfig {
            endpoint: "http://127.0.0.1:1/v1/chat/completions".to_string(),
            n_ctx: 512,
            timeout_ms: 2000,
        }
    }

    fn engine_with(mock: MockBackend) -> (LocalAiEngine, Arc<AtomicUsize>) {
        let calls = Arc::clone(&mock.calls);
        let engine = LocalAiEngine::with_backend(config(), Arc::new(Mutex::new(mock)));
        (engine, calls)
    }

    #[test]
    fn new_parte_not_ready() {
        let engine = LocalAiEngine::new(config());
        assert_eq!(engine.state(), EngineState::NotReady);
        assert!(!engine.is_available());
    }

    #[test]
    fn arm_carica_e_diventa_ready() {
        let (engine, _calls) = engine_with(MockBackend::new());
        assert!(engine.arm().is_ok());
        assert_eq!(engine.state(), EngineState::Ready);
        assert!(engine.is_available());
    }

    #[test]
    fn arm_fallito_resta_not_ready() {
        let mut mock = MockBackend::new();
        mock.load_ok = false;
        let (engine, _calls) = engine_with(mock);
        assert!(engine.arm().is_err());
        assert_eq!(engine.state(), EngineState::NotReady);
    }

    #[test]
    fn disarm_disattiva_e_non_chiama_ai() {
        let mut mock = MockBackend::new();
        mock.action = Some(NativeAction::NativeTick);
        let (engine, calls) = engine_with(mock);
        engine.arm().unwrap();
        engine.disarm("toggle off");
        assert_eq!(engine.state(), EngineState::Disarmed);

        let out = engine.translate("com.example.Foo.onTick()V");
        assert_eq!(out.source, TranslationSource::Disabled);
        assert!(out.native_action.is_none());
        // L'AI non deve essere stata interrogata.
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn not_ready_non_chiama_ai() {
        let mut mock = MockBackend::new();
        mock.action = Some(NativeAction::NativeTick);
        let (engine, calls) = engine_with(mock);
        // Non chiamiamo arm(): stato NotReady.
        let out = engine.translate("com.example.Foo.onTick()V");
        assert_eq!(out.source, TranslationSource::Fallback);
        assert!(out.native_action.is_none());
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn ready_traduce_con_smollm2() {
        let mut mock = MockBackend::new();
        mock.action = Some(NativeAction::SpawnParticle);
        let (engine, calls) = engine_with(mock);
        engine.arm().unwrap();
        let out = engine.translate("com.example.Foo.spawn()V");
        assert_eq!(out.source, TranslationSource::SmolLm2);
        assert_eq!(out.native_action, Some(NativeAction::SpawnParticle));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn translate_fragment_ritorna_option() {
        let mut mock = MockBackend::new();
        mock.action = Some(NativeAction::SendChat);
        let (engine, _calls) = engine_with(mock);
        engine.arm().unwrap();
        assert_eq!(
            engine.translate_fragment("com.example.Foo.chat()V"),
            Some(NativeAction::SendChat)
        );
        engine.disarm("off");
        assert_eq!(engine.translate_fragment("com.example.Foo.chat()V"), None);
    }

    #[test]
    fn ready_ma_nessuna_azione_e_fallback() {
        let mut mock = MockBackend::new();
        mock.action = None;
        let (engine, _calls) = engine_with(mock);
        engine.arm().unwrap();
        let out = engine.translate("com.example.Foo.unknown()V");
        assert_eq!(out.source, TranslationSource::Fallback);
        assert!(out.is_fallback());
    }

    #[test]
    fn errore_inferenza_e_fallback() {
        let mut mock = MockBackend::new();
        mock.err = Some(AiBackendError::Inference);
        let (engine, _calls) = engine_with(mock);
        engine.arm().unwrap();
        let out = engine.translate("com.example.Foo.x()V");
        assert_eq!(out.source, TranslationSource::Fallback);
        assert!(out.native_action.is_none());
    }

    #[test]
    fn timeout_e_fallback() {
        let mut mock = MockBackend::new();
        mock.action = Some(NativeAction::NativeTick);
        mock.delay_ms = 60;
        let mut cfg = config();
        cfg.timeout_ms = 10; // timeout molto basso
        let calls = Arc::clone(&mock.calls);
        let engine = LocalAiEngine::with_backend(cfg, Arc::new(Mutex::new(mock)));
        engine.arm().unwrap();
        let out = engine.translate("com.example.Foo.slow()V");
        assert_eq!(out.source, TranslationSource::Fallback);
        assert!(out.native_action.is_none());
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn background_traduce_su_thread() {
        let mut mock = MockBackend::new();
        mock.action = Some(NativeAction::SetBlock);
        let (engine, _calls) = engine_with(mock);
        engine.arm().unwrap();
        let handle = engine.translate_in_background("com.example.Foo.place()V");
        let out = handle.join().unwrap();
        assert_eq!(out.source, TranslationSource::SmolLm2);
        assert_eq!(out.native_action, Some(NativeAction::SetBlock));
    }

    #[test]
    fn background_disarm_non_chiama_ai() {
        let mut mock = MockBackend::new();
        mock.action = Some(NativeAction::SetBlock);
        let (engine, calls) = engine_with(mock);
        engine.arm().unwrap();
        engine.disarm("off");
        let handle = engine.translate_in_background("com.example.Foo.place()V");
        let out = handle.join().unwrap();
        assert_eq!(out.source, TranslationSource::Disabled);
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }
}
