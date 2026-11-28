//! Mini-server TCP di autenticazione per il join degli account offline.
//!
//! **Separato dal bridge a 8 bit**: ascolta su una porta dedicata e non
//! condivide thread, buffer o lock con il percorso di gioco → nessun impatto
//! sulla latenza del bridge.
//!
//! ## Flusso
//!
//! Il server intercetta il login. Un client con account **Microsoft** viene
//! lasciato passare intatto (`LOGIN_MS` → `PASS_THROUGH microsoft`): questo
//! layer è **aggiuntivo**, non sostituisce l'autenticazione standard.
//!
//! Un client **offline** viene invitato a inserire il codice monouso:
//!
//! ```text
//! client: LOGIN Steve
//! server: CODE_REQUIRED
//! client: TOKEN ABCD2345
//! server: WELCOME <uuid-locale> Steve offline
//! ```
//!
//! In caso di successo il token viene consumato **immediatamente** e il
//! server inietta un profilo finto con l'UUID locale dell'amico e
//! `online_mode = false` **solo per quel giocatore**.
//!
//! ## Sicurezza
//!
//! - massimo [`AuthConfig::max_attempts`] tentativi per connessione;
//! - timeout di lettura/scrittura per evitare connessioni appese;
//! - nickname validati prima di consumare il token (un nickname invalido non
//!   brucia il codice);
//! - token scaduti rimossi e non riprovabili;
//! - nessun token viene mai loggato.

use std::io::{BufRead, BufReader, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::friends::{valid_nickname, FriendList};
use crate::token::{TokenError, TokenStore};
use crate::SocialError;

/// Configurazione del mini-server auth.
#[derive(Clone, Debug)]
pub struct AuthConfig {
    /// Indirizzo di ascolto. Per la modalità LAN usare `0.0.0.0:<porta>`.
    pub bind_addr: SocketAddr,
    /// Tentativi di codice per singola connessione.
    pub max_attempts: u32,
    /// Timeout di lettura/scrittura per connessione.
    pub read_timeout: Duration,
    /// Porta/indirizzo del mondo host annunciato ai client.
    /// Se `None` viene usato l'indirizzo locale del server.
    pub world_addr: Option<SocketAddr>,
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            bind_addr: "127.0.0.1:0".parse().expect("indirizzo valido"),
            max_attempts: 3,
            read_timeout: Duration::from_secs(30),
            world_addr: None,
        }
    }
}

impl AuthConfig {
    /// Config LAN: ascolta su tutte le interfacce alla porta indicata.
    pub fn lan(port: u16) -> Self {
        Self {
            bind_addr: SocketAddr::from(([0, 0, 0, 0], port)),
            ..Self::default()
        }
    }
}

/// Profilo finto iniettato per un giocatore offline autenticato.
///
/// `online_mode` è **sempre `false`** per questo giocatore: la sua identità
/// è l'UUID locale, non quella Microsoft.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InjectedProfile {
    pub nickname: String,
    /// UUID locale stabile (dalla `FriendList`, generato con OsRng).
    pub uuid: String,
    /// Sempre `false`: il giocatore è offline, autenticato dal codice.
    pub online_mode: bool,
    /// Mondo host a cui il codice dà accesso.
    pub host_world: SocketAddr,
}

impl InjectedProfile {
    /// Profilo offline per un amico già registrato.
    pub fn offline(nickname: impl Into<String>, uuid: impl Into<String>, host_world: SocketAddr) -> Self {
        Self {
            nickname: nickname.into(),
            uuid: uuid.into(),
            online_mode: false,
            host_world,
        }
    }

    /// Riga di `WELCOME` inviata al client.
    pub fn welcome_line(&self) -> String {
        format!(
            "WELCOME {} {} {}",
            self.uuid,
            self.nickname,
            if self.online_mode { "online" } else { "offline" }
        )
    }

    /// Forza `online_mode = false` nella configurazione della **sessione di
    /// questo giocatore**. Non tocca la config globale del server né gli
    /// altri giocatori: è l'applicazione esplicita del vincolo di Fase 6.
    pub fn apply_to_session(&self, session_online_mode: &mut bool) {
        *session_online_mode = false;
    }
}

/// Errori del layer auth.
#[derive(Debug)]
pub enum AuthError {
    /// Errore di I/O sulla connessione.
    Io(std::io::Error),
    /// Messaggio malformato.
    Protocol(String),
    /// Nickname non valido.
    InvalidNickname(String),
    /// Token inesistente, già usato o scaduto.
    Token(TokenError),
    /// Richiesta rifiutata (motivo leggibile).
    Denied(String),
}

