//! Test di integrità: le ottimizzazioni visive NON devono rompere
//! pistoni, farm, decadimento foglie, luce o spawn mob.

use starling_features::config::LeavesMode;
use starling_features::{
    BlockId, Dir, MiniWorld, RenderConfig, Volume, XRAY_WARNING,
};

fn cfg_tutto_attivo() -> RenderConfig {
    let mut cfg = RenderConfig::default();
    cfg.set_xray_enabled(true);
    cfg.leaves_mode = LeavesMode::Hollow;
    cfg.greedy_meshing = true;
    cfg.static_baking = true;
    cfg
}

fn cfg_tutto_spento() -> RenderConfig {
    let mut cfg = RenderConfig::default();
    cfg.xray_enabled = false;
    cfg.leaves_mode = LeavesMode::Solid;
    cfg.greedy_meshing = false;
    cfg.static_baking = false;
    cfg
}

// --- X-Ray ---------------------------------------------------------------

#[test]
fn xray_logga_avviso_all_attivazione() {
    let mut w = MiniWorld::new(RenderConfig::default());
    w.set_xray(true);
    assert_eq!(w.log.len(), 1);
    assert_eq!(w.log[0], XRAY_WARNING);
    assert!(w.log[0].contains("ban"));
    // Riattivazione senza transizione: nessun doppione.
    w.set_xray(true);
    assert_eq!(w.log.len(), 1);
}

#[test]
fn xray_nasconde_la_pietra_ma_non_la_hitbox() {
    let mut w = MiniWorld::new(RenderConfig::default());
    w.set_block([0, 0, 0], BlockId::Stone);
    w.set_block([1, 0, 0], BlockId::DiamondOre);
    w.set_block([2, 0, 0], BlockId::Water);

    // Raycast con X-Ray spento: colpisce la pietra.
    let hit_off = w.raycast([-2.0, 0.5, 0.5], [1.0, 0.0, 0.0], 10.0);
    assert_eq!(hit_off, Some([0, 0, 0]));

    w.set_xray(true);
    // Mesh: niente pietra, sì minerale e acqua.
    let quads = w.mesh_region([-1, -1, -1], [5, 3, 3]);
    assert!(quads.iter().all(|q| q.block != BlockId::Stone));
    assert!(quads.iter().any(|q| q.block == BlockId::DiamondOre));
    assert!(quads.iter().any(|q| q.block == BlockId::Water));

    // Collisione IDENTICA con X-Ray attivo: la hitbox non è alterata.
    let hit_on = w.raycast([-2.0, 0.5, 0.5], [1.0, 0.0, 0.0], 10.0);
    assert_eq!(hit_on, hit_off);
}

// --- Pistone + cassa baked ------------------------------------------------

#[test]
fn pistone_spinge_cassa_baked_invalidazione_same_frame() {
    let mut w = MiniWorld::new(cfg_tutto_attivo());
    // Catena: redstone block → wire → pistone → cassa.
    w.set_block([0, 0, 0], BlockId::RedstoneBlock);
    w.set_block([1, 0, 0], BlockId::RedstoneWire);
    w.set_block([2, 0, 0], BlockId::Piston);
    w.meta_mut([2, 0, 0]).facing = Dir::PosX;
    w.set_block([3, 0, 0], BlockId::Chest);

    // Cassa chiusa → mesh statica baked nel chunk.
    assert!(w.bake_closed_container([3, 0, 0]));
    assert!(w.bake.is_baked([3, 0, 0]));

    // Tick di gioco (20 TPS): propagazione redstone + estensione pistone.
    for _ in 0..4 {
        w.tick();
    }

    // La cassa è stata spinta in (4,0,0), la testa del pistone è in (3,0,0).
    assert_eq!(w.block([4, 0, 0]), BlockId::Chest);
    assert_eq!(w.block([3, 0, 0]), BlockId::PistonHead);
    assert!(w.meta([2, 0, 0]).extended);

    // VINCOLO: la mesh statica è distrutta nello STESSO tick/frame,
    // la cassa alla nuova posizione è dinamica (mai baked di default).
    assert!(!w.bake.is_baked([3, 0, 0]));
    assert!(w.bake.render_dynamic([4, 0, 0]));

    // Il wire redstone non è mai mergeato nella mesh.
    let quads = w.mesh_region([0, -1, -1], [6, 3, 3]);
    for q in quads.iter().filter(|q| q.block == BlockId::RedstoneWire) {
        let a = (q.du[0].abs() + q.du[1].abs() + q.du[2].abs())
            * (q.dv[0].abs() + q.dv[1].abs() + q.dv[2].abs());
        assert_eq!(a, 1, "wire redstone: solo quad 1×1");
    }
}

