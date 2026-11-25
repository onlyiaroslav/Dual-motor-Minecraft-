//! Interfaccia del motore AI locale e backend **HTTP** verso un server
//! OpenAI-compatible in ascolto su `127.0.0.1`.
//!
//! ## Architettura (niente FFI, niente C++)
//! Il modello (SmolLM2 360M o altro) gira in un **processo separato** che espone
//! l'API OpenAI (`POST /v1/chat/completions`): `llama-server` di llama.cpp,
//! LM Studio, Ollama, text-generation-webui, ecc. Il crate si limita a parlare
//! HTTP in locale, quindi:
//! - nessuna compilazione nativa di llama.cpp / bindgen;
//! - nessun modello caricato nel processo dell'app;
//! - se il server non risponde (porta chiusa, timeout, HTTP 4xx/5xx) la
//!   traduzione restituisce `Ok(None)` e il chiamante applica il fallback,
//!   esattamente come con il toggle OFF.
//!
//! ## Timeout
//! Il client HTTP ha un timeout di richiesta di 5 secondi e un timeout di
//! connessione di 2 secondi ([`INFERENCE_TIMEOUT_SECS`],
//! [`CONNECT_TIMEOUT_SECS`]); il motore (`LocalAiEngine`) applica comunque il
//! proprio timeout di configurazione.
//!
//! ## Testabilità
//! [`HttpTransport`] astrae il client HTTP: i test usano un trasporto mock (o un
//! mini-server TCP locale) senza dipendere da un modello reale.

use std::fmt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use bridge_u8::NativeAction;

/// Endpoint OpenAI-compatible predefinito (server locale).
pub const DEFAULT_ENDPOINT: &str = "http://127.0.0.1:8080/v1/chat/completions";

/// Timeout (secondi) di una singola richiesta HTTP, per non bloccare il parser.
pub const INFERENCE_TIMEOUT_SECS: u64 = 5;

/// Timeout (secondi) per stabilire la connessione TCP con il server locale.
pub const CONNECT_TIMEOUT_SECS: u64 = 2;

/// Numero massimo di token generati (basta il nome di un'azione).
pub const MAX_TOKENS: u32 = 16;

/// Nome modello inviato nel payload (i server locali lo ignorano o lo usano
/// per selezionare il modello caricato).
pub const DEFAULT_MODEL_NAME: &str = "smollm2-360m";

/// Errori del backend AI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AiBackendError {
    /// Il server locale non è raggiungibile / non è pronto.
    ModelNotAvailable,
    /// Errore di inferenza (risposta non valida, ecc.).
    Inference,
    /// Timeout dell'inferenza.
    Timeout,
}

impl fmt::Display for AiBackendError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ModelNotAvailable => write!(f, "server AI locale non disponibile"),
            Self::Inference => write!(f, "errore inferenza AI locale"),
            Self::Timeout => write!(f, "timeout AI locale"),
        }
    }
}

impl std::error::Error for AiBackendError {}

/// Interfaccia per il layer AI: dato il nome + descriptor di un metodo Java,
/// suggerisce la `NativeAction` corrispondente.
pub trait AiBackend: Send + Sync {
    /// Verifica che il server locale sia raggiungibile (health check).
    fn load(&mut self) -> Result<(), AiBackendError>;

    /// Analizza la firma Java e restituisce la mappatura proposta.
    /// `Ok(None)` = nessuna azione (fallback: server assente, `NONE`, timeout).
    fn translate_fragment(
        &self,
        java_method_signature: &str,
    ) -> Result<Option<NativeAction>, AiBackendError>;
}

// --- Logica pura (sempre compilabile e testabile senza rete) ---------------

/// Azioni che il prompt propone al modello (sottoinsieme di `NativeAction`).
pub const PROMPT_ACTIONS: &[&str] = &[
    "NativeTick",
    "NativeHud",
    "NativeKey",
    "PlaySound",
    "SetBlock",
];

