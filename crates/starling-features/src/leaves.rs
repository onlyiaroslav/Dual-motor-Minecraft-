//! Foglie: Alpha-to-Coverage / dithered alpha e modalità hollow.
//!
//! VINCOLO RIGIDO: il rendering non tocca il gioco.
//! - Il random tick per il decadimento resta vanilla (mondo, non mesh).
//! - Il passaggio della luce (opacità 1) resta vanilla → spawn mob invariato.
//! - Hollow nasconde solo le foglie INTERNE (6 vicini foglie) dalla mesh.

use crate::blocks::{props, BlockId, Pos};
use crate::config::{LeavesMode, RenderConfig};
use crate::greedy::Volume;
use crate::xray::render_visible;

/// Una foglia interna va culata dalla mesh in modalità hollow?
/// Solo rendering: il blocco resta nel mondo con tutte le sue proprietà.
pub fn hollow_culled<V: Volume>(vol: &V, pos: Pos, cfg: &RenderConfig) -> bool {
    if cfg.leaves_mode != LeavesMode::Hollow {
        return false;
    }
    if vol.block(pos) != BlockId::Leaves {
        return false;
    }
    const DIRS: [Pos; 6] = [
        [1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1],
    ];
    DIRS.iter().all(|d| {
        vol.block([pos[0] + d[0], pos[1] + d[1], pos[2] + d[2]]) == BlockId::Leaves
    })
}

/// Visibilità effettiva di un blocco alla sua posizione (X-Ray + hollow).
pub fn visible_at<V: Volume>(vol: &V, pos: Pos, cfg: &RenderConfig) -> bool {
    render_visible(vol.block(pos), cfg) && !hollow_culled(vol, pos, cfg)
}

/// Opacità luce: proprietà di GIOCO, indipendente dal rendering.
/// Le foglie attenuano di 1 in qualunque modalità (Solid/Dithered/Hollow).
#[inline]
pub fn light_opacity(id: BlockId) -> u8 {
    props(id).light_opacity
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    struct MapVol(HashMap<Pos, BlockId>);
    impl Volume for MapVol {
        fn block(&self, p: Pos) -> BlockId {
            self.0.get(&p).copied().unwrap_or(BlockId::Air)
        }
    }

    fn leaves_cube() -> MapVol {
        let mut m = HashMap::new();
        for x in 0..3 {
            for y in 0..3 {
                for z in 0..3 {
                    m.insert([x, y, z], BlockId::Leaves);
                }
            }
        }
        MapVol(m)
    }

    #[test]
    fn hollow_cula_solo_interne() {
        let vol = leaves_cube();
        let mut cfg = RenderConfig::default();
        cfg.leaves_mode = LeavesMode::Hollow;
        // Il centro (1,1,1) ha 6 vicini foglie → culato.
        assert!(hollow_culled(&vol, [1, 1, 1], &cfg));
        assert!(!visible_at(&vol, [1, 1, 1], &cfg));
        // Una foglia di superficie resta visibile.
        assert!(!hollow_culled(&vol, [0, 1, 1], &cfg));
        assert!(visible_at(&vol, [0, 1, 1], &cfg));
    }

    #[test]
    fn dithered_non_cula() {
        let vol = leaves_cube();
        let cfg = RenderConfig::default(); // Dithered
        assert!(!hollow_culled(&vol, [1, 1, 1], &cfg));
        assert!(visible_at(&vol, [1, 1, 1], &cfg));
    }

    #[test]
    fn luce_indipendente_dalla_modalita() {
        for mode in [LeavesMode::Solid, LeavesMode::Dithered, LeavesMode::Hollow] {
            let mut cfg = RenderConfig::default();
            cfg.leaves_mode = mode;
            // L'opacità luce non legge la config: sempre 1.
            assert_eq!(light_opacity(BlockId::Leaves), 1);
            let _ = cfg;
        }
    }
}