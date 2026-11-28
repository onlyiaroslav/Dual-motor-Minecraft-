//! Game loop e stato di gioco: mondo voxel, movimento con collisioni,
//! tick fissi a 20 TPS e mesh derivata da `starling-features`.
//!
//! Il game loop segue il pacing di `crates/bridge-jni/examples/game_loop.rs`
//! (budget di frame, poll del ring una volta per frame) e chiama
//! [`crate::bridge::poll`] → `starling_jvm_poll_frame` → `nativePoll`.
//!
//! PRINCIPIO (come `starling-features`): il mondo è la sorgente di verità.
//! La mesh è derivata: `set_block`/`block_update` invalidano, mai il contrario.
//! Le collisioni usano SOLO [`collision_solid`]: l'X-Ray non le tocca.
//! Gli ID azione del bridge non carryano payload nel protocollo a 8 bit:
//! vengono contabilizzati (hook HUD/tick/suoni), mai tradotti in mutazioni
//! arbitrarie dello stato di gioco.

use std::time::{Duration, Instant};

use bytemuck::{Pod, Zeroable};
use starling_features::{
    collision_solid, BlockId, MiniWorld, Pos, Quad, RenderConfig, Volume, XRAY_WARNING,
};

use crate::bridge;
use crate::camera::Camera;
use crate::touch::{screen_direction, Phase, TouchControls};

/// Dimensioni del mondo demo (coordinate 0..DIMS).
pub const DIMS: [usize; 3] = [32, 24, 32];
/// FOV verticale in gradi (vanilla default 70).
pub const FOV: f32 = 70.0;
/// Distanza di rendering/nebbia in blocchi.
pub const FAR: f32 = 96.0;

/// Tick di gioco: 20 TPS, come vanilla (mai alterato dal rendering).
const TICK: Duration = Duration::from_millis(50);
/// Gravità e salto (vanilla-like: ~1.25 blocchi di salto).
const GRAVITY: f32 = 28.0;
const JUMP_SPEED: f32 = 8.5;
/// Velocità di camminata vanilla (~4.317 blocchi/s).
const WALK_SPEED: f32 = 4.3;
/// Reach di interazione (vanilla survival ~4.5; qui 6 per il demo).
const REACH: f32 = 6.0;
/// Hitbox del giocatore: 0.6 × 1.8 × 0.6, occhio a 1.62 (vanilla).
const HALF: f32 = 0.3;
const HEIGHT: f32 = 1.8;
const EYE: f32 = 1.62;
/// Sotto-passi di movimento ≤ 0.05 blocchi (niente tunneling nei muri).
const STEP: f32 = 0.05;

/// Vertice della mesh voxel: posizione + normale + colore per blocco.
#[derive(Clone, Copy, Pod, Zeroable)]
#[repr(C)]
pub struct Vertex {
    pub pos: [f32; 3],
    pub normal: [f32; 3],
    pub color: [f32; 3],
}

/// Stato completo del gioco, indipendente da wgpu (testabile su host).
pub struct Game {
    pub world: MiniWorld,
    pub camera: Camera,
    pub touch: TouchControls,
    /// Piedi del giocatore (l'occhio è `feet + EYE`).
    feet: [f32; 3],
    vel_y: f32,
    on_ground: bool,
    jump_queued: bool,
    surface: [f32; 2],
    vertices: Vec<Vertex>,
    mesh_version: u64,
    dirty: bool,
    tick_acc: Duration,
    /// Statistiche del bridge (hook delle mod, nessun payload nel protocollo).
    pub bridge_polls: u64,
    pub bridge_bytes: u64,
    pub bridge_actions: u64,
    pub last_action: Option<bridge_u8::NativeAction>,
}