/// Costruisce il prompt per SmolLM2 a partire dalla firma del metodo Java.
pub fn format_prompt(java_method_signature: &str) -> String {
    format!(
        "Sei un traduttore. Data questa firma: {java_method_signature}. \
         Rispondi solo con l'azione nativa tra \
         [NativeTick, NativeHud, NativeKey, PlaySound, SetBlock]. \
         Se non sei sicuro, rispondi NONE."
    )
}

/// Mappa un nome di azione (case-insensitive) nella `NativeAction` valida.
/// Ritorna `None` per nomi sconosciuti o `NONE`.
pub fn action_from_name(name: &str) -> Option<NativeAction> {
    match name.trim().to_ascii_uppercase().as_str() {
        "NOOP" => Some(NativeAction::Noop),
        "NATIVETICK" => Some(NativeAction::NativeTick),
        "NATIVEHUD" => Some(NativeAction::NativeHud),
        "NATIVEKEY" => Some(NativeAction::NativeKey),
        "SPAWNPARTICLE" => Some(NativeAction::SpawnParticle),
        "PLAYSOUND" => Some(NativeAction::PlaySound),
        "SETBLOCK" => Some(NativeAction::SetBlock),
        "BREAKBLOCK" => Some(NativeAction::BreakBlock),
        "SENDCHAT" => Some(NativeAction::SendChat),
        "APPLYVELOCITY" => Some(NativeAction::ApplyVelocity),
        "SETWORLDTIME" => Some(NativeAction::SetWorldTime),
        "OPENSCREEN" => Some(NativeAction::OpenScreen),
        _ => None,
    }
}

/// Interpreta la risposta testuale del modello.
///
/// Estrae la prima parola "pulita" (ignorando backtick/asterischi/spazi),
/// la confronta case-insensitive con le azioni valide. `NONE`, testo vuoto o
/// non riconosciuto ⇒ `None` (fallback: scarta e logga).
pub fn parse_action_response(response: &str) -> Option<NativeAction> {
    let trimmed = response
        .trim()
        .trim_matches(|c: char| !c.is_alphanumeric());
    let word: String = trimmed
        .chars()
        .take_while(|c| c.is_alphanumeric() || *c == '_')
        .collect();
    if word.is_empty() {
        return None;
    }
    if word.eq_ignore_ascii_case("NONE") {
        return None;
    }
    action_from_name(&word)
}

/// Costruisce il body JSON della richiesta OpenAI-compatible.
pub fn build_request_body(model: &str, prompt: &str) -> String {
    serde_json::json!({
        "model": model,
        "messages": [
            { "role": "user", "content": prompt }
        ],
        "temperature": 0.0,
        "max_tokens": MAX_TOKENS,
        "stream": false
    })
    .to_string()
}

/// Estrae `choices[0].message.content` dalla risposta OpenAI-compatible.
pub fn parse_openai_content(body: &str) -> Result<String, TransportError> {
    let value: serde_json::Value = serde_json::from_str(body)
        .map_err(|e| TransportError::Parse(format!("JSON non valido: {e}")))?;
    value
        .get("choices")
        .and_then(|choices| choices.get(0))
        .and_then(|choice| choice.get("message"))
        .and_then(|message| message.get("content"))
        .and_then(|content| content.as_str())
        .map(str::to_string)
        .ok_or_else(|| {
            TransportError::Parse("choices[0].message.content assente".to_string())
        })
}

// --- Trasporto HTTP ---------------------------------------------------------

/// Errori del trasporto HTTP.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransportError {
    /// Errore di rete / connessione rifiutata.
    Network(String),
    /// Timeout della richiesta.
    Timeout,
    /// Risposta HTTP con status non 2xx.
    Http(u16),
    /// Risposta non interpretabile (JSON/struttura inattesa).
    Parse(String),
}