#[test]
fn apertura_cassa_e_hopper_invalidano_la_mesh() {
    let mut w = MiniWorld::new(RenderConfig::default());
    w.set_block([0, 0, 0], BlockId::Chest);
    assert!(w.bake_closed_container([0, 0, 0]));

    // Apertura della cassa (interazione giocatore) → block update.
    w.meta_mut([0, 0, 0]).open = true;
    assert_eq!(
        w.block_update([0, 0, 0]),
        starling_features::BakeUpdate::Destroyed
    );
    assert!(w.bake.render_dynamic([0, 0, 0]));

    // Ri-bake dopo chiusura, poi update da hopper adiacente.
    w.meta_mut([0, 0, 0]).open = false;
    assert!(w.bake_closed_container([0, 0, 0]));
    w.set_block([1, 0, 0], BlockId::Hopper); // lo schedule aggiorna i vicini
    w.block_update([0, 0, 0]); // notifica trasferimento item hopper→cassa
    assert!(w.bake.render_dynamic([0, 0, 0]));
}

// --- Farm -----------------------------------------------------------------

fn build_farm(cfg: RenderConfig) -> MiniWorld {
    let mut w = MiniWorld::with_seed(cfg, 0xFA47);
    w.set_block([0, 0, 0], BlockId::Farmland);
    w.set_block([2, 0, 0], BlockId::Water); // entro 4 blocchi → idrata
    w.set_block([0, 1, 0], BlockId::Wheat);
    w
}

#[test]
fn farm_identica_con_ottimizzazioni_attive_e_spente() {
    let mut base = build_farm(cfg_tutto_spento());
    let mut opt = build_farm(cfg_tutto_attivo());

    for _ in 0..10 {
        base.tick();
        opt.tick();
        base.random_tick(100);
        opt.random_tick(100);
    }

    // Idratazione identica.
    assert!(base.meta([0, 0, 0]).moisture);
    assert_eq!(base.meta([0, 0, 0]).moisture, opt.meta([0, 0, 0]).moisture);
    // Crescita del grano identica (stesso seed, stesso random tick).
    let g_base = base.meta([0, 1, 0]).growth;
    let g_opt = opt.meta([0, 1, 0]).growth;
    assert!(g_base > 0, "la farm deve crescere");
    assert_eq!(g_base, g_opt, "le ottimizzazioni non devono alterare la farm");
    // Il grano è ancora lì (nessun culling di gioco).
    assert_eq!(opt.block([0, 1, 0]), BlockId::Wheat);
}

#[test]
fn farmland_e_colture_mai_mergeate() {
    let w = build_farm(cfg_tutto_attivo());
    let quads = w.mesh_region([-1, -1, -1], [5, 4, 3]);
    for q in &quads {
        if q.block == BlockId::Farmland || q.block == BlockId::Wheat {
            let a = (q.du[0].abs() + q.du[1].abs() + q.du[2].abs())
                * (q.dv[0].abs() + q.dv[1].abs() + q.dv[2].abs());
            assert_eq!(a, 1, "farmland/colture: solo quad 1×1");
        }
    }
}

// --- Foglie: decadimento e luce --------------------------------------------

#[test]
fn foglie_hollow_decadono_comunque() {
    let mut w = MiniWorld::new(cfg_tutto_attivo()); // hollow + xray
    w.set_block([0, 5, 0], BlockId::Leaves);
    w.meta_mut([0, 5, 0]).distance = 7; // niente log vicino → decade

    for _ in 0..50 {
        w.random_tick(20);
        if w.block([0, 5, 0]) == BlockId::Air {
            break;
        }
    }
    assert_eq!(
        w.block([0, 5, 0]),
        BlockId::Air,
        "il decadimento (random tick) deve avvenire anche in hollow"
    );
}

#[test]
fn luce_e_spawn_mob_invariati_con_hollow() {
    // Colonna di 9 foglie: luce a terra = 15 - 9 = 6 (< 8 → spawn possibile).
    let mut solid = MiniWorld::new(cfg_tutto_spento());
    let mut hollow = MiniWorld::new(cfg_tutto_attivo());
    for w in [&mut solid, &mut hollow] {
        w.set_block([0, -1, 0], BlockId::Stone);
        for y in 1..=9 {
            w.set_block([0, y, 0], BlockId::Leaves);
            w.meta_mut([0, y, 0]).distance = 1; // vicine al log: non decadono
        }
    }
    assert_eq!(solid.light_level([0, 0, 0]), 6);
    assert_eq!(hollow.light_level([0, 0, 0]), 6);
    assert_eq!(solid.can_mob_spawn([0, 0, 0]), hollow.can_mob_spawn([0, 0, 0]));
    assert!(hollow.can_mob_spawn([0, 0, 0]));

    // Il meshing hollow cula le foglie interne, ma il mondo è identico.
    // Confronto con Dithered (tutte le facce): hollow deve ridurle.
    solid.cfg.leaves_mode = LeavesMode::Dithered;
    let q_solid = solid.mesh_region([-1, -1, -1], [3, 12, 3]);
    let q_hollow = hollow.mesh_region([-1, -1, -1], [3, 12, 3]);
    assert!(q_hollow.len() < q_solid.len(), "hollow deve ridurre le facce");
    for y in 1..=9 {
        assert_eq!(solid.block([0, y, 0]), hollow.block([0, y, 0]));
    }
}