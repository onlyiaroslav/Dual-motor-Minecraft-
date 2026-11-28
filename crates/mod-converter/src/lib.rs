//! # mod-converter
//!
//! Fase 7: convertitore **offline** di mod per Starling ("Doppio Motore").
//!
//! Orchestratore che unisce [`mod_analyzer`] (estrazione delle sole firme) e
//! [`local_ai`] (traduzione SmolLM2) per produrre due artefatti:
//!
//! - `mapping.bin`: dizionario di vocabolario nel formato LUT di `bridge-u8`
//!   (`wire_id u16` → `NativeAction`), caricabile dal motore a runtime.
//! - `conversion_log.json`: registro leggibile delle decisioni per ogni firma.
//!
//! ## Vincoli legali (Fase 7)
//! - **Zero codice copiato**: vengono conservate solo firme (`classe.metodo` +
//!   descriptor), ID numerici e nomi di azioni. Mai bytecode, mai asset, mai
//!   decompilazione. Nessun upload: tutto resta sul dispositivo.
//! - **Toggle AI**: se l'AI è disattivata ([`local_ai::EngineState::Disarmed`])
//!   le API sconosciute vengono scartate e loggate come `"AI disattivata"`; si
//!   usa solo la libreria esistente (`mapping.bin`). Modello assente o timeout
//!   ⇒ fallback, come se il toggle fosse spento.
//! - **Zero regressioni**: questo crate è additivo; non tocca `bridge-u8`,
//!   `bridge-jni`, `starling-features` né il game loop.

#![deny(unsafe_code)]
#![warn(missing_docs)]

#[cfg(feature = "jni")]
pub mod jni;

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};

use bridge_u8::mapping::{read_mapping, write_mapping};
use bridge_u8::NativeAction;
use local_ai::{EngineState, LocalAiEngine, TranslationSource};
use mod_analyzer::{extract_method_signatures, JavaMethodSignature};

/// Errori del convertitore.
#[derive(Debug)]
pub enum ConverterError {
    /// Errore di I/O (lettura JAR, scrittura artefatti).
    Io(std::io::Error),
    /// Errore dell'analizzatore JAR.
    Analyzer(mod_analyzer::AnalyzerError),
    /// Errore del formato `mapping.bin` (libreria esistente).
    Mapping(bridge_u8::BridgeError),
}

impl fmt::Display for ConverterError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "converter I/O error: {e}"),
            Self::Analyzer(e) => write!(f, "analyzer error: {e}"),
            Self::Mapping(e) => write!(f, "mapping error: {e}"),
        }
    }
}

impl std::error::Error for ConverterError {}

impl From<std::io::Error> for ConverterError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}
impl From<mod_analyzer::AnalyzerError> for ConverterError {
    fn from(e: mod_analyzer::AnalyzerError) -> Self {
        Self::Analyzer(e)
    }
}
impl From<bridge_u8::BridgeError> for ConverterError {
    fn from(e: bridge_u8::BridgeError) -> Self {
        Self::Mapping(e)
    }
}

/// Origine della decisione per una firma.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DecisionSource {
    /// Tradotta dal modello locale SmolLM2.
    SmolLm2,
    /// Recuperata dalla libreria esistente (`mapping.bin`).
    Library,
    /// AI disattivata dall'utente: firma scartata.
    Disabled,
    /// Fallback (modello assente, timeout, nessuna azione proposta).
    Fallback,
}

impl DecisionSource {
    /// Etichetta usata nel log JSON.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::SmolLm2 => "smollm2",
            Self::Library => "library",
            Self::Disabled => "disabled",
            Self::Fallback => "fallback",
        }
    }
}

