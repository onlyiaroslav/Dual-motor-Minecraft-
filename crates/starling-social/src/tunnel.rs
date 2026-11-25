//! Tunneling per il networking oltre la LAN.
//!
//! Contesto: giocatori dietro **NAT/CGNAT** (es. Russia) non possono
//! ricevere connessioni dirette. La soluzione completa richiede un relay o
//! hole-punching (iroh/libp2p); qui forniamo:
//!
//! - [`LanTunnel`]: connessione TCP diretta, modalità **LAN** (stessa rete
//!   Wi-Fi). Implementata e testata.
//! - [`FutureP2pTunnel`]: stub esplicito che risponde "non disponibile".
//!   Il trait [`P2pTunnel`] è già la seam su cui innestare iroh/libp2p senza
//!   toccare il resto del layer social.
//!
//! Il tunnel è **solo trasporto**: non entra nel percorso del bridge a 8 bit
//! né nella logica di gioco.

use std::io;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

/// Tipo di tunnel.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TunnelKind {
    /// TCP diretto sulla stessa rete locale.
    Lan,
    /// Relay/hole-punching P2P (futuro).
    P2p,
}

/// Errori di tunneling.
#[derive(Debug)]
pub enum TunnelError {
    /// Modalità non ancora implementata.
    Unsupported(&'static str),
    /// Indirizzo peer non valido o non risolvibile.
    InvalidPeer(String),
    /// Timeout di connessione.
    Timeout,
    /// Errore di rete.
    Io(io::Error),
}

impl std::fmt::Display for TunnelError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Unsupported(m) => write!(f, "tunnel non disponibile: {m}"),
            Self::InvalidPeer(p) => write!(f, "peer non valido: {p}"),
            Self::Timeout => write!(f, "timeout di connessione"),
            Self::Io(e) => write!(f, "errore di rete: {e}"),
        }
    }
}

impl std::error::Error for TunnelError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<io::Error> for TunnelError {
    fn from(e: io::Error) -> Self {
        if e.kind() == io::ErrorKind::TimedOut {
            Self::Timeout
        } else {
            Self::Io(e)
        }
    }
}

/// Interfaccia comune per i trasporti verso l'host.
///
/// Implementazioni attuali: [`LanTunnel`]. Implementazione futura:
/// [`FutureP2pTunnel`] (iroh/libp2p). Il resto del codice social dipende solo
/// da questo trait.
pub trait P2pTunnel: Send + Sync {
    /// Tipo di trasporto.
    fn kind(&self) -> TunnelKind;

    /// Il trasporto è utilizzabile in questo build/ambiente?
    fn is_available(&self) -> bool;

    /// Apre una connessione TCP verso `peer` (`host:porta` o `SocketAddr`).
    fn connect(&self, peer: &str, timeout: Duration) -> Result<TcpStream, TunnelError>;
}

/// Modalità LAN: connessione TCP diretta (stessa rete Wi-Fi).
#[derive(Clone, Copy, Debug, Default)]
pub struct LanTunnel;

impl LanTunnel {
    pub const fn new() -> Self {
        Self
    }

    fn resolve(peer: &str) -> Result<SocketAddr, TunnelError> {
        if let Ok(addr) = peer.parse::<SocketAddr>() {
            return Ok(addr);
        }
        peer.to_socket_addrs()
            .map_err(|_| TunnelError::InvalidPeer(peer.to_string()))?
            .next()
            .ok_or_else(|| TunnelError::InvalidPeer(peer.to_string()))
    }
}

impl P2pTunnel for LanTunnel {
    fn kind(&self) -> TunnelKind {
        TunnelKind::Lan
    }

    fn is_available(&self) -> bool {
        true
    }

    fn connect(&self, peer: &str, timeout: Duration) -> Result<TcpStream, TunnelError> {
        let addr = Self::resolve(peer)?;
        let stream = TcpStream::connect_timeout(&addr, timeout).map_err(TunnelError::from)?;
        let _ = stream.set_nodelay(true);
        Ok(stream)
    }
}

/// Stub del futuro tunnel P2P (iroh/libp2p) per NAT/CGNAT.
///
/// Non implementato di proposito in questa fase: risponde sempre
/// [`TunnelError::Unsupported`]. La UI può usarlo per mostrare "richiede
/// relay P2P (in arrivo)" senza cambiare l'API.
#[derive(Clone, Copy, Debug, Default)]
pub struct FutureP2pTunnel;

impl FutureP2pTunnel {
    pub const fn new() -> Self {
        Self
    }
}

impl P2pTunnel for FutureP2pTunnel {
    fn kind(&self) -> TunnelKind {
        TunnelKind::P2p
    }

    fn is_available(&self) -> bool {
        false
    }

    fn connect(&self, _peer: &str, _timeout: Duration) -> Result<TcpStream, TunnelError> {
        Err(TunnelError::Unsupported(
            "tunnel P2P (iroh/libp2p) non ancora implementato: usare la modalità LAN",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    #[test]
    fn lan_connette_alla_porta_locale() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut buf = [0u8; 5];
            s.read_exact(&mut buf).unwrap();
            s.write_all(b"world").unwrap();
        });

        let tunnel = LanTunnel::new();
        assert_eq!(tunnel.kind(), TunnelKind::Lan);
        assert!(tunnel.is_available());
        let mut s = tunnel
            .connect(&addr.to_string(), Duration::from_secs(2))
            .unwrap();
        s.write_all(b"hello").unwrap();
        let mut buf = [0u8; 5];
        s.read_exact(&mut buf).unwrap();
        assert_eq!(&buf, b"world");
        server.join().unwrap();
    }

    #[test]
    fn lan_peer_non_valido() {
        let tunnel = LanTunnel::new();
        assert!(matches!(
            tunnel.connect("non-un-host:porta-invalida", Duration::from_millis(100)),
            Err(TunnelError::InvalidPeer(_))
        ));
    }

    #[test]
    fn tunnel_p2p_futuro_non_disponibile() {
        let tunnel = FutureP2pTunnel::new();
        assert_eq!(tunnel.kind(), TunnelKind::P2p);
        assert!(!tunnel.is_available());
        assert!(matches!(
            tunnel.connect("peer", Duration::from_secs(1)),
            Err(TunnelError::Unsupported(_))
        ));
    }
}
