//! Errori del bridge a 8 bit.

use std::fmt;
use std::io;

/// Errori prodotti dal crate `bridge-u8`.
#[derive(Debug)]
pub enum BridgeError {
    /// Errore di I/O durante il caricamento/salvataggio del mapping.
    Io(io::Error),
    /// Il file non inizia con il magic `BU8M`.
    InvalidMagic([u8; 4]),
    /// Versione del formato non supportata da questo crate.
    UnsupportedVersion(u16),
    /// File terminato prima del previsto (header o record incompleto).
    TruncatedMapping,
    /// Il file dichiara più entry del massimo possibile (65536).
    TooManyEntries(u32),
    /// Lo stesso ID di wire è mappato due volte.
    DuplicateMapping(u16),
    /// Il file contiene un ID azione che non corrisponde a nessuna `NativeAction`.
    UnknownActionId(u16),
    /// ID di wire ricevuto dalla JVM ma assente nella LUT.
    UnmappedWireId(u16),
    /// L'esecutore nativo ha rifiutato/ fallito l'azione.
    Executor(String),
    /// Buffer condiviso (ring) non valido: magic, versione, allineamento, capacità.
    InvalidRing(String),
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "errore I/O: {e}"),
            Self::InvalidMagic(m) => write!(f, "magic non valido: {m:?} (atteso b\"BU8M\")"),
            Self::UnsupportedVersion(v) => write!(f, "versione mapping non supportata: {v}"),
            Self::TruncatedMapping => write!(f, "file mapping troncato"),
            Self::TooManyEntries(n) => write!(f, "troppe entry nel mapping: {n} (max 65536)"),
            Self::DuplicateMapping(id) => write!(f, "ID di wire duplicato: 0x{id:04X}"),
            Self::UnknownActionId(id) => write!(f, "ID azione sconosciuto: {id}"),
            Self::UnmappedWireId(id) => write!(f, "ID di wire non mappato: 0x{id:04X}"),
            Self::Executor(msg) => write!(f, "errore esecutore nativo: {msg}"),
            Self::InvalidRing(msg) => write!(f, "ring buffer non valido: {msg}"),
        }
    }
}

impl std::error::Error for BridgeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<io::Error> for BridgeError {
    fn from(e: io::Error) -> Self {
        Self::Io(e)
    }
}