/// Decisione registrata per una singola firma nel `conversion_log.json`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogEntry {
    /// Firma canonica `classe.metodo` + descriptor.
    pub signature: String,
    /// ID di wire a 16 bit assegnato alla firma.
    pub wire_id: u16,
    /// Azione nativa risolta, oppure `None` se scartata.
    pub action: Option<NativeAction>,
    /// Origine della decisione.
    pub source: DecisionSource,
    /// Motivazione (es. `"AI disattivata"`).
    pub reason: String,
}

/// Risultato complessivo di una conversione.
#[derive(Debug, Clone, Default)]
pub struct ConversionReport {
    /// Entry risolte `(wire_id, azione)`, ordinate per `wire_id` (per `mapping.bin`).
    pub entries: Vec<(u16, NativeAction)>,
    /// Decisioni per ogni firma unica (per `conversion_log.json`).
    pub log: Vec<LogEntry>,
    /// Numero di firme tradotte dall'AI.
    pub ai_used: usize,
    /// Numero di firme recuperate dalla libreria.
    pub library_used: usize,
    /// Numero di firme scartate.
    pub discarded: usize,
    /// `true` se l'AI era attiva e pronta durante la conversione.
    pub ai_was_ready: bool,
}

/// Chiave canonica di una firma: `classe.metodo` + descriptor JVM.
pub fn signature_key(sig: &JavaMethodSignature) -> String {
    format!("{}.{}{}", sig.class_name, sig.name, sig.descriptor)
}

/// ID di wire deterministico a 16 bit per una chiave (FNV-1a con fold).
///
/// Stabile tra esecuzioni: permette di ritrovare la stessa firma nella
/// libreria `mapping.bin` esistente.
pub fn wire_id_for(key: &str) -> u16 {
    let mut h: u32 = 0x811c_9dc5;
    for b in key.bytes() {
        h ^= b as u32;
        h = h.wrapping_mul(0x0100_0193);
    }
    (h ^ (h >> 16)) as u16
}

/// Carica la libreria esistente `mapping.bin` (facoltativa).
pub fn load_library(path: impl AsRef<Path>) -> Result<Vec<(u16, NativeAction)>, ConverterError> {
    let bytes = fs::read(path)?;
    let entries = read_mapping(std::io::Cursor::new(bytes))?;
    Ok(entries)
}

/// Converte un elenco di firme usando il motore AI e la libreria esistente.
///
/// La libreria ha la precedenza: se una firma è già nota, si usa quella azione.
/// Altrimenti si interroga l'AI solo se pronta; se disattivata/assente si scarta.
pub fn convert_signatures(
    signatures: &[JavaMethodSignature],
    engine: &LocalAiEngine,
    library: &[(u16, NativeAction)],
) -> ConversionReport {
    let lib: HashMap<u16, NativeAction> = library.iter().copied().collect();
    let ai_ready = engine.state() == EngineState::Ready;

    let mut entries: BTreeMap<u16, NativeAction> = BTreeMap::new();
    let mut log = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let (mut ai_used, mut library_used, mut discarded) = (0usize, 0usize, 0usize);

    for sig in signatures {
        let key = signature_key(sig);
        if !seen.insert(key.clone()) {
            continue; // firma duplicata: già processata
        }
        let wire_id = wire_id_for(&key);

        if let Some(&action) = lib.get(&wire_id) {
            entries.insert(wire_id, action);
            log.push(LogEntry {
                signature: key,
                wire_id,
                action: Some(action),
                source: DecisionSource::Library,
                reason: String::new(),
            });
            library_used += 1;
            continue;
        }

        // Firma sconosciuta: interroga l'AI (che rispetta il toggle).
        let outcome = engine.translate(&key);
        match (outcome.source, outcome.native_action) {
            (TranslationSource::SmolLm2, Some(action)) => {
                entries.insert(wire_id, action);
                log.push(LogEntry {
                    signature: key,
                    wire_id,
                    action: Some(action),
                    source: DecisionSource::SmolLm2,
                    reason: String::new(),
                });
                ai_used += 1;
            }
            (TranslationSource::Disabled, _) => {
                log.push(LogEntry {
                    signature: key,
                    wire_id,
                    action: None,
                    source: DecisionSource::Disabled,
                    reason: "AI disabled".into(),
                });
                discarded += 1;
            }
            (TranslationSource::Fallback, _) => {
                log.push(LogEntry {
                    signature: key,
                    wire_id,
                    action: None,
                    source: DecisionSource::Fallback,
                    reason: "fallback AI: modello assente o timeout".into(),
                });
                discarded += 1;
            }
            (TranslationSource::SmolLm2, None) => {
                log.push(LogEntry {
                    signature: key,
                    wire_id,
                    action: None,
                    source: DecisionSource::Fallback,
                    reason: "nessuna azione proposta dall'AI".into(),
                });
                discarded += 1;
            }
        }
    }

    ConversionReport {
        entries: entries.into_iter().collect(),
        log,
        ai_used,
        library_used,
        discarded,
        ai_was_ready: ai_ready,
    }
}