impl std::fmt::Display for AuthError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(e) => write!(f, "auth I/O error: {e}"),
            Self::Protocol(m) => write!(f, "protocollo auth: {m}"),
            Self::InvalidNickname(n) => write!(f, "invalid nickname: {n}"),
            Self::Token(TokenError::NotFound) => write!(f, "code not found or already used"),
            Self::Token(TokenError::Expired) => write!(f, "code expired"),
            Self::Denied(m) => write!(f, "accesso negato: {m}"),
        }
    }
}

impl std::error::Error for AuthError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for AuthError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

impl From<TokenError> for AuthError {
    fn from(e: TokenError) -> Self {
        Self::Token(e)
    }
}

/// Valida un codice monouso e costruisce il profilo offline.
///
/// Ordine deliberato: prima il nickname (un nickname invalido non deve
/// bruciare il token), poi il consumo **atomico** del codice. Il token viene
/// rimosso anche se scaduto, così non è riprovabile.
pub fn redeem_code(
    tokens: &mut TokenStore,
    friends: &mut FriendList,
    nickname: &str,
    code: &str,
) -> Result<InjectedProfile, AuthError> {
    if !valid_nickname(nickname) {
        return Err(AuthError::InvalidNickname(nickname.to_string()));
    }
    let code = code.trim().to_ascii_uppercase();
    let entry = tokens.consume_checked(&code)?;
    let uuid = friends
        .ensure_online(nickname)
        .map_err(|e| AuthError::Denied(e.to_string()))?;
    Ok(InjectedProfile::offline(nickname, uuid, entry.host_world))
}

/// Mini-server TCP di autenticazione offline.
///
/// Il listener viene spostato in un thread dedicato all'avvio; `stop`/`Drop`
/// fermano il loop di accept. Le connessioni in corso hanno un timeout e si
/// chiudono da sole. Il server non tocca mai il bridge a 8 bit.
pub struct OfflineAuthServer {
    listener: Option<TcpListener>,
    config: AuthConfig,
    local_addr: SocketAddr,
    tokens: Arc<Mutex<TokenStore>>,
    friends: Arc<Mutex<FriendList>>,
    logs: Arc<Mutex<Vec<String>>>,
    running: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl OfflineAuthServer {
    /// Crea il server con stato nuovo (token store e lista amici vuoti).
    pub fn bind(config: AuthConfig) -> Result<Self, SocialError> {
        Self::bind_with_state(config, Arc::new(Mutex::new(TokenStore::new())), Arc::new(Mutex::new(FriendList::new())))
    }

    /// Crea il server condividendo token store e lista amici con il launcher
    /// (la schermata "Amici" genera i codici sullo stesso store).
    pub fn bind_with_state(
        config: AuthConfig,
        tokens: Arc<Mutex<TokenStore>>,
        friends: Arc<Mutex<FriendList>>,
    ) -> Result<Self, SocialError> {
        let listener = TcpListener::bind(config.bind_addr)
            .map_err(|e| SocialError::Server(format!("failed to bind {}: {e}", config.bind_addr)))?;
        listener
            .set_nonblocking(true)
            .map_err(|e| SocialError::Server(format!("failed to start nonblocking listener: {e}")))?;
        let local_addr = listener
            .local_addr()
            .map_err(|e| SocialError::Server(format!("failed to get local address: {e}")))?;
        Ok(Self {
            listener: Some(listener),
            config,
            local_addr,
            tokens,
            friends,
            logs: Arc::new(Mutex::new(Vec::new())),
            running: Arc::new(AtomicBool::new(false)),
            thread: None,
        })
    }

    /// Indirizzo effettivo di ascolto (utile con porta 0 nei test).
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// Store condiviso dei token (per generare gli inviti dalla UI).
    pub fn tokens(&self) -> Arc<Mutex<TokenStore>> {
        Arc::clone(&self.tokens)
    }

    /// Lista amici condivisa.
    pub fn friends(&self) -> Arc<Mutex<FriendList>> {
        Arc::clone(&self.friends)
    }

    /// Genera un codice monouso valido per il mondo host indicato.
    pub fn generate_invite(&self, host_world: SocketAddr) -> String {
        self.tokens
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .generate(host_world)
    }

    /// Avvia il loop di accept. Idempotente: se già avviato non fa nulla.
    pub fn start(&mut self) -> Result<(), SocialError> {
        if self.running.load(Ordering::SeqCst) {
            return Ok(());
        }
        let listener = self
            .listener
            .take()
            .ok_or_else(|| SocialError::Server("server already stopped: recreate the instance".into()))?;
        let config = self.config.clone();
        let tokens = Arc::clone(&self.tokens);
        let friends = Arc::clone(&self.friends);
        let logs = Arc::clone(&self.logs);
        let running = Arc::clone(&self.running);
        running.store(true, Ordering::SeqCst);
        self.thread = Some(thread::spawn(move || {
            accept_loop(listener, config, tokens, friends, logs, running);
        }));
        Ok(())
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }

    /// Ferma il loop di accept e attende il thread. Le connessioni attive
    /// terminano al timeout.
    pub fn stop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        if let Some(h) = self.thread.take() {
            let _ = h.join();
        }
    }

