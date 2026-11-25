//! # bridge-u8
//!
//! Bridge IPC a 8 bit tra il motore nativo Rust (**Starling** su Android,
//! **Horizon** su PC) e una **JVM headless** che esegue i jar Fabric/NeoForge
//! originali delle mod Java (progetto "Doppio Motore").
//!
//! ## Principi
//!
//! - **Niente stringhe, niente oggetti pesanti sul wire**: la JVM invia solo
//!   ID numerici a 16 bit (pacchetti di 2 byte, little-endian) in un flusso di `u8`.
//! - **Risoluzione in nanosecondi**: [`LookupTable`] usa indicizzazione diretta
//!   in un array piatto di 65536 slot (~128 KiB), non un `HashMap`.
//! - **LUT precompilata**: il file `mapping.bin` è generato offline (analisi
//!   statica in fase di build, eventualmente assistita da AI) e caricato
//!   all'avvio. **Zero AI a runtime.**
//! - **Licenze**: il bridge esegue il jar originale; nessuna decompilazione o
//!   riscrittura delle mod "All Rights Reserved".
//!
//! ## Esempio
//!
//! ```
//! use bridge_u8::{BridgeError, LookupTable, NativeAction, NativeExecutor};
//!
//! struct Engine;
//! impl NativeExecutor for Engine {
//!     fn execute(&mut self, action: NativeAction) -> Result<(), BridgeError> {
//!         // qui Starling/Horizon collega l'azione ai sistemi nativi
//!         assert_eq!(action, NativeAction::PlaySound);
//!         Ok(())
//!     }
//! }
//!
//! // All'avvio: LUT dal mapping precompilato (qui generato in memoria).
//! let entries = [(0x1234u16, NativeAction::PlaySound)];
//! let mut lut = LookupTable::from_entries(&entries, Engine).unwrap();
//!
//! // Durante il frame: byte in arrivo dalla JVM headless.
//! lut.dispatch_byte_stream(&[0x34, 0x12]).unwrap(); // ID 0x1234 LE
//! ```
//!
//! ## Flusso di avvio (Fase 1 — Android)
//!
//! 1. Il motore scansiona la cartella mod.
//! 2. Per ogni mod, cerca il mapping nella LUT locale (`mapping.bin`).
//! 3. Se presente → caricamento e via.
//! 4. Se assente → analisi **offline** (fuori dal gameplay) che genera il
//!    mapping e lo salva; al prossimo avvio la mod è nella LUT.

#![forbid(unsafe_code)]
#![warn(missing_docs)]

mod action;
mod error;
mod lut;
pub mod mapping;

pub use action::NativeAction;
pub use error::BridgeError;
pub use lut::{LookupTable, NativeExecutor};