/// Estrae le firme da un JAR e le converte.
pub fn convert_jar(
    jar_bytes: &[u8],
    engine: &LocalAiEngine,
    library: &[(u16, NativeAction)],
) -> Result<ConversionReport, ConverterError> {
    let signatures = extract_method_signatures(jar_bytes)?;
    Ok(convert_signatures(&signatures, engine, library))
}

/// Scrive il `mapping.bin` dal report (formato LUT `bridge-u8`).
pub fn write_mapping_bin(
    report: &ConversionReport,
    path: impl AsRef<Path>,
) -> Result<(), ConverterError> {
    let mut buf = Vec::new();
    write_mapping(&mut buf, &report.entries)?;
    fs::write(path, buf)?;
    Ok(())
}

/// Escape minimale di una stringa per JSON.
fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

/// Serializza il report in `conversion_log.json` (JSON scritto a mano, senza dipendenze).
pub fn conversion_log_json(report: &ConversionReport) -> String {
    let mut s = String::new();
    s.push_str("{\n");
    s.push_str("  \"tool\": \"starling-mod-converter\",\n");
    s.push_str(&format!("  \"ai_was_ready\": {},\n", report.ai_was_ready));
    s.push_str(&format!("  \"total_signatures\": {},\n", report.log.len()));
    s.push_str(&format!("  \"mapped\": {},\n", report.entries.len()));
    s.push_str(&format!("  \"ai_used\": {},\n", report.ai_used));
    s.push_str(&format!("  \"library_used\": {},\n", report.library_used));
    s.push_str(&format!("  \"discarded\": {},\n", report.discarded));
    s.push_str("  \"entries\": [\n");
    for (i, e) in report.log.iter().enumerate() {
        s.push_str("    {");
        s.push_str(&format!("\"signature\": \"{}\", ", json_escape(&e.signature)));
        s.push_str(&format!("\"wire_id\": {}, ", e.wire_id));
        match e.action {
            Some(a) => s.push_str(&format!(
                "\"action\": \"{:?}\", \"action_id\": {}, ",
                a,
                a.as_u16()
            )),
            None => s.push_str("\"action\": null, \"action_id\": null, "),
        }
        s.push_str(&format!("\"source\": \"{}\", ", e.source.as_str()));
        s.push_str(&format!("\"reason\": \"{}\"", json_escape(&e.reason)));
        s.push('}');
        if i + 1 < report.log.len() {
            s.push(',');
        }
        s.push('\n');
    }
    s.push_str("  ]\n");
    s.push_str("}\n");
    s
}

/// Scrive il `conversion_log.json` su disco.
pub fn write_conversion_log(
    report: &ConversionReport,
    path: impl AsRef<Path>,
) -> Result<(), ConverterError> {
    fs::write(path, conversion_log_json(report))?;
    Ok(())
}