    /// Log del server (join, rifiuti, pass-through Microsoft). Mai token.
    pub fn logs(&self) -> Vec<String> {
        self.logs.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }
}

impl Drop for OfflineAuthServer {
    fn drop(&mut self) {
        self.stop();
    }
}

fn accept_loop(
    listener: TcpListener,
    config: AuthConfig,
    tokens: Arc<Mutex<TokenStore>>,
    friends: Arc<Mutex<FriendList>>,
    logs: Arc<Mutex<Vec<String>>>,
    running: Arc<AtomicBool>,
) {
    while running.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, peer)) => {
                let config = config.clone();
                let tokens = Arc::clone(&tokens);
                let friends = Arc::clone(&friends);
                let logs = Arc::clone(&logs);
                // Connessioni gestite in parallelo: un client lento non
                // blocca gli altri. Il timeout chiude quelle appese.
                thread::spawn(move || {
                    let _ = handle_client(stream, peer, &config, &tokens, &friends, &logs);
                });
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(5));
            }
            Err(_) => break,
        }
    }
}

/// Gestisce una singola connessione: `LOGIN` → `TOKEN`, oppure `LOGIN_MS`.
fn handle_client(
    stream: TcpStream,
    peer: SocketAddr,
    config: &AuthConfig,
    tokens: &Mutex<TokenStore>,
    friends: &Mutex<FriendList>,
    logs: &Mutex<Vec<String>>,
) -> Result<(), AuthError> {
    stream.set_read_timeout(Some(config.read_timeout))?;
    stream.set_write_timeout(Some(config.read_timeout))?;
    let _ = stream.set_nodelay(true);
    let mut writer = stream.try_clone()?;
    let mut reader = BufReader::new(stream);
    let mut pending_nick: Option<String> = None;
    let mut attempts = 0u32;

    loop {
        let mut line = String::new();
        match reader.read_line(&mut line) {
            Ok(0) => return Ok(()), // client disconnesso
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock
                || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                return Ok(());
            }
            Err(e) => return Err(AuthError::Io(e)),
        }
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let (cmd, arg) = match line.split_once(char::is_whitespace) {
            Some((c, a)) => (c, a.trim()),
            None => (line, ""),
        };
        match cmd.to_ascii_uppercase().as_str() {
            "PING" => send(&mut writer, "PONG")?,
            "LOGIN" => {
                if !valid_nickname(arg) {
                    send(&mut writer, "DENY invalid_nickname")?;
                    continue;
                }
                pending_nick = Some(arg.to_string());
                send(&mut writer, "CODE_REQUIRED")?;
            }
            // Account Microsoft: questo layer non lo tocca. Il bridge di gioco
            // prosegue con l'autenticazione standard.
            "LOGIN_MS" => {
                let nick = if valid_nickname(arg) { arg } else { "?" };
                logs.lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push(format!("microsoft {nick} from {peer}"));
                send(&mut writer, "PASS_THROUGH microsoft")?;
                return Ok(());
            }
            "TOKEN" => {
                let nick = match &pending_nick {
                    Some(n) => n.clone(),
                    None => {
                        send(&mut writer, "DENY login_required")?;
                        continue;
                    }
                };
                attempts += 1;
                let result = {
                    let mut t = tokens.lock().unwrap_or_else(|e| e.into_inner());
                    let mut f = friends.lock().unwrap_or_else(|e| e.into_inner());
                    redeem_code(&mut t, &mut f, &nick, arg)
                };
                match result {
                    Ok(profile) => {
                        logs.lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .push(format!(
                                "join {} {} from {peer}",
                                profile.nickname, profile.uuid
                            ));
                        send(&mut writer, &profile.welcome_line())?;
                        return Ok(());
                    }
                    Err(AuthError::Token(TokenError::Expired)) => {
                        send(&mut writer, "DENY expired_code")?;
                    }
                    Err(AuthError::Token(TokenError::NotFound)) => {
                        send(&mut writer, "DENY invalid_code")?;
                    }
                    Err(AuthError::InvalidNickname(_)) => {
                        send(&mut writer, "DENY invalid_nickname")?;
                    }
                    Err(e) => return Err(e),
                }
                if attempts >= config.max_attempts {
                    send(&mut writer, "DENY too_many_attempts")?;
                    return Ok(());
                }
            }
            _ => send(&mut writer, "DENY unknown_command")?,
        }
    }
}

