//! MiniWorld: nucleo di gioco di riferimento per verificare l'integrità.
//!
//! Implementa la logica che le ottimizzazioni visive NON devono mai rompere:
//! tick 20 TPS, propagazione redstone, pistoni che spingono block entity,
//! random tick (colture, farmland, decadimento foglie), luce cielo e spawn
//! mob, collisioni via raycast. Il rendering (mesh/bake) è derivato, mai
//! sorgente di verità: `set_block` invalida le mesh baked, non viceversa.

use std::collections::{HashMap, VecDeque};

use crate::bake::{BakeUpdate, StaticBakeManager};
use crate::blocks::{props, BlockId, Pos};
use crate::config::RenderConfig;
use crate::greedy::{greedy_mesh, Quad, Volume};
use crate::leaves::light_opacity;
use crate::xray::collision_solid;

/// Direzione per pistoni/observer.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Dir { NegX, PosX, NegY, PosY, NegZ, PosZ }

impl Dir {
    pub const fn delta(self) -> Pos {
        match self {
            Dir::NegX => [-1, 0, 0],
            Dir::PosX => [1, 0, 0],
            Dir::NegY => [0, -1, 0],
            Dir::PosY => [0, 1, 0],
            Dir::NegZ => [0, 0, -1],
            Dir::PosZ => [0, 0, 1],
        }
    }
}

const DIRS: [Pos; 6] = [
    [1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1],
];

