//! Test di integrazione Fase 6: due client (host con token attivo e client
//! offline che entra col codice), scadenza, pass-through Microsoft e LAN.
//!
//! Il server auth è quello reale: ascolta su TCP, il client parla il
//! protocollo line-based. Nessun mock del percorso di rete.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use starling_social::tunnel::{LanTunnel, P2pTunnel};
use starling_social::{
    redeem_code, AuthConfig, FriendList, OfflineAuthServer, TokenStore,
};

const HOST_WORLD: &str = "192.168.1.50:25565";

fn cfg() -> AuthConfig {
    AuthConfig {
        read_timeout: Duration::from_secs(3),
        ..AuthConfig::default()
    }
}

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

/// Client offline: LOGIN → CODE_REQUIRED, TOKEN → WELCOME/DENY.
fn join(addr: SocketAddr, nickname: &str, code: &str) -> (String, String) {
    let mut s = TcpStream::connect(addr).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    send_line(&mut s, &format!("LOGIN {nickname}"));
    let first = read_line(&mut s);
    send_line(&mut s, &format!("TOKEN {code}"));
    let second = read_line(&mut s);
    (first, second)
}

fn welcome_uuid(welcome: &str) -> String {
    welcome.split(' ').nth(1).unwrap().to_string()
}

#[test]
fn due_clienti_host_e_offline() {
    let tokens = Arc::new(Mutex::new(TokenStore::new()));
    let friends = Arc::new(Mutex::new(FriendList::new()));
    friends.lock().unwrap().add("Host").unwrap();

    let mut server =
        OfflineAuthServer::bind_with_state(cfg(), Arc::clone(&tokens), Arc::clone(&friends))
            .unwrap();
    server.start().unwrap();

    // L'host genera il codice monouso per il suo mondo.
    let code = server.generate_invite(HOST_WORLD.parse().unwrap());
    assert_eq!(tokens.lock().unwrap().active_count(), 1);

    // Client HOST già connesso al server auth (resta aperto durante il join).
    let mut host = TcpStream::connect(server.local_addr()).unwrap();
    host.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    send_line(&mut host, "PING");
    assert_eq!(read_line(&mut host), "PONG");

    // Client offline: primo ingresso.
    let (first, second) = join(server.local_addr(), "OfflineGuy", &code);
    assert_eq!(first, "CODE_REQUIRED");
    assert!(second.starts_with("WELCOME "), "atteso WELCOME, ricevuto: {second}");
    assert!(second.ends_with(" OfflineGuy offline"), "profilo offline: {second}");
    let uuid = welcome_uuid(&second);
    assert_eq!(uuid.len(), 36);

    // Il client host è ancora servito: due client simultanei, nessun blocco.
    send_line(&mut host, "PING");
    assert_eq!(read_line(&mut host), "PONG");

    // Profilo iniettato + stato amico + token consumato.
    {
        let f = friends.lock().unwrap();
        let friend = f.find("OfflineGuy").expect("amico registrato al join");
        assert_eq!(friend.uuid, uuid, "UUID locale stabile");
        assert!(friend.online);
    }
    assert_eq!(tokens.lock().unwrap().active_count(), 0, "token monouso consumato");

    // Un secondo client con lo stesso codice deve essere negato.
    let (first2, second2) = join(server.local_addr(), "Intruder", &code);
    assert_eq!(first2, "CODE_REQUIRED");
    assert_eq!(second2, "DENY invalid_code");
    assert!(friends.lock().unwrap().find("Intruder").is_none());
    assert_eq!(tokens.lock().unwrap().active_count(), 0);

    // Log: join presente, token mai loggato.
    let logs = server.logs();
    assert!(logs.iter().any(|l| l.starts_with("join OfflineGuy ")));
    assert!(logs.iter().all(|l| !l.contains(&code)));
}

#[test]
fn token_scaduto_negato_e_non_riprovabile() {
    let tokens = Arc::new(Mutex::new(TokenStore::with_ttl(Duration::from_millis(50))));
    let friends = Arc::new(Mutex::new(FriendList::new()));
    let mut server =
        OfflineAuthServer::bind_with_state(cfg(), Arc::clone(&tokens), Arc::clone(&friends))
            .unwrap();
    server.start().unwrap();

    let code = server.generate_invite(HOST_WORLD.parse().unwrap());
    std::thread::sleep(Duration::from_millis(90));

    let (first, second) = join(server.local_addr(), "LateGuy", &code);
    assert_eq!(first, "CODE_REQUIRED");
    assert_eq!(second, "DENY expired_code");
    assert_eq!(tokens.lock().unwrap().active_count(), 0, "scaduto: rimosso");

    // Riprovare lo stesso codice non funziona (non è più nello store).
    let (_, third) = join(server.local_addr(), "LateGuy", &code);
    assert_eq!(third, "DENY invalid_code");
    assert!(friends.lock().unwrap().find("LateGuy").is_none());
}

#[test]
fn microsoft_pass_through_non_tocca_i_token() {
    let tokens = Arc::new(Mutex::new(TokenStore::new()));
    let friends = Arc::new(Mutex::new(FriendList::new()));
    let mut server =
        OfflineAuthServer::bind_with_state(cfg(), Arc::clone(&tokens), Arc::clone(&friends))
            .unwrap();
    server.start().unwrap();
    let _pending = server.generate_invite(HOST_WORLD.parse().unwrap());

    let mut s = TcpStream::connect(server.local_addr()).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    send_line(&mut s, "LOGIN_MS Notch");
    assert_eq!(read_line(&mut s), "PASS_THROUGH microsoft");

    assert_eq!(tokens.lock().unwrap().active_count(), 1, "Microsoft non consuma token");
    assert!(server.logs().iter().any(|l| l.starts_with("microsoft Notch")));
}

#[test]
fn lan_tunnel_raggiunge_il_server_auth() {
    let tokens = Arc::new(Mutex::new(TokenStore::new()));
    let friends = Arc::new(Mutex::new(FriendList::new()));
    let mut server =
        OfflineAuthServer::bind_with_state(cfg(), Arc::clone(&tokens), Arc::clone(&friends))
            .unwrap();
    server.start().unwrap();
    let code = server.generate_invite(HOST_WORLD.parse().unwrap());

    // Stessa rete Wi-Fi: connessione TCP diretta tramite il trait.
    let tunnel = LanTunnel::new();
    assert!(tunnel.is_available());
    let mut s = tunnel
        .connect(&server.local_addr().to_string(), Duration::from_secs(2))
        .unwrap();
    s.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    send_line(&mut s, "LOGIN LanGuy");
    assert_eq!(read_line(&mut s), "CODE_REQUIRED");
    send_line(&mut s, &format!("TOKEN {code}"));
    let welcome = read_line(&mut s);
    assert!(welcome.starts_with("WELCOME "));
    assert!(welcome.ends_with(" LanGuy offline"));
}

#[test]
fn redeem_diretto_condivide_lo_stesso_uuid_della_lista() {
    let mut tokens = TokenStore::new();
    let mut friends = FriendList::new();
    let f = friends.add("DirectGuy").unwrap();
    let code = tokens.generate(HOST_WORLD.parse().unwrap());
    let p = redeem_code(&mut tokens, &mut friends, "DirectGuy", &code).unwrap();
    assert_eq!(p.uuid, f.uuid);
    assert!(!p.online_mode);
    assert_eq!(p.host_world, HOST_WORLD.parse().unwrap());
}