fn send(stream: &mut TcpStream, line: &str) -> Result<(), AuthError> {
    stream.write_all(line.as_bytes())?;
    stream.write_all(b"\n")?;
    stream.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::net::TcpStream;

    fn temp_friends() -> FriendList {
        FriendList::new()
    }

    #[test]
    fn redeem_valido_inietta_profilo_offline() {
        let mut tokens = TokenStore::new();
        let mut friends = temp_friends();
        let host: SocketAddr = "192.168.1.20:25565".parse().unwrap();
        let code = tokens.generate(host);

        let p = redeem_code(&mut tokens, &mut friends, "Steve_01", &code).unwrap();
        assert_eq!(p.nickname, "Steve_01");
        assert_eq!(p.host_world, host);
        assert!(!p.online_mode, "il giocatore offline deve avere online_mode=false");
        assert_eq!(p.uuid.len(), 36);
        assert_eq!(friends.find("Steve_01").unwrap().uuid, p.uuid);
        assert!(friends.find("Steve_01").unwrap().online);
        assert_eq!(tokens.active_count(), 0, "token invalidato subito");

        // Forzatura esplicita: la sessione di QUESTO giocatore passa a offline.
        let mut session_online_mode = true;
        p.apply_to_session(&mut session_online_mode);
        assert!(!session_online_mode, "online-mode forzato a false per il giocatore offline");

        // Riuso: negato.
        assert!(matches!(
            redeem_code(&mut tokens, &mut friends, "Steve_01", &code),
            Err(AuthError::Token(TokenError::NotFound))
        ));
    }

    #[test]
    fn redeem_scaduto_negato_e_non_riprovabile() {
        let mut tokens = TokenStore::with_ttl(Duration::from_millis(40));
        let mut friends = temp_friends();
        let code = tokens.generate("127.0.0.1:25565".parse().unwrap());
        thread::sleep(Duration::from_millis(70));
        assert!(matches!(
            redeem_code(&mut tokens, &mut friends, "Alex", &code),
            Err(AuthError::Token(TokenError::Expired))
        ));
        assert_eq!(tokens.active_count(), 0);
        assert!(matches!(
            redeem_code(&mut tokens, &mut friends, "Alex", &code),
            Err(AuthError::Token(TokenError::NotFound))
        ));
    }

    #[test]
    fn nickname_invalido_non_brucia_il_token() {
        let mut tokens = TokenStore::new();
        let mut friends = temp_friends();
        let code = tokens.generate("127.0.0.1:25565".parse().unwrap());
        assert!(matches!(
            redeem_code(&mut tokens, &mut friends, "x", &code),
            Err(AuthError::InvalidNickname(_))
        ));
        assert_eq!(tokens.active_count(), 1, "il codice deve restare valido");
        assert!(redeem_code(&mut tokens, &mut friends, "Alex", &code).is_ok());
    }

    #[test]
    fn uuid_locale_stabile_tra_join() {
        let mut tokens = TokenStore::new();
        let mut friends = temp_friends();
        let c1 = tokens.generate("127.0.0.1:1".parse().unwrap());
        let p1 = redeem_code(&mut tokens, &mut friends, "Alex", &c1).unwrap();
        let c2 = tokens.generate("127.0.0.1:1".parse().unwrap());
        let p2 = redeem_code(&mut tokens, &mut friends, "Alex", &c2).unwrap();
        assert_eq!(p1.uuid, p2.uuid, "stesso UUID locale per lo stesso amico");
    }

    // --- Test del server TCP -------------------------------------------------

    fn read_line(stream: &mut TcpStream) -> String {
        let mut buf = [0u8; 1];
        let mut out = Vec::new();
        loop {
            match stream.read(&mut buf) {
                Ok(0) => break,
                Ok(_) => {
                    if buf[0] == b'\n' {
                        break;
                    }
                    out.push(buf[0]);
                }
                Err(_) => break,
            }
        }
        String::from_utf8_lossy(&out).trim().to_string()
    }

    fn send_line(stream: &mut TcpStream, line: &str) {
        stream.write_all(line.as_bytes()).unwrap();
        stream.write_all(b"\n").unwrap();
        stream.flush().unwrap();
    }

    fn start_server() -> OfflineAuthServer {
        let mut cfg = AuthConfig::default();
        cfg.read_timeout = Duration::from_secs(2);
        let mut server = OfflineAuthServer::bind(cfg).unwrap();
        server.start().unwrap();
        server
    }

    #[test]
    fn server_login_token_welcome() {
        let server = start_server();
        let mut s = TcpStream::connect(server.local_addr()).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();

        send_line(&mut s, "LOGIN Steve");
        assert_eq!(read_line(&mut s), "CODE_REQUIRED");

        let code = server.generate_invite("10.0.0.5:25565".parse().unwrap());
        send_line(&mut s, &format!("TOKEN {code}"));
        let welcome = read_line(&mut s);
        let parts: Vec<&str> = welcome.split(' ').collect();
        assert_eq!(parts[0], "WELCOME");
        assert_eq!(parts[1].len(), 36);
        assert_eq!(parts[2], "Steve");
        assert_eq!(parts[3], "offline");
        assert!(!welcome.contains("online "), "mai online mode per l'offline");

        // Token consumato: secondo client negato.
        let mut s2 = TcpStream::connect(server.local_addr()).unwrap();
        s2.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        send_line(&mut s2, "LOGIN Alex");
        assert_eq!(read_line(&mut s2), "CODE_REQUIRED");
        send_line(&mut s2, &format!("TOKEN {code}"));
        assert_eq!(read_line(&mut s2), "DENY invalid_code");

        let logs = server.logs();
        assert!(logs.iter().any(|l| l.starts_with("join Steve ")));
        assert!(logs.iter().all(|l| !l.contains(&code)), "il token non va mai loggato");
    }

    #[test]
    fn server_microsoft_pass_through() {
        let server = start_server();
        let mut s = TcpStream::connect(server.local_addr()).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        send_line(&mut s, "LOGIN_MS Notch");
        assert_eq!(read_line(&mut s), "PASS_THROUGH microsoft");
        // L'autenticazione standard prosegue fuori da questo layer.
        assert!(server.logs().iter().any(|l| l.starts_with("microsoft Notch")));
        assert_eq!(
            server.tokens().lock().unwrap().active_count(),
            0,
            "nessun token toccato per Microsoft"
        );
    }

    #[test]
    fn server_max_attempts() {
        let mut cfg = AuthConfig::default();
        cfg.max_attempts = 2;
        cfg.read_timeout = Duration::from_secs(2);
        let mut server = OfflineAuthServer::bind(cfg).unwrap();
        server.start().unwrap();

        let mut s = TcpStream::connect(server.local_addr()).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        send_line(&mut s, "LOGIN Steve");
        assert_eq!(read_line(&mut s), "CODE_REQUIRED");
        send_line(&mut s, "TOKEN BBBBBBBB");
        assert_eq!(read_line(&mut s), "DENY invalid_code");
        send_line(&mut s, "TOKEN CCCCCCCC");
        assert_eq!(read_line(&mut s), "DENY invalid_code");
        assert_eq!(read_line(&mut s), "DENY too_many_attempts");
    }

    #[test]
    fn server_token_senza_login_negato() {
        let server = start_server();
        let mut s = TcpStream::connect(server.local_addr()).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        send_line(&mut s, "TOKEN ABCD2345");
        assert_eq!(read_line(&mut s), "DENY login_required");
    }

    #[test]
    fn server_ping() {
        let server = start_server();
        let mut s = TcpStream::connect(server.local_addr()).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        send_line(&mut s, "PING");
        assert_eq!(read_line(&mut s), "PONG");
    }

    #[test]
    fn stop_ferma_il_server() {
        let mut server = start_server();
        let addr = server.local_addr();
        assert!(server.is_running());
        server.stop();
        assert!(!server.is_running());
        // Dopo lo stop il listener è chiuso: connessione rifiutata.
        let r = TcpStream::connect_timeout(&addr, Duration::from_millis(300));
        assert!(r.is_err());
    }
}
