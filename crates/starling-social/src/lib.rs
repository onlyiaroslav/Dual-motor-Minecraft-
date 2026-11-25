//! # starling-social
//!
//! Fase 6: **lista amici** e **join per account offline tramite codice
//! monouso**. Layer puramente additivo: non tocca l'autenticazione Microsoft
//! standard, il bridge a 8 bit, `starling-features`, il game loop o
//! `bridge-jni`.
//!
//! ## Moduli
//!
//! - [`friends`]: `Friend` (nickname, UUID locale, online/offline) e
//!   `FriendList` persistente su file, salvataggio atomico.
//! - [`token`]: `TokenStore` con codici alfanumerici di 8 caratteri generati
//!   con **`rand::rngs::OsRng`**, TTL 5 minuti, `HashMap` dei token attivi con
//!   IP/Porta dell'host, consumo monouso immediato.
//! - [`network_auth`]: mini-server TCP **separato dal bridge a 8 bit** che
//!   intercetta il login offline, chiede il codice, inietta un profilo finto
//!   (`online_mode = false` solo per quel giocatore) e invalida il token.
//! - [`tunnel`]: modalità **LAN** (stessa Wi-Fi) e trait `P2pTunnel` per il
//!   futuro tunnel NAT/CGNAT (iroh/libp2p), non implementato.
//! - [`jni`] (feature `jni`): export per la schermata "Amici" del launcher
//!   Kotlin. Additivo, non sostituisce il bridge JNI esistente.
//!
//! ## Sicurezza
//!
//! - Codici generati dal CSPRNG di sistema (`OsRng`), 40 bit di entropia,
//!   alfabeto senza caratteri ambigui.
//! - Monouso: il token è rimosso **prima** di verificare la scadenza.
//! - I token non sono persistiti: al riavvio decadono tutti.
//! - Il server auth ascolta su una porta dedicata; il traffico di gioco resta
//!   sul bridge/porta separati (nessun impatto sulla latenza).

#![deny(unsafe_code)]

pub mod friends;
pub mod network_auth;
pub mod token;
pub mod tunnel;

#[cfg(feature = "jni")]
pub mod jni;

pub use friends::{valid_nickname, Friend, FriendList};
pub use network_auth::{
    redeem_code, AuthConfig, AuthError, InjectedProfile, OfflineAuthServer,
};
pub use token::{TokenEntry, TokenError, TokenStore, CODE_LEN, DEFAULT_TTL};
pub use tunnel::{FutureP2pTunnel, LanTunnel, P2pTunnel};

use std::fmt;

/// Errori del layer social.
#[derive(Debug)]
pub enum SocialError {
    /// Errore di I/O (file amici, rete).
    Io(std::io::Error),
    /// Protocollo malformato.
    Protocol(String),
    /// Nickname non valido (3–16 caratteri [A-Za-z0-9_]).
    InvalidNickname(String),
    /// Nickname già presente nella lista.
    DuplicateFriend(String),
    /// Server auth non avviato o già fermo.
    Server(String),
}

impl fmt::Display for SocialError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "errore I/O: {e}"),
            Self::Protocol(m) => write!(f, "errore protocollo: {m}"),
            Self::InvalidNickname(n) => write!(f, "nickname non valido: {n}"),
            Self::DuplicateFriend(n) => write!(f, "amico già presente: {n}"),
            Self::Server(m) => write!(f, "errore server auth: {m}"),
        }
    }
}

impl std::error::Error for SocialError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for SocialError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}