/// Percorsi degli artefatti generati.
#[derive(Debug, Clone)]
pub struct OutputPaths {
    /// Percorso del `mapping.bin` generato.
    pub mapping_bin: PathBuf,
    /// Percorso del `conversion_log.json` generato.
    pub conversion_log: PathBuf,
}

/// Pipeline completa: converte un JAR e scrive `mapping.bin` + `conversion_log.json`
/// nella directory `out_dir`. Tutto avviene offline, sul dispositivo.
pub fn convert_jar_to_files(
    jar_bytes: &[u8],
    engine: &LocalAiEngine,
    library: &[(u16, NativeAction)],
    out_dir: impl AsRef<Path>,
) -> Result<OutputPaths, ConverterError> {
    let report = convert_jar(jar_bytes, engine, library)?;
    let out_dir = out_dir.as_ref();
    fs::create_dir_all(out_dir)?;
    let mapping_bin = out_dir.join("mapping.bin");
    let conversion_log = out_dir.join("conversion_log.json");
    write_mapping_bin(&report, &mapping_bin)?;
    write_conversion_log(&report, &conversion_log)?;
    Ok(OutputPaths {
        mapping_bin,
        conversion_log,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use local_ai::{AiBackend, AiBackendError, LocalAiConfig};
    use std::sync::{Arc, Mutex};

    /// Backend mock che mappa una firma specifica a un'azione.
    struct FixedBackend {
        action: NativeAction,
    }
    impl AiBackend for FixedBackend {
        fn load(&mut self) -> Result<(), AiBackendError> {
            Ok(())
        }
        fn translate_fragment(
            &self,
            _sig: &str,
        ) -> Result<Option<NativeAction>, AiBackendError> {
            Ok(Some(self.action))
        }
    }

    fn config() -> LocalAiConfig {
        LocalAiConfig {
            endpoint: "http://127.0.0.1:1/v1/chat/completions".to_string(),
            n_ctx: 512,
            timeout_ms: 2000,
        }
    }

    fn engine_armed(action: NativeAction) -> LocalAiEngine {
        let e = LocalAiEngine::with_backend(
            config(),
            Arc::new(Mutex::new(FixedBackend { action })),
        );
        e.arm().unwrap();
        e
    }

    fn sig(name: &str, desc: &str) -> JavaMethodSignature {
        JavaMethodSignature {
            class_name: "com.example.Test".into(),
            name: name.into(),
            descriptor: desc.into(),
        }
    }

    #[test]
    fn wire_id_deterministico() {
        let a = wire_id_for("com.example.Test.a()V");
        let b = wire_id_for("com.example.Test.a()V");
        assert_eq!(a, b);
        assert_ne!(a, wire_id_for("com.example.Test.b()V"));
    }

    #[test]
    fn ai_attiva_traduce_e_popola_mapping() {
        let engine = engine_armed(NativeAction::NativeTick);
        let report = convert_signatures(&[sig("a", "()V")], &engine, &[]);
        assert_eq!(report.ai_used, 1);
        assert_eq!(report.discarded, 0);
        assert_eq!(report.entries.len(), 1);
        assert_eq!(report.entries[0].1, NativeAction::NativeTick);
        assert_eq!(report.log[0].source, DecisionSource::SmolLm2);
    }

    #[test]
    fn ai_disattivata_scarta_e_logga() {
        let engine = engine_armed(NativeAction::NativeTick);
        engine.disarm("toggle off");
        let report = convert_signatures(&[sig("a", "()V")], &engine, &[]);
        assert_eq!(report.ai_used, 0);
        assert_eq!(report.discarded, 1);
        assert!(report.entries.is_empty());
        assert_eq!(report.log[0].source, DecisionSource::Disabled);
        assert_eq!(report.log[0].reason, "AI disabled");
        let json = conversion_log_json(&report);
        assert!(json.contains("AI disabled"));
    }

    #[test]
    fn libreria_esistente_ha_precedenza() {
        let engine = engine_armed(NativeAction::NativeTick);
        engine.disarm("off"); // anche con AI spenta, la libreria funziona
        let key = signature_key(&sig("a", "()V"));
        let wid = wire_id_for(&key);
        let library = vec![(wid, NativeAction::PlaySound)];
        let report = convert_signatures(&[sig("a", "()V")], &engine, &library);
        assert_eq!(report.library_used, 1);
        assert_eq!(report.discarded, 0);
        assert_eq!(report.entries[0].1, NativeAction::PlaySound);
        assert_eq!(report.log[0].source, DecisionSource::Library);
    }

    #[test]
    fn modello_assente_fallback() {
        // Motore reale con modello inesistente → NotReady.
        let engine = LocalAiEngine::new(config());
        let report = convert_signatures(&[sig("a", "()V")], &engine, &[]);
        assert_eq!(report.discarded, 1);
        assert_eq!(report.log[0].source, DecisionSource::Fallback);
    }

    #[test]
    fn mapping_bin_roundtrip_con_bridge_u8() {
        let engine = engine_armed(NativeAction::SpawnParticle);
        let report = convert_signatures(
            &[sig("a", "()V"), sig("b", "(Ljava/lang/String;)Z")],
            &engine,
            &[],
        );
        let mut buf = Vec::new();
        write_mapping(&mut buf, &report.entries).unwrap();
        let letti = read_mapping(std::io::Cursor::new(&buf)).unwrap();
        assert_eq!(letti, report.entries);
    }

    #[test]
    fn log_json_struttura_valida() {
        let engine = engine_armed(NativeAction::SendChat);
        let report = convert_signatures(&[sig("a", "()V")], &engine, &[]);
        let json = conversion_log_json(&report);
        assert!(json.contains("\"tool\": \"starling-mod-converter\""));
        assert!(json.contains("\"source\": \"smollm2\""));
        assert!(json.contains("\"action\": \"SendChat\""));
        assert!(json.contains("\"action_id\": 8"));
        assert!(json.contains("com.example.Test.a()V"));
    }

    #[test]
    fn firme_duplicate_contate_una_volta() {
        let engine = engine_armed(NativeAction::NativeTick);
        let report = convert_signatures(&[sig("a", "()V"), sig("a", "()V")], &engine, &[]);
        assert_eq!(report.log.len(), 1);
        assert_eq!(report.ai_used, 1);
    }

    #[test]
    fn zero_codice_copiato_nel_log() {
        // Il log contiene solo firme/ID/azioni, mai bytecode della classe.
        let engine = engine_armed(NativeAction::NativeTick);
        let report = convert_signatures(&[sig("a", "()V")], &engine, &[]);
        let json = conversion_log_json(&report);
        assert!(!json.contains("CAFEBABE"));
        assert!(!json.contains("\u{0}"));
    }

    #[test]
    fn pipeline_scrive_artefatti() {
        let dir = std::env::temp_dir().join(format!("starling_conv_{}", std::process::id()));
        let engine = engine_armed(NativeAction::NativeTick);
        // JAR minimo con una classe (riusa il builder di mod-analyzer tramite firma diretta).
        let report = convert_signatures(&[sig("a", "()V")], &engine, &[]);
        fs::create_dir_all(&dir).unwrap();
        write_mapping_bin(&report, dir.join("mapping.bin")).unwrap();
        write_conversion_log(&report, dir.join("conversion_log.json")).unwrap();
        assert!(dir.join("mapping.bin").exists());
        assert!(dir.join("conversion_log.json").exists());
        // Il mapping.bin scritto è ricaricabile dal bridge.
        let bytes = fs::read(dir.join("mapping.bin")).unwrap();
        let letti = read_mapping(std::io::Cursor::new(bytes)).unwrap();
        assert_eq!(letti, report.entries);
        let _ = fs::remove_dir_all(&dir);
    }
}
