//! Lista amici persistente (file locale) con UUID locali.
//!
//! Nessun legame con l'autenticazione Microsoft: gli UUID sono generati
//! localmente (v4, OsRng) e servono solo come identità stabile per la
//! lista amici e per il profilo finto iniettato dal join offline.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use rand::rngs::OsRng;
use rand::RngCore;

use crate::SocialError;

/// Un amico: nickname, UUID locale, stato online/offline.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Friend {
    pub nickname: String,
    pub uuid: String,
    pub online: bool,
}

/// Genera un UUID v4 locale con OsRng (formato 8-4-4-4-12).
pub fn local_uuid() -> String {
    let mut b = [0u8; 16];
    OsRng.fill_bytes(&mut b);
    b[6] = (b[6] & 0x0f) | 0x40; // versione 4
    b[8] = (b[8] & 0x3f) | 0x80; // variante RFC 4122
    let h: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// Valida un nickname stile Minecraft: 3–16 caratteri [A-Za-z0-9_].
pub fn valid_nickname(nick: &str) -> bool {
    (3..=16).contains(&nick.len())
        && nick.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// Lista amici persistente.
///
/// Formato file (una riga per amico): `uuid nickname online(0|1)`.
/// I nickname sono validati (niente spazi/pipe) → parsing banale e sicuro.
/// Salvataggio atomico: write su tmp + rename.
#[derive(Default)]
pub struct FriendList {
    friends: HashMap<String, Friend>, // chiave: nickname
    path: Option<PathBuf>,
}

impl FriendList {
    pub fn new() -> Self {
        Self::default()
    }

    /// Carica la lista da file (inesistente → lista vuota).
    pub fn load(path: impl AsRef<Path>) -> Result<Self, SocialError> {
        let path = path.as_ref();
        let mut list = Self { friends: HashMap::new(), path: Some(path.to_path_buf()) };
        if !path.exists() {
            return Ok(list);
        }
        let text = fs::read_to_string(path)?;
        for line in text.lines() {
            if line.trim().is_empty() {
                continue;
            }
            let mut parts = line.split(' ');
            let uuid = parts.next().ok_or_else(|| SocialError::Protocol("malformed friends row".into()))?;
            let nickname = parts.next().ok_or_else(|| SocialError::Protocol("malformed friends row".into()))?;
            let online = parts.next().ok_or_else(|| SocialError::Protocol("malformed friends row".into()))?;
            if parts.next().is_some() || !valid_nickname(nickname) || uuid.len() != 36 {
                return Err(SocialError::Protocol(format!("invalid friends row: {line}")));
            }
            list.friends.insert(nickname.to_string(), Friend {
                nickname: nickname.to_string(),
                uuid: uuid.to_string(),
                online: online == "1",
            });
        }
        Ok(list)
    }

    /// Salva atomicamente sul file associato (se presente).
    pub fn save(&self) -> Result<(), SocialError> {
        if let Some(path) = &self.path {
            self.save_to(path)?;
        }
        Ok(())
    }

    /// Salva su un percorso esplicito.
    pub fn save_to(&self, path: impl AsRef<Path>) -> Result<(), SocialError> {
        let path = path.as_ref();
        let mut text = String::new();
        let mut sorted: Vec<&Friend> = self.friends.values().collect();
        sorted.sort_by(|a, b| a.nickname.cmp(&b.nickname));
        for f in sorted {
            text.push_str(&format!("{} {} {}\n", f.uuid, f.nickname, if f.online { 1 } else { 0 }));
        }
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, text)?;
        fs::rename(&tmp, path)?;
        Ok(())
    }

    /// Aggiunge un amico (nickname univoco, UUID locale nuovo).
    pub fn add(&mut self, nickname: &str) -> Result<Friend, SocialError> {
        if !valid_nickname(nickname) {
            return Err(SocialError::InvalidNickname(nickname.to_string()));
        }
        if self.friends.contains_key(nickname) {
            return Err(SocialError::DuplicateFriend(nickname.to_string()));
        }
        let f = Friend {
            nickname: nickname.to_string(),
            uuid: local_uuid(),
            online: false,
        };
        self.friends.insert(nickname.to_string(), f.clone());
        Ok(f)
    }

    pub fn remove(&mut self, nickname: &str) -> bool {
        self.friends.remove(nickname).is_some()
    }

    pub fn find(&self, nickname: &str) -> Option<&Friend> {
        self.friends.get(nickname)
    }

    pub fn set_online(&mut self, uuid: &str, online: bool) -> bool {
        for f in self.friends.values_mut() {
            if f.uuid == uuid {
                f.online = online;
                return true;
            }
        }
        false
    }

    /// Registra/aggiorna un amico come online; ritorna il suo UUID locale.
    /// Usato dal server auth dopo un join offline riuscito.
    pub fn ensure_online(&mut self, nickname: &str) -> Result<String, SocialError> {
        if let Some(f) = self.friends.get_mut(nickname) {
            f.online = true;
            return Ok(f.uuid.clone());
        }
        let f = self.add(nickname)?;
        self.friends.get_mut(nickname).unwrap().online = true;
        Ok(f.uuid)
    }

    pub fn list(&self) -> Vec<Friend> {
        let mut v: Vec<Friend> = self.friends.values().cloned().collect();
        v.sort_by(|a, b| a.nickname.cmp(&b.nickname));
        v
    }

    pub fn len(&self) -> usize {
        self.friends.len()
    }

    pub fn is_empty(&self) -> bool {
        self.friends.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuid_v4_locale_formato() {
        let u = local_uuid();
        assert_eq!(u.len(), 36);
        assert_eq!(u.as_bytes()[14], b'4'); // versione
        let variant = u.as_bytes()[19];
        assert!(variant == b'8' || variant == b'9' || variant == b'a' || variant == b'b');
        assert_ne!(local_uuid(), local_uuid());
    }

    #[test]
    fn nickname_validazione() {
        assert!(valid_nickname("Steve_01"));
        assert!(!valid_nickname("ab")); // troppo corto
        assert!(!valid_nickname("a".repeat(17).as_str()));
        assert!(!valid_nickname("evil name"));
        assert!(!valid_nickname("pipe|name"));
    }

    #[test]
    fn aggiungi_duplicato_rimuovi() {
        let mut l = FriendList::new();
        let f = l.add("Alice").unwrap();
        assert!(!f.online);
        assert!(matches!(l.add("Alice"), Err(SocialError::DuplicateFriend(_))));
        assert!(matches!(l.add("x"), Err(SocialError::InvalidNickname(_))));
        assert!(l.set_online(&f.uuid, true));
        assert!(l.find("Alice").unwrap().online);
        assert!(l.remove("Alice"));
        assert!(l.is_empty());
    }

    #[test]
    fn persistenza_roundtrip() {
        let path = std::env::temp_dir().join(format!("starling-friends-{}.txt", std::process::id()));
        let mut l = FriendList::new();
        let a = l.add("Alice").unwrap();
        l.add("Bob").unwrap();
        l.set_online(&a.uuid, true);
        l.save_to(&path).unwrap();

        let r = FriendList::load(&path).unwrap();
        assert_eq!(r.len(), 2);
        assert!(r.find("Alice").unwrap().online);
        assert!(!r.find("Bob").unwrap().online);
        assert_eq!(r.find("Alice").unwrap().uuid, a.uuid);
        fs::remove_file(&path).unwrap();
    }

    #[test]
    fn ensure_online_registra_o_aggiorna() {
        let mut l = FriendList::new();
        let u1 = l.ensure_online("Nuovo").unwrap();
        assert!(l.find("Nuovo").unwrap().online);
        let u2 = l.ensure_online("Nuovo").unwrap();
        assert_eq!(u1, u2, "stesso UUID locale alla seconda entrata");
    }
}