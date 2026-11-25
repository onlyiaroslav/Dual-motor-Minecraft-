//! Azioni native eseguibili dal motore Rust (Starling/Horizon).
//!
//! Gli ID (`u16`) sono **stabili e permanenti**: fanno parte del formato
//! `mapping.bin` e del protocollo col bridge Java. Non riutilizzare mai un ID
//! dismesso; aggiungerne solo di nuovi in coda.

use crate::error::BridgeError;

/// Azione nativa richiesta da una mod Java tramite il bridge a 8 bit.
///
/// La JVM non invia mai stringhe o oggetti: invia un ID di wire a 16 bit
/// (2 byte, little-endian) che la LUT risolve in una di queste varianti.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u16)]
pub enum NativeAction {
    /// Nessuna operazione (keep-alive / padding).
    Noop = 0,
    /// Hook di tick: la mod chiede di eseguire logica per-tick.
    NativeTick = 1,
    /// Hook HUD: aggiornamento di un elemento dell'interfaccia.
    NativeHud = 2,
    /// Hook tasto/input: evento di input simulato dalla mod.
    NativeKey = 3,
    /// Genera un sistema di particelle.
    SpawnParticle = 4,
    /// Riproduci un suono.
    PlaySound = 5,
    /// Piazza un blocco nel mondo.
    SetBlock = 6,
    /// Rompi un blocco nel mondo.
    BreakBlock = 7,
    /// Invia un messaggio in chat.
    SendChat = 8,
    /// Applica una velocità a un'entità.
    ApplyVelocity = 9,
    /// Imposta l'ora del mondo.
    SetWorldTime = 10,
    /// Apri una schermata GUI nativa.
    OpenScreen = 11,
}

impl NativeAction {
    /// ID numerico stabile dell'azione.
    #[inline]
    pub const fn as_u16(self) -> u16 {
        self as u16
    }
}

impl TryFrom<u16> for NativeAction {
    type Error = BridgeError;

    #[inline]
    fn try_from(id: u16) -> Result<Self, Self::Error> {
        match id {
            0 => Ok(Self::Noop),
            1 => Ok(Self::NativeTick),
            2 => Ok(Self::NativeHud),
            3 => Ok(Self::NativeKey),
            4 => Ok(Self::SpawnParticle),
            5 => Ok(Self::PlaySound),
            6 => Ok(Self::SetBlock),
            7 => Ok(Self::BreakBlock),
            8 => Ok(Self::SendChat),
            9 => Ok(Self::ApplyVelocity),
            10 => Ok(Self::SetWorldTime),
            11 => Ok(Self::OpenScreen),
            other => Err(BridgeError::UnknownActionId(other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_tutti_gli_id() {
        for id in 0..=11u16 {
            let action = NativeAction::try_from(id).unwrap();
            assert_eq!(action.as_u16(), id);
        }
    }

    #[test]
    fn id_sconosciuto_errore() {
        assert!(matches!(
            NativeAction::try_from(999),
            Err(BridgeError::UnknownActionId(999))
        ));
        assert!(NativeAction::try_from(u16::MAX).is_err());
    }
}