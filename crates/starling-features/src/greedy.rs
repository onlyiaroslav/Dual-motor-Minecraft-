//! Greedy meshing con esclusioni di gioco.
//!
//! Unisce facce adiacenti dello stesso blocco SOLO se il blocco è
//! [`greedy_mergeable`]: restano esclusi redstone, block entity, blocchi
//! ad aggiornamento frequente, random tick (colture/farmland), fluidi e
//! foglie. Vincolo: nessun breaking di pistoni, farm o observer.

use crate::blocks::{greedy_mergeable, props, BlockId, Pos};
use crate::config::{LeavesMode, RenderConfig};
use crate::leaves::visible_at;
use crate::xray::effective_opaque;

/// Accesso in sola lettura a un volume voxel.
pub trait Volume {
    /// Blocco alla posizione (Air fuori dai limiti).
    fn block(&self, pos: Pos) -> BlockId;
}

/// Quad meshato: origine + due lati + normale + blocco.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Quad {
    pub pos: Pos,
    pub du: Pos,
    pub dv: Pos,
    pub normal: Pos,
    pub block: BlockId,
}

/// Maschera di faccia: blocco + verso (+1 / -1 lungo l'asse dello sweep).
type MaskEntry = Option<(BlockId, i32)>;

/// Costruisce la mesh greedy di un volume `dims` (coordinate 0..dims).
///
/// `cfg` controlla X-Ray, hollow e l'abilitazione del merge. Con
/// `greedy_meshing = false` produce quad 1×1 (mesh naive, per test/debug).
pub fn greedy_mesh<V: Volume>(vol: &V, dims: [usize; 3], cfg: &RenderConfig) -> Vec<Quad> {
    let mut quads = Vec::new();
    let dims_i = [dims[0] as i32, dims[1] as i32, dims[2] as i32];

    for d in 0..3usize {
        let u = (d + 1) % 3;
        let v = (d + 2) % 3;
        let mut x = [0i32; 3];
        let mut q = [0i32; 3];
        q[d] = 1;

        let w = dims_i[u];
        let h = dims_i[v];
        if w == 0 || h == 0 {
            continue;
        }
        let mut mask: Vec<MaskEntry> = vec![None; (w * h) as usize];

        // Sweep da -1 a dims[d]: confronta slice adiacenti.
        x[d] = -1;
        while x[d] < dims_i[d] {
            // 1. Costruisci la maschera delle facce visive.
            let mut n = 0usize;
            for j in 0..h {
                for i in 0..w {
                    x[u] = i;
                    x[v] = j;
                    let a_pos = x;
                    let mut b_pos = x;
                    b_pos[d] += 1;
                    let a = vol.block(a_pos);
                    let b = vol.block(b_pos);
                    let a_vis = visible_at(vol, a_pos, cfg);
                    let b_vis = visible_at(vol, b_pos, cfg);
                    let a_op = effective_opaque(a, cfg);
                    let b_op = effective_opaque(b, cfg);
                    // Hollow: culling same-block tra foglie adiacenti
                    // (le facce interne del guscio non servono).
                    let same_leaves = a == BlockId::Leaves
                        && b == BlockId::Leaves
                        && cfg.leaves_mode == LeavesMode::Hollow;
                    mask[n] = if same_leaves {
                        None
                    } else if a_vis && !b_op {
                        Some((a, 1))
                    } else if b_vis && !a_op {
                        Some((b, -1))
                    } else {
                        None
                    };
                    n += 1;
                }
            }
            x[d] += 1;

            // 2. Merge greedy dei rettangoli nella maschera.
            n = 0;
            for j in 0..h {
                let mut i = 0;
                while i < w {
                    let entry = mask[n];
                    if entry.is_none() {
                        i += 1;
                        n += 1;
                        continue;
                    }
                    let (block, sign) = entry.unwrap();
                    let mergeable = cfg.greedy_meshing && greedy_mergeable(block);

                    // Larghezza: estendi finché stesso entry e mergeabile.
                    let mut width = 1i32;
                    if mergeable {
                        while i + width < w
                            && mask[n + width as usize] == entry
                        {
                            width += 1;
                        }
                    }

                    // Altezza: estendi la riga intera finché uguale.
                    let mut height = 1i32;
                    if mergeable {
                        'outer: while j + height < h {
                            for k in 0..width {
                                let idx = n + (height * w) as usize + k as usize;
                                if mask[idx] != entry {
                                    break 'outer;
                                }
                            }
                            height += 1;
                        }
                    }

                    // Emetti il quad.
                    let mut pos = [0i32; 3];
                    pos[d] = x[d];
                    pos[u] = i;
                    pos[v] = j;
                    let mut du = [0i32; 3];
                    du[u] = width;
                    let mut dv = [0i32; 3];
                    dv[v] = height;
                    let mut normal = [0i32; 3];
                    normal[d] = sign;
                    quads.push(Quad { pos, du, dv, normal, block });

                    // Azzera la regione consumata.
                    for hh in 0..height {
                        for ww in 0..width {
                            mask[n + (hh * w) as usize + ww as usize] = None;
                        }
                    }
                    i += width;
                    n += width as usize;
                }
            }
        }
    }
    quads
}