impl Game {
    /// Mondo demo deterministico + spawn al centro.
    pub fn new(width: u32, height: u32) -> Self {
        let mut world = MiniWorld::new(RenderConfig::default());
        generate(&mut world);
        let spawn_h = surface_height(16, 16);
        let feet = [16.5, (spawn_h + 1) as f32, 16.5];
        let mut game = Self {
            world,
            camera: Camera::new([feet[0], feet[1] + EYE, feet[2]], 0.0, 0.0),
            touch: TouchControls::default(),
            feet,
            vel_y: 0.0,
            on_ground: false,
            jump_queued: false,
            surface: [width.max(1) as f32, height.max(1) as f32],
            vertices: Vec::new(),
            mesh_version: 0,
            dirty: true,
            tick_acc: Duration::ZERO,
            bridge_polls: 0,
            bridge_bytes: 0,
            bridge_actions: 0,
            last_action: None,
        };
        game.touch.set_surface(width, height);
        game.rebuild_mesh();
        game
    }

    /// Dimensioni della superficie (per touch e proiezione).
    pub fn resize(&mut self, width: u32, height: u32) {
        self.surface = [width.max(1) as f32, height.max(1) as f32];
        self.touch.set_surface(width, height);
    }

    pub fn surface(&self) -> [f32; 2] {
        self.surface
    }

    /// Un evento touch (coordinate in pixel, come da `MotionEvent`).
    pub fn touch(&mut self, id: i32, phase: Phase, x: f32, y: f32) {
        let p = [x / self.surface[0], y / self.surface[1]];
        self.touch.update(id, phase, p);
    }

    /// Richiesta di salto (pulsante a schermo).
    pub fn jump(&mut self) {
        self.jump_queued = true;
    }