impl fmt::Display for TransportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Network(e) => write!(f, "errore di rete: {e}"),
            Self::Timeout => write!(f, "timeout della richiesta HTTP"),
            Self::Http(code) => write!(f, "risposta HTTP {code}"),
            Self::Parse(e) => write!(f, "risposta non valida: {e}"),
        }
    }
}

impl std::error::Error for TransportError {}

/// Astrazione del client HTTP (mockabile nei test).
pub trait HttpTransport: Send + Sync {
    /// `true` se il server risponde sulla URL indicata (health check).
    fn probe(&self, url: &str) -> bool;

    /// Esegue una `POST` JSON e restituisce il body della risposta.
    fn post_json(&self, url: &str, body: &str) -> Result<String, TransportError>;
}

/// Trasporto reale basato su `reqwest` (blocking, rustls).
struct ReqwestTransport {
    client: reqwest::blocking::Client,
}

impl ReqwestTransport {
    fn new() -> Self {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(INFERENCE_TIMEOUT_SECS))
            .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
            .user_agent("starling-local-ai/0.1")
            .build()
            .expect("impossibile creare il client HTTP locale");
        Self { client }
    }
}

fn map_reqwest_error(error: reqwest::Error) -> TransportError {
    if error.is_timeout() {
        TransportError::Timeout
    } else {
        TransportError::Network(error.to_string())
    }
}

impl HttpTransport for ReqwestTransport {
    fn probe(&self, url: &str) -> bool {
        use std::net::ToSocketAddrs;

        let Ok(parsed) = reqwest::Url::parse(url) else {
            return false;
        };
        let Some(host) = parsed.host_str() else {
            return false;
        };
        let port = parsed.port_or_known_default().unwrap_or(80);
        let Ok(addresses) = (host, port).to_socket_addrs() else {
            return false;
        };
        let timeout = Duration::from_secs(CONNECT_TIMEOUT_SECS);
        addresses
            .into_iter()
            .any(|addr| std::net::TcpStream::connect_timeout(&addr, timeout).is_ok())
    }

    fn post_json(&self, url: &str, body: &str) -> Result<String, TransportError> {
        let response = self
            .client
            .post(url)
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .body(body.to_string())
            .send()
            .map_err(map_reqwest_error)?;

        let status = response.status();
        if !status.is_success() {
            return Err(TransportError::Http(status.as_u16()));
        }
        response.text().map_err(map_reqwest_error)
    }
}

// --- Backend HTTP -----------------------------------------------------------

/// Backend AI che parla con un server OpenAI-compatible locale.
pub struct HttpAiBackend {
    endpoint: String,
    model: String,
    transport: Arc<dyn HttpTransport>,
    loaded: AtomicBool,
}

impl HttpAiBackend {
    /// Crea un backend puntato all'endpoint indicato (default:
    /// [`DEFAULT_ENDPOINT`]) usando il trasporto `reqwest` reale.
    pub fn new(endpoint: impl Into<String>) -> Self {
        Self::with_transport(endpoint, Arc::new(ReqwestTransport::new()))
    }

    /// Crea un backend con un trasporto iniettivo (usato nei test).
    pub fn with_transport(
        endpoint: impl Into<String>,
        transport: Arc<dyn HttpTransport>,
    ) -> Self {
        Self {
            endpoint: endpoint.into(),
            model: DEFAULT_MODEL_NAME.to_string(),
            transport,
            loaded: AtomicBool::new(false),
        }
    }

    /// Endpoint configurato.
    pub fn endpoint(&self) -> &str {
        &self.endpoint
    }

    /// Nome modello inviato nel payload.
    pub fn model(&self) -> &str {
        &self.model
    }

    /// Imposta il nome modello inviato nel payload.
    pub fn set_model(&mut self, model: impl Into<String>) {
        self.model = model.into();
    }

    /// `true` se l'ultimo health check ha avuto successo.
    pub fn is_loaded(&self) -> bool {
        self.loaded.load(Ordering::SeqCst)
    }
}