fn add(a: Pos, b: Pos) -> Pos {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

/// Stato per-blocco (dati di GIOCO, indipendenti dal rendering).
#[derive(Clone, Copy, Debug)]
pub struct BlockMeta {
    pub power: u8,
    pub growth: u8,
    pub moisture: bool,
    pub facing: Dir,
    pub extended: bool,
    /// Distanza dal log più vicino (decadimento foglie, vanilla: >6 decade).
    pub distance: u8,
    /// Cassa/shulker aperta.
    pub open: bool,
}

impl Default for BlockMeta {
    fn default() -> Self {
        Self {
            power: 0, growth: 0, moisture: false, facing: Dir::PosX,
            extended: false, distance: 0, open: false,
        }
    }
}

const DEFAULT_META: BlockMeta = BlockMeta {
    power: 0, growth: 0, moisture: false, facing: Dir::PosX,
    extended: false, distance: 0, open: false,
};

/// Mondo voxel minimale con logica di gioco vanilla-like.
pub struct MiniWorld {
    blocks: HashMap<Pos, BlockId>,
    meta: HashMap<Pos, BlockMeta>,
    pending: VecDeque<Pos>,
    /// Baking mesh statiche (solo rendering).
    pub bake: StaticBakeManager,
    /// Config di rendering (solo rendering).
    pub cfg: RenderConfig,
    /// Log del motore (avvisi X-Ray, ecc.).
    pub log: Vec<String>,
    rng: u64,
    /// Tick di gioco eseguiti (20 TPS in produzione).
    pub game_ticks: u64,
}

impl Volume for MiniWorld {
    fn block(&self, p: Pos) -> BlockId {
        self.blocks.get(&p).copied().unwrap_or(BlockId::Air)
    }
}

impl MiniWorld {
    pub fn new(cfg: RenderConfig) -> Self {
        Self::with_seed(cfg, 0x5EED_1234)
    }

    pub fn with_seed(cfg: RenderConfig, seed: u64) -> Self {
        let baking = cfg.static_baking;
        Self {
            blocks: HashMap::new(),
            meta: HashMap::new(),
            pending: VecDeque::new(),
            bake: StaticBakeManager::new(baking),
            cfg,
            log: Vec::new(),
            rng: seed,
            game_ticks: 0,
        }
    }

    /// Piazza un blocco e propaga i block update (vicini + baking).
    /// È la sorgente di verità: il rendering si adatta, mai il contrario.
    pub fn set_block(&mut self, p: Pos, id: BlockId) {
        if id == BlockId::Air {
            self.blocks.remove(&p);
        } else {
            self.blocks.insert(p, id);
            self.meta.entry(p).or_default();
        }
        // Invalidazione mesh statica SAME-FRAME (pistone/hopper/apertura).
        self.bake.on_block_update(p);
        self.pending.push_back(p);
        for d in DIRS {
            self.pending.push_back(add(p, d));
        }
    }

    pub fn meta(&self, p: Pos) -> &BlockMeta {
        self.meta.get(&p).unwrap_or(&DEFAULT_META)
    }

    pub fn meta_mut(&mut self, p: Pos) -> &mut BlockMeta {
        self.meta.entry(p).or_default()
    }

    /// Toggle X-Ray con log di avviso obbligatorio.
    pub fn set_xray(&mut self, on: bool) {
        if let Some(warning) = self.cfg.set_xray_enabled(on) {
            self.log.push(warning.to_string());
        }
    }

    /// Notifica un block update generico (apertura cassa, hopper, vicino).
    pub fn block_update(&mut self, p: Pos) -> BakeUpdate {
        let r = self.bake.on_block_update(p);
        self.pending.push_back(p);
        for d in DIRS {
            self.pending.push_back(add(p, d));
        }
        r
    }

    /// Cuoci la mesh di una cassa/shulker chiusa (helper di rendering).
    pub fn bake_closed_container(&mut self, p: Pos) -> bool {
        let id = self.block(p);
        let inactive = !self.meta(p).open;
        self.bake.try_bake(p, id, inactive)
    }

    /// Alimenta un cavo redstone.
    pub fn power_wire(&mut self, p: Pos, level: u8) {
        self.meta_mut(p).power = level;
        self.pending.push_back(p);
    }

    fn next_rand(&mut self) -> u64 {
        self.rng = self.rng.wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        self.rng >> 33
    }

    fn is_powered(&self, p: Pos) -> bool {
        DIRS.iter().any(|d| {
            let n = add(p, *d);
            match self.block(n) {
                BlockId::RedstoneBlock => true,
                BlockId::RedstoneWire => self.meta(n).power > 0,
                _ => false,
            }
        })
    }

    /// Tick di gioco (20 TPS): redstone + pistoni. NESSUNA logica di rendering.
    pub fn tick(&mut self) {
        self.game_ticks += 1;
        let queue: Vec<Pos> = self.pending.drain(..).collect();
        for p in queue {
            match self.block(p) {
                BlockId::RedstoneBlock => {
                    for d in DIRS {
                        let n = add(p, d);
                        if self.block(n) == BlockId::RedstoneWire
                            && self.meta(n).power < 15
                        {
                            self.meta_mut(n).power = 15;
                            self.pending.push_back(n);
                        }
                    }
                }
                BlockId::RedstoneWire => {
                    let power = self.meta(p).power;
                    if power > 0 {
                        for d in DIRS {
                            let n = add(p, d);
                            match self.block(n) {
                                BlockId::RedstoneWire => {
                                    if self.meta(n).power + 1 < power {
                                        self.meta_mut(n).power = power - 1;
                                        self.pending.push_back(n);
                                    }
                                }
                                BlockId::Piston => self.pending.push_back(n),
                                _ => {}
                            }
                        }
                    }
                }
                BlockId::Piston => {
                    if !self.meta(p).extended && self.is_powered(p) {
                        self.extend_piston(p);
                    }
                }
                _ => {}
            }
        }
    }

    fn extend_piston(&mut self, p: Pos) {
        let facing = self.meta(p).facing;
        let front = add(p, facing.delta());
        let front_id = self.block(front);
        self.meta_mut(p).extended = true;
        if front_id == BlockId::Air {
            self.set_block(front, BlockId::PistonHead);
            return;
        }
        let pr = props(front_id);
        // Vanilla: le block entity pushabili (cassa/shulker) si muovono.
        if pr.unpushable || front_id == BlockId::Piston || front_id == BlockId::PistonHead {
            return;
        }
        let dest = add(front, facing.delta());
        let dest_id = self.block(dest);
        if dest_id != BlockId::Air && props(dest_id).solid {
            return; // destinazione occupata: il pistone non spinge
        }
        let moved_meta = *self.meta(front);
        // set_block invalida le mesh baked di partenza e destinazione:
        // la cassa torna dinamica nello STESSO tick/frame.
        self.set_block(dest, front_id);
        *self.meta_mut(dest) = moved_meta;
        self.set_block(front, BlockId::PistonHead);
    }

    /// Random tick vanilla-like: colture, idratazione farmland, decadimento
    /// foglie. Indipendente dalla config di rendering.
    pub fn random_tick(&mut self, n: u32) {
        let tickable: Vec<Pos> = self.blocks.iter()
            .filter(|(_, id)| props(**id).random_tick)
            .map(|(p, _)| *p)
            .collect();
        if tickable.is_empty() {
            return;
        }
        for _ in 0..n {
            let p = tickable[(self.next_rand() as usize) % tickable.len()];
            match self.block(p) {
                BlockId::Wheat => {
                    let below = add(p, [0, -1, 0]);
                    if self.block(below) == BlockId::Farmland && self.meta(below).moisture {
                        let g = self.meta(p).growth;
                        self.meta_mut(p).growth = (g + 1).min(7);
                    }
                }
                BlockId::Farmland => {
                    let mut moist = false;
                    'scan: for dx in -4..=4 {
                        for dz in -4..=4 {
                            for dy in 0..=1 {
                                if self.block(add(p, [dx, dy, dz])) == BlockId::Water {
                                    moist = true;
                                    break 'scan;
                                }
                            }
                        }
                    }
                    self.meta_mut(p).moisture = moist;
                }
                BlockId::Leaves => {
                    if self.meta(p).distance > 6 {
                        self.set_block(p, BlockId::Air);
                    }
                }
                _ => {}
            }
        }
    }

    /// Luce cielo nel punto: attenuazione dei blocchi sopra (proprietà di
    /// gioco, indipendente dal rendering).
    pub fn light_level(&self, p: Pos) -> u8 {
        let mut sum = 0u16;
        for y in (p[1] + 1)..=64 {
            sum += light_opacity(self.block([p[0], y, p[2]])) as u16;
        }
        15u16.saturating_sub(sum) as u8
    }

    /// Condizione di spawn mob vanilla-like (luce < 8, solido sotto, aria).
    pub fn can_mob_spawn(&self, p: Pos) -> bool {
        self.block(p) == BlockId::Air
            && collision_solid(self.block(add(p, [0, -1, 0])))
            && self.light_level(p) < 8
    }

    /// Raycast di collisione: usa SOLO proprietà solide, mai la config di
    /// rendering. Un blocco nascosto dall'X-Ray viene comunque colpito.
    pub fn raycast(&self, origin: [f32; 3], dir: [f32; 3], max_dist: f32) -> Option<Pos> {
        let len = (dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]).sqrt();
        if len == 0.0 {
            return None;
        }
        let d = [dir[0] / len, dir[1] / len, dir[2] / len];
        let mut t = 0.0f32;
        while t < max_dist {
            let p = [
                (origin[0] + d[0] * t).floor() as i32,
                (origin[1] + d[1] * t).floor() as i32,
                (origin[2] + d[2] * t).floor() as i32,
            ];
            if collision_solid(self.block(p)) {
                return Some(p);
            }
            t += 0.05;
        }
        None
    }

    /// Mesh di una regione (solo rendering): X-Ray + hollow + greedy + bake.
    pub fn mesh_region(&self, min: Pos, dims: [usize; 3]) -> Vec<Quad> {
        let region = RegionVol { world: self, min, dims };
        greedy_mesh(&region, dims, &self.cfg)
    }
}

/// Volume limitato per il meshing (coordinate relative a `min`).
struct RegionVol<'a> {
    world: &'a MiniWorld,
    min: Pos,
    dims: [usize; 3],
}

impl Volume for RegionVol<'_> {
    fn block(&self, p: Pos) -> BlockId {
        if p[0] < 0 || p[1] < 0 || p[2] < 0
            || p[0] >= self.dims[0] as i32
            || p[1] >= self.dims[1] as i32
            || p[2] >= self.dims[2] as i32
        {
            return BlockId::Air;
        }
        self.world.block(add(self.min, p))
    }
}