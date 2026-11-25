//! # mod-analyzer
//!
//! Fase 7: analizzatore del JAR dei mod per la conversione offline.
//!
//! Principio fondamentale (vincolo Fase 7): il tool deve operare in
//! **zero codice copiato**. Per questo il parser estrae esclusivamente le
//! **firme** dei metodi Java (`name` + `descriptor` della JVM), mai bytecode,
//! né logica di decompilazione. Le firme alimentano il vocabolario di mapping
//! (`mapping.bin`) condiviso con la AI locale.
//!
//! Il formato JAR (ZIP) è letto direttamente dalla struttura dei file
//! central directory / local header: niente dipendenze esterne.

#![deny(unsafe_code)]
#![warn(missing_docs)]

mod jar;

pub use jar::extract_method_signatures;

use std::fmt;

/// Errori dell'analizzatore.
#[derive(Debug)]
pub enum AnalyzerError {
    /// Errore di I/O sulla struttura del file.
    Io(std::io::Error),
    /// JAR o classe non conforme allo standard JVM.
    Malformed(String),
}

impl fmt::Display for AnalyzerError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "errore I/O analizzatore: {e}"),
            Self::Malformed(m) => write!(f, "jar malformato: {m}"),
        }
    }
}

impl std::error::Error for AnalyzerError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for AnalyzerError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

impl From<String> for AnalyzerError {
    fn from(s: String) -> Self {
        Self::Malformed(s)
    }
}

/// Firma di un metodo Java: nome, descriptor JVM e classe di appartenenza.
#[derive(Clone, Debug, PartialEq, Eq, Hash, Default)]
pub struct JavaMethodSignature {
    /// Nome normalizzato della classe (es. `com.example.Foo`).
    pub class_name: String,
    /// Nome del metodo (es. `onNativeTick`).
    pub name: String,
    /// Descriptor JVM (es. `()V`, `(Ljava/lang/String;)Z`).
    pub descriptor: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn errori_son_display() {
        assert!(AnalyzerError::Malformed("boom".into()).to_string().contains("boom"));
    }
}