/// Opacità per il culling (ri-export comodo per i test).
pub fn block_opaque(id: BlockId) -> bool {
    props(id).opaque
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::LeavesMode;
    use std::collections::HashMap;

    struct MapVol(HashMap<Pos, BlockId>);
    impl Volume for MapVol {
        fn block(&self, p: Pos) -> BlockId {
            self.0.get(&p).copied().unwrap_or(BlockId::Air)
        }
    }

    fn solid_cube(size: i32, id: BlockId) -> MapVol {
        let mut m = HashMap::new();
        for x in 0..size {
            for y in 0..size {
                for z in 0..size {
                    m.insert([x, y, z], id);
                }
            }
        }
        MapVol(m)
    }

    #[test]
    fn cubo_pieno_6_facce_mergeate() {
        let vol = solid_cube(4, BlockId::Stone);
        let cfg = RenderConfig::default();
        let quads = greedy_mesh(&vol, [4, 4, 4], &cfg);
        assert_eq!(quads.len(), 6, "4×4×4 → 6 quad 4×4");
        for q in &quads {
            assert_eq!(q.block, BlockId::Stone);
            let area = (q.du[0].abs() + q.du[1].abs() + q.du[2].abs())
                * (q.dv[0].abs() + q.dv[1].abs() + q.dv[2].abs());
            assert_eq!(area, 16);
        }
    }

    #[test]
    fn naive_senza_greedy() {
        let vol = solid_cube(4, BlockId::Stone);
        let mut cfg = RenderConfig::default();
        cfg.greedy_meshing = false;
        let quads = greedy_mesh(&vol, [4, 4, 4], &cfg);
        assert_eq!(quads.len(), 6 * 16, "mesh naive: una quad per faccia");
    }

    #[test]
    fn redstone_mai_mergeato() {
        // Pavimento di pietra 4×4 con una riga di redstone wire sopra.
        let mut m = HashMap::new();
        for x in 0..4 {
            for z in 0..4 {
                m.insert([x, 0, z], BlockId::Stone);
            }
        }
        for x in 0..4 {
            m.insert([x, 1, 0], BlockId::RedstoneWire);
        }
        let vol = MapVol(m);
        let cfg = RenderConfig::default();
        let quads = greedy_mesh(&vol, [4, 2, 4], &cfg);
        let wire_quads: Vec<_> = quads
            .iter()
            .filter(|q| q.block == BlockId::RedstoneWire)
            .collect();
        // Ogni cella di wire resta separata (nessun merge tra celle).
        assert!(wire_quads.len() >= 4);
        for q in &wire_quads {
            let w = q.du[0].abs() + q.du[1].abs() + q.du[2].abs();
            let h = q.dv[0].abs() + q.dv[1].abs() + q.dv[2].abs();
            assert_eq!(w * h, 1, "wire: solo quad 1×1");
        }
        // La pietra sotto resta mergeata.
        let stone: Vec<_> = quads.iter().filter(|q| q.block == BlockId::Stone).collect();
        assert!(stone.iter().any(|q| {
            let w = q.du[0].abs() + q.du[1].abs() + q.du[2].abs();
            let h = q.dv[0].abs() + q.dv[1].abs() + q.dv[2].abs();
            w * h > 1
        }));
    }

    #[test]
    fn cassa_mai_mergeata() {
        let mut m = HashMap::new();
        for x in 0..3 {
            m.insert([x, 0, 0], BlockId::Chest);
        }
        let vol = MapVol(m);
        let quads = greedy_mesh(&vol, [3, 1, 1], &RenderConfig::default());
        for q in &quads {
            let w = q.du[0].abs() + q.du[1].abs() + q.du[2].abs();
            let h = q.dv[0].abs() + q.dv[1].abs() + q.dv[2].abs();
            assert_eq!(w * h, 1);
        }
    }

    #[test]
    fn xray_mesha_solo_minerali() {
        let mut m = HashMap::new();
        m.insert([0, 0, 0], BlockId::Stone);
        m.insert([1, 0, 0], BlockId::DiamondOre);
        m.insert([2, 0, 0], BlockId::Water);
        let vol = MapVol(m);
        let mut cfg = RenderConfig::default();
        cfg.set_xray_enabled(true);
        let quads = greedy_mesh(&vol, [3, 1, 1], &cfg);
        assert!(quads.iter().all(|q| q.block != BlockId::Stone));
        assert!(quads.iter().any(|q| q.block == BlockId::DiamondOre));
        assert!(quads.iter().any(|q| q.block == BlockId::Water));
    }

    #[test]
    fn hollow_cula_foglie_interne() {
        let vol = solid_cube(3, BlockId::Leaves);
        let mut cfg = RenderConfig::default();
        cfg.leaves_mode = LeavesMode::Hollow;
        let quads = greedy_mesh(&vol, [3, 3, 3], &cfg);
        let cfg_dither = RenderConfig::default();
        let quads_d = greedy_mesh(&vol, [3, 3, 3], &cfg_dither);
        // Hollow: meno facce del dithered (interne culate).
        assert!(quads.len() < quads_d.len());
        assert!(quads.iter().all(|q| q.block == BlockId::Leaves));
        // Tutte le facce restanti stanno sul guscio esterno (piani 0 o 3):
        // nessun quad sui piani interni 1 e 2.
        for q in &quads {
            let d = if q.normal[0] != 0 { 0 } else if q.normal[1] != 0 { 1 } else { 2 };
            assert!(q.pos[d] == 0 || q.pos[d] == 3, "faccia interna sopravvissuta: {q:?}");
        }
    }
}