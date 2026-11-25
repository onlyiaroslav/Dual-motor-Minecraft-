//! X-Ray: filtro di SOLI rendering.
//!
//! Quando attivo, il motore non mesha i blocchi opachi (terra, pietra,
//! netherrack…): renderizza solo minerali, fluidi (acqua/lava) ed entità
//! (le entità passano sempre, il filtro non le tocca).
//!
//! GARANZIE DI SICUREZZA:
//! - hitbox e collisioni invariate: [`collision_solid`] non legge la config;
//! - il mondo/tick non viene alterato: il filtro si applica solo in fase
//!   di costruzione della mesh;
//! - avviso obbligatorio all'attivazione (vedi [`crate::config`]).

use crate::blocks::{props, BlockId};
use crate::config::RenderConfig;

/// Il blocco va renderizzato con la config corrente?
/// (Air mai; in X-Ray solo minerali e fluidi.)
#[inline]
pub fn render_visible(id: BlockId, cfg: &RenderConfig) -> bool {
    if id == BlockId::Air {
        return false;
    }
    if cfg.xray_enabled {
        let p = props(id);
        p.ore || p.fluid
    } else {
        true
    }
}

/// Collisione solida: NON dipende dalla config di rendering.
/// Un blocco nascosto dall'X-Ray continua a avere la sua hitbox.
#[inline]
pub fn collision_solid(id: BlockId) -> bool {
    props(id).solid
}

/// Opacità effettiva per il meshing: un blocco nascosto dall'X-Ray è
/// trattato come aria ai fini del culling delle facce vicine.
#[inline]
pub fn effective_opaque(id: BlockId, cfg: &RenderConfig) -> bool {
    render_visible(id, cfg) && props(id).opaque
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn xray_solo_minerali_e_fluidi() {
        let mut cfg = RenderConfig::default();
        cfg.set_xray_enabled(true);
        assert!(!render_visible(BlockId::Stone, &cfg));
        assert!(!render_visible(BlockId::Dirt, &cfg));
        assert!(!render_visible(BlockId::Netherrack, &cfg));
        assert!(!render_visible(BlockId::Leaves, &cfg));
        assert!(render_visible(BlockId::DiamondOre, &cfg));
        assert!(render_visible(BlockId::GoldOre, &cfg));
        assert!(render_visible(BlockId::IronOre, &cfg));
        assert!(render_visible(BlockId::CoalOre, &cfg));
        assert!(render_visible(BlockId::Water, &cfg));
        assert!(render_visible(BlockId::Lava, &cfg));
    }

    #[test]
    fn collisione_mai_alterata() {
        let mut cfg = RenderConfig::default();
        cfg.set_xray_enabled(true);
        // Nascosto nel rendering, ma solido per la fisica.
        assert!(!render_visible(BlockId::Stone, &cfg));
        assert!(collision_solid(BlockId::Stone));
        assert!(collision_solid(BlockId::Dirt));
        assert!(!collision_solid(BlockId::Water));
    }

    #[test]
    fn default_tutto_visibile() {
        let cfg = RenderConfig::default();
        assert!(render_visible(BlockId::Stone, &cfg));
        assert!(render_visible(BlockId::Leaves, &cfg));
    }
}