    /// Toggle X-Ray: ritorna l'avviso obbligatorio alla transizione off→on.
    /// Solo rendering: collisioni e tick restano vanilla.
    pub fn set_xray(&mut self, on: bool) -> Option<&'static str> {
        let was = self.world.cfg.xray_enabled;
        self.world.set_xray(on);
        self.dirty = true;
        if on && !was {
            Some(XRAY_WARNING)
        } else {
            None
        }
    }

    /// Un frame: poll del bridge, input, movimento, tick 20 TPS, mesh.
    /// `dt` è il tempo dall'ultimo frame (clampato per non avvitarsi).
    pub fn update(&mut self, dt: Duration) {
        let dt = dt.min(Duration::from_millis(100));
        let secs = dt.as_secs_f32();

        // 1. Bridge a 8 bit: una poll per frame (nativePoll via host C).
        self.bridge_polls += 1;
        let n = bridge::poll();
        if n > 0 {
            self.bridge_bytes += n as u64;
        }
        for id in bridge::take_actions() {
            if let Ok(action) = bridge_u8::NativeAction::try_from(id as u16) {
                self.bridge_actions += 1;
                self.last_action = Some(action);
            }
        }

        // 2. Camera dal drag del touch.
        let look = self.touch.drain_look();
        self.camera.look(look[0], look[1]);

        // 3. Movimento: stick + gravità + collisioni (solo proprietà solide).
        let mv = self.touch.move_vec;
        let f = self.camera.forward_horizontal();
        let r = self.camera.right_horizontal();
        let mut dir = [
            r[0] * mv[0] + f[0] * mv[1],
            0.0,
            r[2] * mv[0] + f[2] * mv[1],
        ];
        let len = (dir[0] * dir[0] + dir[2] * dir[2]).sqrt();
        if len > 1.0 {
            dir[0] /= len;
            dir[2] /= len;
        }
        self.vel_y = (self.vel_y - GRAVITY * secs).max(-50.0);
        if self.jump_queued && self.on_ground {
            self.vel_y = JUMP_SPEED;
        }
        self.jump_queued = false;
        let delta = [dir[0] * WALK_SPEED * secs, self.vel_y * secs, dir[2] * WALK_SPEED * secs];
        self.move_with_collisions(delta);
        self.on_ground = self.collides([self.feet[0], self.feet[1] - STEP, self.feet[2]]);
        self.camera.position = [self.feet[0], self.feet[1] + EYE, self.feet[2]];

        // 4. Interazioni sul mondo: tap = rompi, hold = piazza.
        if let Some(p) = self.touch.tap.take() {
            self.break_at(p);
        }
        if let Some(p) = self.touch.hold.take() {
            self.place_at(p);
        }

        // 5. Tick di gioco a 20 TPS fissi (indipendenti dal frame rate).
        self.tick_acc = (self.tick_acc + dt).min(Duration::from_millis(250));
        while self.tick_acc >= TICK {
            self.world.tick();
            self.world.random_tick(3);
            self.tick_acc -= TICK;
        }

        // 6. Mesh derivata: ricostruita solo se il mondo/config è cambiato.
        if self.dirty {
            self.rebuild_mesh();
        }
    }

    /// Mesh corrente (vertici triangoli) e versione per l'upload GPU.
    pub fn vertices(&self) -> &[Vertex] {
        &self.vertices
    }

    pub fn mesh_version(&self) -> u64 {
        self.mesh_version
    }

    // ------------------------------------------------------------------
    // Interni
    // ------------------------------------------------------------------

    /// AABB del giocatore contro i blocchi solidi (mai la config di render).
    fn collides(&self, feet: [f32; 3]) -> bool {
        let min = [feet[0] - HALF, feet[1], feet[2] - HALF];
        let max = [feet[0] + HALF, feet[1] + HEIGHT, feet[2] + HALF];
        for bx in (min[0].floor() as i32)..=(max[0].floor() as i32) {
            for by in (min[1].floor() as i32)..=(max[1].floor() as i32) {
                for bz in (min[2].floor() as i32)..=(max[2].floor() as i32) {
                    if collision_solid(self.world.block([bx, by, bz])) {
                        return true;
                    }
                }
            }
        }
        false
    }

    /// Integrazione per sotto-passi ≤ STEP, asse per asse: un asse bloccato
    /// non ferma gli altri (scivolamento lungo i muri, come vanilla).
    fn move_with_collisions(&mut self, delta: [f32; 3]) {
        let max = delta.iter().fold(0.0f32, |m, v| m.max(v.abs()));
        let steps = (max / STEP).ceil().max(1.0) as usize;
        for _ in 0..steps {
            for axis in 0..3 {
                let d = delta[axis] / steps as f32;
                if d == 0.0 {
                    continue;
                }
                let mut next = self.feet;
                next[axis] += d;
                if self.collides(next) {
                    if axis == 1 {
                        self.vel_y = 0.0;
                    }
                } else {
                    self.feet = next;
                }
            }
        }
    }

    /// Raggio dal punto toccato (la vista non gira: il tap agisce dove atterra).
    fn aim(&self, point: [f32; 2]) -> [f32; 3] {
        let (f, _, up) = self.camera.basis();
        screen_direction(f, up, point, self.surface[0] / self.surface[1], FOV)
    }

    fn break_at(&mut self, point: [f32; 2]) {
        let dir = self.aim(point);
        if let Some(p) = self.world.raycast(self.camera.position, dir, REACH) {
            self.world.set_block(p, BlockId::Air);
            self.dirty = true;
        }
    }

    fn place_at(&mut self, point: [f32; 2]) {
        let dir = self.aim(point);
        let Some(hit) = self.world.raycast(self.camera.position, dir, REACH) else { return };
        // La faccia libera più vicina all'occhio; mai dentro il giocatore.
        let neighbors: [Pos; 6] = [
            [1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1],
        ];
        let mut best: Option<(f32, Pos)> = None;
        for d in neighbors {
            let p = [hit[0] + d[0], hit[1] + d[1], hit[2] + d[2]];
            if self.world.block(p) != BlockId::Air {
                continue;
            }
            let center = [p[0] as f32 + 0.5, p[1] as f32 + 0.5, p[2] as f32 + 0.5];
            let feet = [center[0], center[1] - 0.5, center[2]];
            if self.collides(feet) {
                continue;
            }
            let dist = (0..3)
                .map(|i| (center[i] - self.camera.position[i]).powi(2))
                .sum::<f32>();
            if best.is_none_or(|(b, _)| dist < b) {
                best = Some((dist, p));
            }
        }
        if let Some((_, p)) = best {
            self.world.set_block(p, BlockId::Dirt);
            self.dirty = true;
        }
    }

    /// Mesh greedy dell'intero mondo demo (X-Ray/hollow/esclusioni sono
    /// applicati da `starling-features`, mai qui).
    fn rebuild_mesh(&mut self) {
        let quads = self.world.mesh_region([0, 0, 0], DIMS);
        self.vertices.clear();
        for q in &quads {
            push_quad(&mut self.vertices, q);
        }
        self.mesh_version += 1;
        self.dirty = false;
    }
}

