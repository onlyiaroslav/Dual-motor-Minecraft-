//! Test di convivenza Fase 5 + Fase 6: un giocatore offline entrato col codice
//! monouso deve vedere le ottimizzazioni visive (X-Ray, casse baked) senza
//! causare crash o desync nella logica di gioco.
//!
//! Il mondo è la sorgente di verità: il join tocca solo lista amici/token,
//! mai blocchi, tick, redstone o random tick.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use starling_features::config::LeavesMode;
use starling_features::world::BlockMeta;
use starling_features::{
    render_visible, BlockId, Dir, MiniWorld, RenderConfig, Volume,
};
use starling_social::{AuthConfig, FriendList, OfflineAuthServer, TokenStore};

const HOST_WORLD: &str = "10.0.0.7:25565";

fn all_on() -> RenderConfig {
    let mut cfg = RenderConfig::default();
    cfg.set_xray_enabled(true);
    cfg.leaves_mode = LeavesMode::Hollow;
    cfg.greedy_meshing = true;
    cfg.static_baking = true;
    cfg
}

fn build_world(cfg: RenderConfig) -> MiniWorld {
    let mut w = MiniWorld::with_seed(cfg, 0xC0FFEE);
    // Scena: pietra nascosta dall'X-Ray, minerale visibile, cassa statica,
    // catena redstone → pistone → cassa che verrà spinta.
    w.set_block([0, 0, 0], BlockId::Stone);
    w.set_block([1, 0, 0], BlockId::DiamondOre);
    w.set_block([2, 0, 0], BlockId::Chest);
    w.set_block([3, 0, 0], BlockId::RedstoneBlock);
    w.set_block([4, 0, 0], BlockId::RedstoneWire);
    w.set_block([5, 0, 0], BlockId::Piston);
    w.meta_mut([5, 0, 0]).facing = Dir::PosX;
    w.set_block([6, 0, 0], BlockId::Chest);
    // Farm idratata.
    w.set_block([0, 2, 0], BlockId::Farmland);
    w.set_block([2, 2, 0], BlockId::Water);
    w.set_block([0, 3, 0], BlockId::Wheat);
    w
}

fn meta_eq(a: &BlockMeta, b: &BlockMeta) -> bool {
    a.power == b.power
        && a.growth == b.growth
        && a.moisture == b.moisture
        && a.facing == b.facing
        && a.extended == b.extended
        && a.distance == b.distance
        && a.open == b.open
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

#[test]
fn offline_join_convive_con_xray_e_bake_senza_desync() {
    let tokens = Arc::new(Mutex::new(TokenStore::new()));
    let friends = Arc::new(Mutex::new(FriendList::new()));
    friends.lock().unwrap().add("Host").unwrap();

    let mut server = OfflineAuthServer::bind_with_state(
        AuthConfig {
            read_timeout: Duration::from_secs(3),
            ..AuthConfig::default()
        },
        Arc::clone(&tokens),
        Arc::clone(&friends),
    )
    .unwrap();
    server.start().unwrap();

    let mut control = build_world(all_on());
    let mut joined = build_world(all_on());

    // Cassa statica chiusa: baked in entrambi i mondi (solo rendering).
    assert!(joined.bake_closed_container([2, 0, 0]));
    assert!(joined.bake_closed_container([6, 0, 0]));
    assert!(joined.bake.is_baked([2, 0, 0]));
    assert!(joined.bake.is_baked([6, 0, 0]));

    // Join offline REALE attraverso il server TCP, a mondo già costruito.
    let code = server.generate_invite(HOST_WORLD.parse().unwrap());
    let mut s = TcpStream::connect(server.local_addr()).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    send_line(&mut s, "LOGIN OfflineGuy");
    assert_eq!(read_line(&mut s), "CODE_REQUIRED");
    send_line(&mut s, &format!("TOKEN {code}"));
    let welcome = read_line(&mut s);
    assert!(welcome.starts_with("WELCOME "));
    assert!(welcome.ends_with(" OfflineGuy offline"));
    let player_uuid = welcome.split(' ').nth(1).unwrap().to_string();

    // Il join non altera il mondo: 20 tick identici al controllo.
    for _ in 0..20 {
        control.tick();
        joined.tick();
        control.random_tick(200);
        joined.random_tick(200);
    }
    assert_eq!(control.game_ticks, joined.game_ticks);
    for p in [
        [0, 0, 0],
        [1, 0, 0],
        [2, 0, 0],
        [3, 0, 0],
        [4, 0, 0],
        [5, 0, 0],
        [6, 0, 0],
        [7, 0, 0],
        [0, 2, 0],
        [2, 2, 0],
        [0, 3, 0],
    ] {
        assert_eq!(control.block(p), joined.block(p), "blocco diverso in {p:?}");
        assert!(
            meta_eq(control.meta(p), joined.meta(p)),
            "meta diversa in {p:?}"
        );
    }

    // Pistone: la cassa baked è stata spinta e la mesh statica invalidata
    // nello stesso tick; la cassa alla nuova posizione è dinamica.
    assert_eq!(joined.block([7, 0, 0]), BlockId::Chest);
    assert_eq!(joined.block([6, 0, 0]), BlockId::PistonHead);
    assert!(!joined.bake.is_baked([6, 0, 0]));
    assert!(joined.bake.render_dynamic([7, 0, 0]));
    // La cassa statica non toccata resta baked.
    assert!(joined.bake.is_baked([2, 0, 0]));

    // X-Ray attivo: solo minerali/fluidi nella mesh, pietra invisibile,
    // ma la collisione resta invariata (già garantita da starling-features).
    assert!(!render_visible(BlockId::Stone, &joined.cfg));
    assert!(render_visible(BlockId::DiamondOre, &joined.cfg));
    let q_xray = joined.mesh_region([-1, -1, -1], [10, 6, 3]);
    assert!(q_xray.iter().all(|q| q.block != BlockId::Stone));
    assert!(q_xray.iter().any(|q| q.block == BlockId::DiamondOre));
    assert!(q_xray.iter().any(|q| q.block == BlockId::Water));
    assert!(q_xray.iter().all(|q| q.block != BlockId::Chest), "X-Ray: niente casse");

    // X-Ray spento: le casse statiche sono renderizzate e le mesh dei due
    // mondi (join vs controllo) sono identiche → nessun desync visivo.
    control.set_xray(false);
    joined.set_xray(false);
    let q_control = control.mesh_region([-1, -1, -1], [10, 6, 3]);
    let q_joined = joined.mesh_region([-1, -1, -1], [10, 6, 3]);
    assert_eq!(q_control, q_joined);
    assert!(q_joined.iter().any(|q| q.block == BlockId::Chest));

    // Farm: crescita e idratazione identiche al controllo.
    assert!(joined.meta([0, 3, 0]).growth > 0, "la farm deve crescere");
    assert_eq!(
        control.meta([0, 3, 0]).growth,
        joined.meta([0, 3, 0]).growth
    );
    assert!(joined.meta([0, 2, 0]).moisture);

    // Profilo e lista amici coerenti; il token non è più attivo.
    {
        let f = friends.lock().unwrap();
        assert_eq!(f.find("OfflineGuy").unwrap().uuid, player_uuid);
        assert!(f.find("OfflineGuy").unwrap().online);
    }
    assert_eq!(tokens.lock().unwrap().active_count(), 0);
    assert!(server.logs().iter().any(|l| l.starts_with("join OfflineGuy ")));
}