impl AiBackend for HttpAiBackend {
    fn load(&mut self) -> Result<(), AiBackendError> {
        if self.transport.probe(&self.endpoint) {
            self.loaded.store(true, Ordering::SeqCst);
            Ok(())
        } else {
            self.loaded.store(false, Ordering::SeqCst);
            Err(AiBackendError::ModelNotAvailable)
        }
    }

    fn translate_fragment(
        &self,
        java_method_signature: &str,
    ) -> Result<Option<NativeAction>, AiBackendError> {
        let prompt = format_prompt(java_method_signature);
        let body = build_request_body(&self.model, &prompt);

        match self.transport.post_json(&self.endpoint, &body) {
            Ok(response) => match parse_openai_content(&response) {
                Ok(content) => Ok(parse_action_response(&content)),
                Err(error) => {
                    eprintln!("[local-ai] risposta del server non valida: {error}");
                    Ok(None)
                }
            },
            Err(error) => {
                // Server assente/timeout/HTTP errore: fallback come toggle OFF.
                eprintln!("[local-ai] server locale non disponibile: {error}");
                Ok(None)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::atomic::AtomicUsize;
    use std::sync::Mutex;
    use std::thread;
    use std::time::Instant;

    /// Trasporto fittizio: risposta/errore configurabili, zero rete.
    struct MockTransport {
        probe_ok: bool,
        response: Mutex<Result<String, TransportError>>,
        calls: AtomicUsize,
        last_body: Mutex<String>,
    }

    impl MockTransport {
        fn ok(probe_ok: bool, body: &str) -> Self {
            Self {
                probe_ok,
                response: Mutex::new(Ok(body.to_string())),
                calls: AtomicUsize::new(0),
                last_body: Mutex::new(String::new()),
            }
        }

        fn err(probe_ok: bool, error: TransportError) -> Self {
            Self {
                probe_ok,
                response: Mutex::new(Err(error)),
                calls: AtomicUsize::new(0),
                last_body: Mutex::new(String::new()),
            }
        }

        fn calls(&self) -> usize {
            self.calls.load(Ordering::SeqCst)
        }

        fn last_body(&self) -> String {
            self.last_body.lock().unwrap().clone()
        }
    }

    impl HttpTransport for MockTransport {
        fn probe(&self, _url: &str) -> bool {
            self.probe_ok
        }

        fn post_json(&self, _url: &str, body: &str) -> Result<String, TransportError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            *self.last_body.lock().unwrap() = body.to_string();
            self.response.lock().unwrap().clone()
        }
    }

    fn openai_body(content: &str) -> String {
        serde_json::json!({
            "choices": [
                { "message": { "role": "assistant", "content": content } }
            ]
        })
        .to_string()
    }

    /// Mini-server HTTP su 127.0.0.1: risponde a `connections` richieste.
    fn spawn_mock_server(
        response_body: &str,
        connections: usize,
    ) -> (String, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind del server mock");
        let addr = listener.local_addr().expect("indirizzo del server mock");
        let body = response_body.to_string();
        let handle = thread::spawn(move || {
            for _ in 0..connections {
                let Ok((mut stream, _)) = listener.accept() else {
                    break;
                };
                let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
                let mut buffer = [0u8; 16 * 1024];
                let _ = stream.read(&mut buffer);
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.flush();
            }
        });
        (format!("http://{addr}/v1/chat/completions"), handle)
    }

    // --- Logica pura --------------------------------------------------------

    #[test]
    fn prompt_contiene_firma_e_istruzioni() {
        let prompt = format_prompt("com.example.Foo.onTick()V");
        assert!(prompt.contains("com.example.Foo.onTick()V"));
        assert!(prompt.contains("Sei un traduttore"));
        assert!(prompt.contains("NativeTick"));
        assert!(prompt.contains("NONE"));
    }

    #[test]
    fn parse_azioni_valide() {
        assert_eq!(parse_action_response("NativeTick"), Some(NativeAction::NativeTick));
        assert_eq!(parse_action_response("PlaySound"), Some(NativeAction::PlaySound));
        assert_eq!(parse_action_response("SetBlock"), Some(NativeAction::SetBlock));
        assert_eq!(parse_action_response("NativeHud"), Some(NativeAction::NativeHud));
        assert_eq!(parse_action_response("NativeKey"), Some(NativeAction::NativeKey));
    }

    #[test]
    fn parse_case_insensitive_e_pulizia() {
        assert_eq!(parse_action_response("  nativetick  "), Some(NativeAction::NativeTick));
        assert_eq!(parse_action_response("`PlaySound`"), Some(NativeAction::PlaySound));
        assert_eq!(parse_action_response("**SetBlock**"), Some(NativeAction::SetBlock));
        assert_eq!(parse_action_response("NativeTick.\n"), Some(NativeAction::NativeTick));
    }

    #[test]
    fn parse_none_e_sconosciuti_fallback() {
        assert_eq!(parse_action_response("NONE"), None);
        assert_eq!(parse_action_response("none"), None);
        assert_eq!(parse_action_response("Non sono sicuro"), None);
        assert_eq!(parse_action_response(""), None);
        assert_eq!(parse_action_response("AzioneSconosciuta"), None);
    }

    #[test]
    fn action_from_name_copre_tutte_le_native() {
        assert_eq!(action_from_name("SendChat"), Some(NativeAction::SendChat));
        assert_eq!(action_from_name("OpenScreen"), Some(NativeAction::OpenScreen));
        assert_eq!(action_from_name("Noop"), Some(NativeAction::Noop));
        assert_eq!(action_from_name("boh"), None);
    }

    #[test]
    fn timeout_e_endpoint_predefiniti() {
        assert_eq!(INFERENCE_TIMEOUT_SECS, 5);
        assert_eq!(CONNECT_TIMEOUT_SECS, 2);
        assert_eq!(DEFAULT_ENDPOINT, "http://127.0.0.1:8080/v1/chat/completions");
    }

    #[test]
    fn body_json_valido_e_contiene_prompt() {
        let prompt = format_prompt("com.example.Foo.a()V");
        let body = build_request_body(DEFAULT_MODEL_NAME, &prompt);
        let value: serde_json::Value = serde_json::from_str(&body).expect("JSON valido");
        assert_eq!(value["model"], DEFAULT_MODEL_NAME);
        assert_eq!(value["stream"], false);
        assert_eq!(value["messages"][0]["role"], "user");
        assert!(value["messages"][0]["content"]
            .as_str()
            .unwrap()
            .contains("com.example.Foo.a()V"));
    }

    #[test]
    fn parse_openai_content_estrazione() {
        let body = openai_body("NativeTick");
        assert_eq!(parse_openai_content(&body), Ok("NativeTick".to_string()));
    }

    #[test]
    fn parse_openai_content_malformato() {
        assert!(matches!(
            parse_openai_content("non-json"),
            Err(TransportError::Parse(_))
        ));
        assert!(matches!(
            parse_openai_content("{}"),
            Err(TransportError::Parse(_))
        ));
    }

    #[test]
    fn errori_display() {
        assert!(AiBackendError::ModelNotAvailable
            .to_string()
            .contains("non disponibile"));
        assert!(TransportError::Timeout.to_string().contains("timeout"));
        assert!(TransportError::Http(500).to_string().contains("500"));
    }

    // --- Backend con trasporto mock -----------------------------------------

    #[test]
    fn load_probe_ok_diventa_loaded() {
        let transport = Arc::new(MockTransport::ok(true, "{}"));
        let mut backend =
            HttpAiBackend::with_transport(DEFAULT_ENDPOINT, transport.clone());
        assert_eq!(backend.load(), Ok(()));
        assert!(backend.is_loaded());
    }

    #[test]
    fn load_probe_fallito_errore() {
        let transport = Arc::new(MockTransport::ok(false, "{}"));
        let mut backend =
            HttpAiBackend::with_transport(DEFAULT_ENDPOINT, transport.clone());
        assert_eq!(backend.load(), Err(AiBackendError::ModelNotAvailable));
        assert!(!backend.is_loaded());
    }

    #[test]
    fn translate_risposta_valida() {
        let transport = Arc::new(MockTransport::ok(true, &openai_body("NativeTick")));
        let backend = HttpAiBackend::with_transport(DEFAULT_ENDPOINT, transport.clone());
        assert_eq!(
            backend.translate_fragment("com.example.Foo.onTick()V"),
            Ok(Some(NativeAction::NativeTick))
        );
        assert_eq!(transport.calls(), 1);
        // Il body inviato contiene la firma e il prompt richiesto.
        let sent = transport.last_body();
        assert!(sent.contains("com.example.Foo.onTick()V"));
        assert!(sent.contains("Sei un traduttore"));
    }

    #[test]
    fn translate_none_fallback() {
        let transport = Arc::new(MockTransport::ok(true, &openai_body("NONE")));
        let backend = HttpAiBackend::with_transport(DEFAULT_ENDPOINT, transport.clone());
        assert_eq!(
            backend.translate_fragment("com.example.Foo.unknown()V"),
            Ok(None)
        );
    }

    #[test]
    fn translate_errore_rete_fallback_senza_panic() {
        let transport = Arc::new(MockTransport::err(
            false,
            TransportError::Network("connessione rifiutata".into()),
        ));
        let backend = HttpAiBackend::with_transport(DEFAULT_ENDPOINT, transport.clone());
        assert_eq!(
            backend.translate_fragment("com.example.Foo.x()V"),
            Ok(None)
        );
        assert_eq!(transport.calls(), 1);
    }

    #[test]
    fn translate_errore_http_fallback() {
        let transport = Arc::new(MockTransport::err(false, TransportError::Http(503)));
        let backend = HttpAiBackend::with_transport(DEFAULT_ENDPOINT, transport.clone());
        assert_eq!(
            backend.translate_fragment("com.example.Foo.x()V"),
            Ok(None)
        );
    }

    #[test]
    fn translate_timeout_fallback() {
        let transport = Arc::new(MockTransport::err(false, TransportError::Timeout));
        let backend = HttpAiBackend::with_transport(DEFAULT_ENDPOINT, transport.clone());
        assert_eq!(
            backend.translate_fragment("com.example.Foo.x()V"),
            Ok(None)
        );
    }

    // --- Backend con trasporto reale (mini-server TCP) ----------------------

    #[test]
    fn server_http_reale_risponde() {
        let (url, handle) = spawn_mock_server(&openai_body("NativeTick"), 2);
        let mut backend = HttpAiBackend::new(url);
        assert_eq!(backend.load(), Ok(()));
        assert_eq!(
            backend.translate_fragment("com.example.Foo.onTick()V"),
            Ok(Some(NativeAction::NativeTick))
        );
        handle.join().expect("server mock terminato");
    }

    #[test]
    fn server_porta_chiusa_fallback_senza_panic() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("addr");
        drop(listener); // porta chiusa: connessione rifiutata

        let mut backend =
            HttpAiBackend::new(format!("http://{addr}/v1/chat/completions"));
        assert_eq!(backend.load(), Err(AiBackendError::ModelNotAvailable));

        let started = Instant::now();
        assert_eq!(
            backend.translate_fragment("com.example.Foo.x()V"),
            Ok(None)
        );
        assert!(
            started.elapsed() < Duration::from_secs(INFERENCE_TIMEOUT_SECS + 1),
            "il fallback deve rispettare il timeout"
        );
    }
}