/// Quad → 2 triangoli. Winding CCW rispetto alla normale (cull back-face):
/// per normale +asse i lati sono (du, dv), per -asse invertiti.
fn push_quad(out: &mut Vec<Vertex>, q: &Quad) {
    let color = block_color(q.block);
    let n = [q.normal[0] as f32, q.normal[1] as f32, q.normal[2] as f32];
    let a = q.pos;
    let b = [a[0] + q.du[0], a[1] + q.du[1], a[2] + q.du[2]];
    let c = [b[0] + q.dv[0], b[1] + q.dv[1], b[2] + q.dv[2]];
    let d = [a[0] + q.dv[0], a[1] + q.dv[1], a[2] + q.dv[2]];
    let sign = q.normal[0] + q.normal[1] + q.normal[2];
    let (t1, t2) = if sign > 0 { ([a, b, c], [a, c, d]) } else { ([a, d, c], [a, c, b]) };
    for t in [t1, t2] {
        for p in t {
            out.push(Vertex {
                pos: [p[0] as f32, p[1] as f32, p[2] as f32],
                normal: n,
                color,
            });
        }
    }
}

/// Palette per blocco (colori vanilla-like, niente texture nel demo).
fn block_color(id: BlockId) -> [f32; 3] {
    let rgb = |hex: u32| {
        [
            ((hex >> 16) & 0xFF) as f32 / 255.0,
            ((hex >> 8) & 0xFF) as f32 / 255.0,
            (hex & 0xFF) as f32 / 255.0,
        ]
    };
    match id {
        BlockId::Air => [0.0; 3],
        BlockId::Stone => rgb(0x7F7F7F),
        BlockId::Dirt => rgb(0x8B5A2B),
        BlockId::Netherrack => rgb(0x6B2222),
        BlockId::DiamondOre => rgb(0x4AEDD9),
        BlockId::GoldOre => rgb(0xFCEE4B),
        BlockId::IronOre => rgb(0xD8AF93),
        BlockId::CoalOre => rgb(0x3A3A3A),
        BlockId::Water => rgb(0x3F76E4),
        BlockId::Lava => rgb(0xD45A12),
        BlockId::Leaves => rgb(0x48B518),
        BlockId::Log => rgb(0x6B5130),
        BlockId::Chest => rgb(0xA0722A),
        BlockId::ShulkerBox => rgb(0x976797),
        BlockId::Piston => rgb(0x998159),
        BlockId::PistonHead => rgb(0xB09A6A),
        BlockId::RedstoneWire => rgb(0xC40000),
        BlockId::Repeater => rgb(0x9A5A5A),
        BlockId::Observer => rgb(0x6B6B6B),
        BlockId::Hopper => rgb(0x4C4C4C),
        BlockId::Farmland => rgb(0x6B4226),
        BlockId::Wheat => rgb(0xC8B23C),
        BlockId::Glass => rgb(0xC8E8F0),
        BlockId::Bedrock => rgb(0x333333),
        BlockId::RedstoneBlock => rgb(0xA91E1E),
    }
}

/// Altezza del terreno (deterministica, onde sinusoidali).
fn surface_height(x: i32, z: i32) -> i32 {
    5 + (((x as f32 * 0.35).sin() + (z as f32 * 0.30).cos()) * 1.5) as i32
}

