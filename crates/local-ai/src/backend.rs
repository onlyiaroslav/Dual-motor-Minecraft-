//! Interfaccia del motore AI locale e wrapper verso llama.cpp.

use std::fmt;
use std::path::PathBuf;

use bridge_u8::NativeAction;

/// Errori del backend AI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AiBackendError {
    /// Il modello non è stato caricato / non risulta presente sul dispositivo.
    ModelNotAvailable,
    /// Errore di inferenza (runtime llama.cpp).
    Inference,
    /// Timeout dell'inferenza.
    Timeout,
}

impl fmt::Display for AiBackendError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ModelNotAvailable => write!(f, "modello AI non disponibile"),
            Self::Inference => write!(f, "errore inferenza AI locale"),
            Self::Timeout => write!(f, "timeout AI locale"),
        }
    }
}

impl std::error::Error for AiBackendError {}

/// Interfaccia per il layer AI: dato il nome + descriptor di un metodo Java,
/// suggerisce la `NativeAction` corrispondente.
pub trait AiBackend: Send + Sync {
    /// Carica il modello (SmolLM2 GGUF Q4_K_M) nel backend.
    fn load(&mut self) -> Result<(), AiBackendError>;

    /// Analizza la firma Java e restituisce la mappatura proposta.
    /// `Ok(None)` = nessuna azione consigliata per quella firma.
    fn translate_fragment(&self, java_method_signature: &str) -> Result<Option<NativeAction>, AiBackendError>;
}

/// Wrapper verso il runtime **llama.cpp** (SmolLM2, formato GGUF Q4_K_M).
pub struct LlamaCppBackend {
    /// Percorso del file modello GGUF sul dispositivo.
    pub model_path: PathBuf,
    loaded: bool,
}

impl LlamaCppBackend {
    /// Crea un backend puntato al modello indicato (non ancora caricato).
    pub fn new(model_path: impl Into<PathBuf>) -> Self {
        Self {
            model_path: model_path.into(),
            loaded: false,
        }
    }

    /// `true` se il modello è stato caricato con successo.
    pub fn is_loaded(&self) -> bool {
        self.loaded
    }
}

impl AiBackend for LlamaCppBackend {
    fn load(&mut self) -> Result<(), AiBackendError> {
        if !self.model_path.exists() {
            return Err(AiBackendError::ModelNotAvailable);
        }
        // Fase 7 v0: il punto di inserimento della chiamata llama.cpp
        // (llama_model_load / llama_init_from_model / llama_infer) è qui.
        // Il resto dell'orchestrazione è già testabile senza il runtime.
        self.loaded = true;
        Ok(())
    }

    fn translate_fragment(
        &self,
        java_method_signature: &str,
    ) -> Result<Option<NativeAction>, AiBackendError> {
        if !self.loaded {
            return Err(AiBackendError::ModelNotAvailable);
        }
        // Fase 7 v0: runtime llama.cpp non ancora collegato; errore di inferenza.
        let _ = java_method_signature;
        Err(AiBackendError::Inference)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_model(tag: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("starling_ai_test_{tag}.gguf"));
        std::fs::write(&p, b"GGUF").unwrap();
        p
    }

    #[test]
    fn load_modello_assente_errore() {
        let mut b = LlamaCppBackend::new("/percorso/inesistente.gguf");
        assert_eq!(b.load(), Err(AiBackendError::ModelNotAvailable));
        assert!(!b.is_loaded());
    }

    #[test]
    fn load_modello_presente_ok() {
        let p = temp_model("presente");
        let mut b = LlamaCppBackend::new(&p);
        assert!(b.load().is_ok());
        assert!(b.is_loaded());
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn translate_prima_del_load_errore() {
        let b = LlamaCppBackend::new("/percorso/inesistente.gguf");
        assert_eq!(
            b.translate_fragment("com.example.Foo.x()V"),
            Err(AiBackendError::ModelNotAvailable)
        );
    }

    #[test]
    fn translate_dopo_load_placeholder_inferenza() {
        let p = temp_model("infer");
        let mut b = LlamaCppBackend::new(&p);
        b.load().unwrap();
        // v0: il runtime llama.cpp non è collegato, quindi inferenza non disponibile.
        assert_eq!(
            b.translate_fragment("com.example.Foo.x()V"),
            Err(AiBackendError::Inference)
        );
        let _ = std::fs::remove_file(&p);
    }

    #[test]
    fn errori_display() {
        assert!(AiBackendError::ModelNotAvailable.to_string().contains("non disponibile"));
        assert!(AiBackendError::Timeout.to_string().contains("timeout"));
    }
}
