//! Baking delle mesh statiche (casse, shulker box chiuse/inattive).
//!
//! Una mesh statica è "cotta" nella geometria del chunk finché il blocco è
//! inattivo. QUALSIASI block update (pistone, hopper, apertura, redstone)
//! distrugge la mesh statica IMMEDIATAMENTE (O(1), stesso frame) e il blocco
//! torna a rendering dinamico. Vincolo: mai rompere pistoni o farm.

use std::collections::HashMap;

use crate::blocks::{props, BlockId, Pos};

/// Gestione delle mesh baked.
#[derive(Default)]
pub struct StaticBakeManager {
    baked: HashMap<Pos, BlockId>,
    enabled: bool,
}

/// Esito di un block update su una posizione.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum BakeUpdate {
    /// Nessuna mesh baked lì.
    None,
    /// Mesh statica distrutta: il blocco va renderizzato dinamico da ORA.
    Destroyed,
}

impl StaticBakeManager {
    pub fn new(enabled: bool) -> Self {
        Self { baked: HashMap::new(), enabled }
    }

    /// Cuoci la mesh statica se il blocco è una block entity inattiva
    /// (cassa/shulker chiusa). Ritorna true se baked.
    pub fn try_bake(&mut self, pos: Pos, id: BlockId, inactive: bool) -> bool {
        if !self.enabled || !inactive || !props(id).block_entity {
            return false;
        }
        // I componenti redstone non si cuociono mai (stati frequenti).
        if props(id).redstone || props(id).frequent_update {
            return false;
        }
        self.baked.insert(pos, id);
        true
    }

    /// Block update ricevuto (pistone, hopper, apertura, vicino cambiato):
    /// invalidazione O(1) nello STESSO frame. Il chiamante non deve differire.
    #[inline]
    pub fn on_block_update(&mut self, pos: Pos) -> BakeUpdate {
        if self.baked.remove(&pos).is_some() {
            BakeUpdate::Destroyed
        } else {
            BakeUpdate::None
        }
    }

    /// La posizione ha una mesh statica baked?
    #[inline]
    pub fn is_baked(&self, pos: Pos) -> bool {
        self.baked.contains_key(&pos)
    }

    /// Modalità di rendering corrente: statica (baked) o dinamica.
    #[inline]
    pub fn render_dynamic(&self, pos: Pos) -> bool {
        !self.is_baked(pos)
    }

    /// Numero di mesh baked (diagnostica).
    #[inline]
    pub fn baked_count(&self) -> usize {
        self.baked.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bake_solo_block_entity_inactive() {
        let mut m = StaticBakeManager::new(true);
        assert!(m.try_bake([0, 0, 0], BlockId::Chest, true));
        assert!(m.try_bake([1, 0, 0], BlockId::ShulkerBox, true));
        // Aperta → non baked.
        assert!(!m.try_bake([2, 0, 0], BlockId::Chest, false));
        // Pietra → non è block entity.
        assert!(!m.try_bake([3, 0, 0], BlockId::Stone, true));
        // Hopper → aggiornamenti frequenti, mai baked.
        assert!(!m.try_bake([4, 0, 0], BlockId::Hopper, true));
        assert_eq!(m.baked_count(), 2);
    }

    #[test]
    fn update_distrugge_same_frame() {
        let mut m = StaticBakeManager::new(true);
        m.try_bake([0, 0, 0], BlockId::Chest, true);
        assert!(m.is_baked([0, 0, 0]));
        // Block update (es. pistone che spinge): immediato.
        assert_eq!(m.on_block_update([0, 0, 0]), BakeUpdate::Destroyed);
        assert!(!m.is_baked([0, 0, 0]));
        assert!(m.render_dynamic([0, 0, 0]));
        // Secondo update: niente da distruggere.
        assert_eq!(m.on_block_update([0, 0, 0]), BakeUpdate::None);
    }

    #[test]
    fn disabilitato_non_cuoce() {
        let mut m = StaticBakeManager::new(false);
        assert!(!m.try_bake([0, 0, 0], BlockId::Chest, true));
    }
}