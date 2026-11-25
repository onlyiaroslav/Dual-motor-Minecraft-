//! Generatore di codici invito monouso con TTL.
//!
//! - Codice alfanumerico di 8 caratteri, alfabeto da 32 simboli senza
//!   ambiguità (niente 0/O/1/I): 40 bit di entropia.
//! - Generazione con **rand::rngs::OsRng** (CSPRNG di sistema).
//! - TTL default 5 minuti; `active_tokens: HashMap<codice, entry>` con
//!   l'IP/Porta del mondo host.
//! - Monouso: `consume` rimuove il token IMMEDIATAMENTE (anche se scaduto).
//! - I token NON sono persistiti: alla riavvio del launcher decadono tutti
//!   (scelta di sicurezza deliberata).

use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::{Duration, Instant};

use rand::rngs::OsRng;
use rand::RngCore;

/// Alfabeto 32 simboli (256 % 32 == 0 → nessuna bias nella mappatura).
const ALPHABET: &[u8; 32] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
/// Lunghezza codice (specifica: 6–8; usiamo 8 = 40 bit).
pub const CODE_LEN: usize = 8;
/// TTL di default: 5 minuti.
pub const DEFAULT_TTL: Duration = Duration::from_secs(5 * 60);

/// Entry di un token attivo.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TokenEntry {
    /// IP/Porta del mondo host a cui il codice dà accesso.
    pub host_world: SocketAddr,
    pub expires: Instant,
}

/// Errore di consumo di un token.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TokenError {
    /// Codice mai emesso (o già consumato).
    NotFound,
    /// Codice emesso ma oltre il TTL.
    Expired,
}

/// Registro dei codici monouso attivi.
pub struct TokenStore {
    pub active_tokens: HashMap<String, TokenEntry>,
    ttl: Duration,
}

impl Default for TokenStore {
    fn default() -> Self {
        Self::new()
    }
}

impl TokenStore {
    pub fn new() -> Self {
        Self { active_tokens: HashMap::new(), ttl: DEFAULT_TTL }
    }

    /// TTL personalizzato (usato nei test per la scadenza).
    pub fn with_ttl(ttl: Duration) -> Self {
        Self { active_tokens: HashMap::new(), ttl }
    }

    pub fn ttl(&self) -> Duration {
        self.ttl
    }

    /// Genera un codice monouso per l'host indicato e lo registra.
    pub fn generate(&mut self, host_world: SocketAddr) -> String {
        loop {
            let mut bytes = [0u8; CODE_LEN];
            OsRng.fill_bytes(&mut bytes);
            let code: String = bytes
                .iter()
                .map(|b| ALPHABET[(*b % 32) as usize] as char)
                .collect();
            if self.active_tokens.contains_key(&code) {
                continue; // collisione astronomicamente rara, rigenera
            }
            self.active_tokens.insert(code.clone(), TokenEntry {
                host_world,
                expires: Instant::now() + self.ttl,
            });
            return code;
        }
    }

    /// Valida e CONSUMA il token (monouso). Rimuove subito la entry;
    /// ritorna None se inesistente o scaduto.
    pub fn consume(&mut self, code: &str) -> Option<TokenEntry> {
        self.consume_checked(code).ok()
    }

    /// Come [`Self::consume`], ma distingue "inesistente" da "scaduto".
    /// In entrambi i casi il token viene rimosso: un codice scaduto non
    /// deve poter essere riprovato.
    pub fn consume_checked(&mut self, code: &str) -> Result<TokenEntry, TokenError> {
        let entry = self.active_tokens.remove(code).ok_or(TokenError::NotFound)?;
        if Instant::now() >= entry.expires {
            Err(TokenError::Expired)
        } else {
            Ok(entry)
        }
    }

    /// Rimuove i token scaduti (manutenzione periodica). Ritorna quanti.
    pub fn purge_expired(&mut self) -> usize {
        let now = Instant::now();
        let before = self.active_tokens.len();
        self.active_tokens.retain(|_, e| e.expires > now);
        before - self.active_tokens.len()
    }

    pub fn active_count(&self) -> usize {
        self.active_tokens.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    fn host() -> SocketAddr {
        "192.168.1.10:25565".parse().unwrap()
    }

    #[test]
    fn formato_codice() {
        let mut s = TokenStore::new();
        let code = s.generate(host());
        assert_eq!(code.len(), CODE_LEN);
        assert!(code.chars().all(|c| ALPHABET.contains(&(c as u8))));
        assert!(!code.contains('0') && !code.contains('O') && !code.contains('1'));
    }

    #[test]
    fn unicita_su_1000_generazioni() {
        let mut s = TokenStore::new();
        let mut seen = HashSet::new();
        for _ in 0..1000 {
            assert!(seen.insert(s.generate(host())));
        }
        assert_eq!(s.active_count(), 1000);
    }

    #[test]
    fn monouso_secondo_uso_negato() {
        let mut s = TokenStore::new();
        let code = s.generate(host());
        let e = s.consume(&code).unwrap();
        assert_eq!(e.host_world, host());
        assert!(s.consume(&code).is_none(), "il token deve essere invalidato subito");
        assert_eq!(s.active_count(), 0);
    }

    #[test]
    fn scadenza_ttl() {
        let mut s = TokenStore::with_ttl(Duration::from_millis(60));
        let code = s.generate(host());
        // Prima della scadenza il token è valido ma NON va consumato qui.
        assert!(s.active_tokens.contains_key(&code));
        std::thread::sleep(Duration::from_millis(90));
        assert_eq!(s.consume_checked(&code), Err(TokenError::Expired));
        assert_eq!(s.consume(&code), None, "scaduto: rimosso e non riprovabile");
    }

    #[test]
    fn token_valido_prima_della_scadenza() {
        let mut s = TokenStore::with_ttl(Duration::from_secs(5));
        let code = s.generate(host());
        assert!(s.consume_checked(&code).is_ok());
    }

    #[test]
    fn ttl_default_5_minuti() {
        let s = TokenStore::new();
        assert_eq!(s.ttl(), Duration::from_secs(300));
    }

    #[test]
    fn purge_rimuove_scaduti() {
        let mut s = TokenStore::with_ttl(Duration::from_millis(40));
        s.generate(host());
        s.generate(host());
        std::thread::sleep(Duration::from_millis(70));
        let fresh = s.generate(host());
        assert_eq!(s.purge_expired(), 2);
        assert_eq!(s.active_count(), 1);
        assert!(s.consume(&fresh).is_some());
    }

    #[test]
    fn codice_inesistente_negato() {
        let mut s = TokenStore::new();
        assert!(s.consume("XXXXXXXX").is_none());
    }
}