/// Mondo demo: terreno con minerali (per l'X-Ray), stagno, alberi, farm,
/// un circuito redstone con pistone (per l'integrità dei tick), casse/vetro.
fn generate(world: &mut MiniWorld) {
    let mut rng: u64 = 0x5EED_1234;
    let mut next = move || {
        rng = rng
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        (rng >> 33) as u32
    };
    for x in 0..DIMS[0] as i32 {
        for z in 0..DIMS[2] as i32 {
            let h = surface_height(x, z);
            world.set_block([x, 0, z], BlockId::Bedrock);
            for y in 1..h - 1 {
                let r = next() % 100;
                let id = if y <= 2 && r < 3 {
                    BlockId::DiamondOre
                } else if y <= 3 && r < 6 {
                    BlockId::GoldOre
                } else if y <= 5 && r < 12 {
                    BlockId::IronOre
                } else if r < 20 {
                    BlockId::CoalOre
                } else {
                    BlockId::Stone
                };
                world.set_block([x, y, z], id);
            }
            for y in (h - 1)..=h {
                world.set_block([x, y, z], BlockId::Dirt);
            }
        }
    }

    // Stagno (acqua: fluido, visibile in X-Ray, non solido).
    for x in 4..=9 {
        for z in 4..=9 {
            for y in 5..=9 {
                world.set_block([x, y, z], BlockId::Air);
            }
            world.set_block([x, 3, z], BlockId::Dirt);
            world.set_block([x, 4, z], BlockId::Water);
        }
    }

    // Alberi (foglie: random tick, hollow/dithered a seconda della config).
    for (x, z) in [(8, 24), (24, 8), (26, 24)] {
        let h = surface_height(x, z);
        for y in (h + 1)..=(h + 3) {
            world.set_block([x, y, z], BlockId::Log);
        }
        for dx in -2..=2 {
            for dz in -2..=2 {
                for dy in 0..=1 {
                    if dx == 0 && dz == 0 && dy == 0 {
                        continue;
                    }
                    world.set_block([x + dx, h + 3 + dy, z + dz], BlockId::Leaves);
                }
            }
        }
        world.set_block([x, h + 5, z], BlockId::Leaves);
    }

    // Farm: farmland + grano + canale d'acqua (random tick e idratazione).
    for x in 14..=18 {
        let h = surface_height(x, 2);
        world.set_block([x, h, 1], BlockId::Water);
        for z in 2..=5 {
            let hz = surface_height(x, z);
            world.set_block([x, hz, z], BlockId::Farmland);
            world.set_block([x, hz + 1, z], BlockId::Wheat);
        }
    }

    // Redstone: cavo alimentato da un blocco, pistone che si estende al tick.
    let h = surface_height(20, 28);
    for x in 20..=26 {
        world.set_block([x, h + 1, 28], BlockId::RedstoneWire);
    }
    world.set_block([27, h + 1, 28], BlockId::RedstoneBlock);
    world.set_block([19, h + 1, 28], BlockId::Piston);

    // Vicino allo spawn: cassa (block entity, mai greedy-mergeata) e vetro.
    let hs = surface_height(14, 14);
    world.set_block([14, hs + 1, 14], BlockId::Chest);
    world.set_block([18, hs + 1, 14], BlockId::Glass);
}

