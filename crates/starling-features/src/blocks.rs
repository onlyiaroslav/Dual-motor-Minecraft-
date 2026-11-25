//! Registro blocchi (subset rilevante per le feature client).
//!
//! PRINCIPIO FONDAMENTALE: le proprietà di RENDERING (opacità visiva,
//! mergeabilità, baking) sono separate da quelle di GIOCO (collisione,
//! random tick, luce, poussabilità). Le ottimizzazioni visive toccano
//! solo le prime.

/// Posizione voxel nel mondo.
pub type Pos = [i32; 3];

/// Identificatori blocco (subset; gli ID sono stabili).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
#[repr(u16)]
pub enum BlockId {
    Air = 0,
    Stone,
    Dirt,
    Netherrack,
    DiamondOre,
    GoldOre,
    IronOre,
    CoalOre,
    Water,
    Lava,
    Leaves,
    Log,
    Chest,
    ShulkerBox,
    Piston,
    PistonHead,
    RedstoneWire,
    Repeater,
    Observer,
    Hopper,
    Farmland,
    Wheat,
    Glass,
    Bedrock,
    RedstoneBlock,
}

/// Proprietà di un blocco.
#[derive(Clone, Copy, Debug)]
pub struct BlockProps {
    /// Opaco ai fini del meshing (culling facce). NON è la collisione.
    pub opaque: bool,
    /// Collisione solida (hitbox). MAI alterata dalle feature di rendering.
    pub solid: bool,
    /// Minerale: visibile in X-Ray.
    pub ore: bool,
    /// Fluido (acqua/lava): visibile in X-Ray, animato, non mergeabile.
    pub fluid: bool,
    /// Foglie: cutout alpha, random tick (decadimento), opacità luce 1.
    pub leaves: bool,
    /// Block entity (cassa, shulker, hopper…): mesh dinamica o baked.
    pub block_entity: bool,
    /// Componente redstone: MAI greedy-mergeato (stati frequenti).
    pub redstone: bool,
    /// Aggiornamenti frequenti (hopper, piston head, farmland…): no merge.
    pub frequent_update: bool,
    /// Riceve random tick (colture, foglie, farmland): no merge.
    pub random_tick: bool,
    /// Non spingibile da pistone (bedrock, ossidiana…).
    pub unpushable: bool,
    /// attenuazione luce cielo (indipendente dal rendering).
    pub light_opacity: u8,
}

const fn p(
    opaque: bool, solid: bool, ore: bool, fluid: bool, leaves: bool,
    block_entity: bool, redstone: bool, frequent_update: bool,
    random_tick: bool, unpushable: bool, light_opacity: u8,
) -> BlockProps {
    BlockProps {
        opaque, solid, ore, fluid, leaves, block_entity, redstone,
        frequent_update, random_tick, unpushable, light_opacity,
    }
}

/// Proprietà del blocco (tabella const, zero allocazioni).
pub const fn props(id: BlockId) -> BlockProps {
    use BlockId::*;
    match id {
        Air => p(false, false, false, false, false, false, false, false, false, false, 0),
        Stone | Dirt | Netherrack => p(true, true, false, false, false, false, false, false, false, false, 15),
        Bedrock => p(true, true, false, false, false, false, false, false, false, true, 15),
        DiamondOre | GoldOre | IronOre | CoalOre => p(true, true, true, false, false, false, false, false, false, false, 15),
        Water => p(false, false, false, true, false, false, false, false, false, false, 1),
        Lava => p(false, false, false, true, false, false, false, false, false, false, 0),
        // Foglie: cutout (non opache), solide, random tick, luce -1.
        Leaves => p(false, true, false, false, true, false, false, false, true, false, 1),
        Log => p(true, true, false, false, false, false, false, false, false, false, 15),
        // Casse/shulker: block entity pushabili dal pistone (vanilla).
        Chest | ShulkerBox => p(false, true, false, false, false, true, false, false, false, false, 0),
        Piston => p(false, true, false, false, false, true, true, false, false, true, 0),
        PistonHead => p(false, true, false, false, false, false, true, true, false, true, 0),
        RedstoneWire => p(false, false, false, false, false, false, true, true, false, false, 0),
        Repeater => p(false, true, false, false, false, false, true, true, false, false, 0),
        Observer => p(true, true, false, false, false, true, true, true, false, false, 15),
        Hopper => p(false, true, false, false, false, true, false, true, false, false, 0),
        Farmland => p(true, true, false, false, false, false, false, true, true, false, 15),
        Wheat => p(false, false, false, false, false, false, false, false, true, false, 0),
        Glass => p(false, true, false, false, false, false, false, false, false, false, 0),
        RedstoneBlock => p(true, true, false, false, false, false, true, false, false, false, 15),
    }
}

/// Un blocco è mergeabile col greedy meshing?
/// ESCLUSI: redstone, block entity, aggiornamenti frequenti, random tick,
/// fluidi e foglie (cutout/animati). Vincolo: no farm breaking.
pub const fn greedy_mergeable(id: BlockId) -> bool {
    let pr = props(id);
    !pr.redstone && !pr.block_entity && !pr.frequent_update
        && !pr.random_tick && !pr.fluid && !pr.leaves
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collisione_indipendente_dal_rendering() {
        // La pietra è solida anche se l'X-Ray la nasconde.
        assert!(props(BlockId::Stone).solid);
        assert!(props(BlockId::Leaves).solid);
        assert!(!props(BlockId::Water).solid);
    }

    #[test]
    fn esclusioni_greedy() {
        assert!(greedy_mergeable(BlockId::Stone));
        assert!(greedy_mergeable(BlockId::Glass));
        assert!(!greedy_mergeable(BlockId::RedstoneWire));
        assert!(!greedy_mergeable(BlockId::Chest));
        assert!(!greedy_mergeable(BlockId::Hopper));
        assert!(!greedy_mergeable(BlockId::Wheat));
        assert!(!greedy_mergeable(BlockId::Farmland));
        assert!(!greedy_mergeable(BlockId::Leaves));
        assert!(!greedy_mergeable(BlockId::Water));
    }

    #[test]
    fn luce_foglie_indipendente_dal_rendering() {
        // Opacità luce delle foglie = 1 in QUALUNQUE modalità di rendering.
        assert_eq!(props(BlockId::Leaves).light_opacity, 1);
    }
}