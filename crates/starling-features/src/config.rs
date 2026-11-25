//! Configurazione di rendering di Starling (toggle utente).
//!
//! Le impostazioni di rendering NON toccano mai la logica di gioco:
//! tick 20 TPS, redstone, random tick, collisioni restano identici.

/// Modalità di rendering delle foglie.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LeavesMode {
    /// Quad pieni con texture opaca (fast).
    Solid,
    /// Alpha-to-Coverage / dithered alpha: trasparenza ordinata, no sort.
    Dithered,
    /// Hollow: le foglie interne (circondate da foglie) non vengono meshate.
    /// Solo rendering: decadimento e luce restano vanilla.
    Hollow,
}

/// Configurazione di rendering.
#[derive(Clone, Debug)]
pub struct RenderConfig {
    /// X-Ray: rendering di soli minerali, entità e fluidi.
    pub xray_enabled: bool,
    /// Modalità foglie.
    pub leaves_mode: LeavesMode,
    /// Greedy meshing (con esclusioni di gioco).
    pub greedy_meshing: bool,
    /// Baking delle mesh statiche (casse/shulker chiuse).
    pub static_baking: bool,
}

impl Default for RenderConfig {
    fn default() -> Self {
        Self {
            xray_enabled: false,
            leaves_mode: LeavesMode::Dithered,
            greedy_meshing: true,
            static_baking: true,
        }
    }
}

/// Avviso obbligatorio all'attivazione dell'X-Ray.
pub const XRAY_WARNING: &str = "Solo per Singleplayer/Server privati. L'uso su server pubblici può causare il ban.";

impl RenderConfig {
    /// Toggle X-Ray. Ritorna l'avviso da loggare quando viene ATTIVATO
    /// (una sola volta per transizione off→on).
    pub fn set_xray_enabled(&mut self, on: bool) -> Option<&'static str> {
        let was = self.xray_enabled;
        self.xray_enabled = on;
        if on && !was {
            Some(XRAY_WARNING)
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn avviso_su_attivazione() {
        let mut cfg = RenderConfig::default();
        let w = cfg.set_xray_enabled(true);
        assert_eq!(w, Some(XRAY_WARNING));
        assert!(w.unwrap().contains("ban"));
        // Nessuna ripetizione se già attivo.
        assert_eq!(cfg.set_xray_enabled(true), None);
        // Disattivazione: nessun avviso.
        assert_eq!(cfg.set_xray_enabled(false), None);
        // Riattivazione: avviso di nuovo.
        assert_eq!(cfg.set_xray_enabled(true), Some(XRAY_WARNING));
    }
}