/// Pacing del frame (come `bridge-jni/examples/game_loop.rs`): dorme il
/// tempo che avanza nel budget di 16.667 ms (60 FPS). Ritorna l'istante
/// iniziale del frame, da passare al frame successivo.
pub fn pace_frame(start: Instant, budget: Duration) {
    let elapsed = start.elapsed();
    if elapsed < budget {
        std::thread::sleep(budget - elapsed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn game() -> Game {
        Game::new(2400, 1080)
    }

    #[test]
    fn mondo_demo_ha_mesh_e_minerali() {
        let g = game();
        assert!(!g.vertices().is_empty());
        let quads = g.world.mesh_region([0, 0, 0], DIMS);
        assert!(quads.iter().any(|q| q.block == BlockId::DiamondOre));
        // Greedy attivo: il terreno pieno mergea (meno quad della mesh naive).
        let mut cfg = g.world.cfg.clone();
        cfg.greedy_meshing = false;
        let naive = starling_features::greedy_mesh(
            &g.world,
            DIMS,
            &cfg,
        );
        assert!(quads.len() < naive.len());
    }

    #[test]
    fn tick_20_tps_indipendente_dai_frame() {
        let mut g = game();
        let before = g.world.game_ticks;
        // 1 secondo in 60 frame → esattamente 20 tick.
        for _ in 0..60 {
            g.update(Duration::from_micros(16_667));
        }
        assert_eq!(g.world.game_ticks - before, 20);
    }

    #[test]
    fn gravita_attterra_e_salto_alza() {
        let mut g = game();
        for _ in 0..120 {
            g.update(Duration::from_millis(16));
        }
        assert!(g.on_ground, "il giocatore deve atterrare");
        let y0 = g.feet[1];
        g.jump();
        g.update(Duration::from_millis(16));
        for _ in 0..10 {
            g.update(Duration::from_millis(16));
        }
        assert!(g.feet[1] > y0 + 0.5, "il salto deve alzare: {} -> {}", y0, g.feet[1]);
    }

    #[test]
    fn stick_muove_avanti() {
        let mut g = game();
        for _ in 0..120 {
            g.update(Duration::from_millis(16));
        }
        let z0 = g.feet[2];
        // Stick avanti (zona sinistra, drag in su).
        g.touch(0, Phase::Down, 300.0, 800.0);
        g.touch(0, Phase::Move, 300.0, 600.0);
        for _ in 0..30 {
            g.update(Duration::from_millis(16));
        }
        assert!(g.feet[2] > z0 + 0.5, "yaw 0 = sud (+Z): {} -> {}", z0, g.feet[2]);
    }

    #[test]
    fn tap_rompe_il_blocco_mirato() {
        let mut g = game();
        for _ in 0..120 {
            g.update(Duration::from_millis(16));
        }
        // Guarda in basso: il tap al centro colpisce il terreno sotto i piedi.
        g.camera.look(0.0, 90.0);
        g.update(Duration::from_millis(16));
        let below = [
            g.feet[0].floor() as i32,
            (g.feet[1] - 0.5).floor() as i32,
            g.feet[2].floor() as i32,
        ];
        assert_ne!(g.world.block(below), BlockId::Air);
        let v0 = g.mesh_version();
        g.touch(1, Phase::Down, 1200.0, 540.0);
        g.touch(1, Phase::Up, 1200.0, 540.0);
        g.update(Duration::from_millis(16));
        assert_eq!(g.world.block(below), BlockId::Air, "tap deve rompere");
        assert!(g.mesh_version() > v0, "mesh invalidata same-frame");
    }

    #[test]
    fn xray_non_tocca_le_collisioni() {
        let mut g = game();
        let warning = g.set_xray(true);
        assert_eq!(warning, Some(XRAY_WARNING));
        assert!(g.world.log.iter().any(|l| l.contains("ban")));
        // La pietra nascosta resta solida: il raycast la colpisce lo stesso.
        g.camera.look(0.0, 90.0);
        g.update(Duration::from_millis(16));
        let dir = g.aim([0.5, 0.5]);
        let hit = g.world.raycast(g.camera.position, dir, REACH);
        assert!(hit.is_some(), "collisioni invariate in X-Ray");
        // E la mesh non contiene più pietra.
        let quads = g.world.mesh_region([0, 0, 0], DIMS);
        assert!(quads.iter().all(|q| q.block != BlockId::Stone));
        assert!(g.set_xray(true).is_none(), "avviso una sola volta");
    }

    #[test]
    fn frame_pacing_rispetta_il_budget() {
        let start = Instant::now();
        pace_frame(start, Duration::from_millis(20));
        assert!(start.elapsed() >= Duration::from_millis(20));
    }
}