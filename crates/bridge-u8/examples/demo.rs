//! Demo end-to-end: genera `mapping.bin` offline, lo carica all'avvio,
//! poi simula i byte inviati dalla JVM headless durante un frame.
//!
//! Esegui con: `cargo run -p bridge-u8 --example demo`

use std::time::Instant;

use bridge_u8::mapping::{read_mapping, write_mapping};
use bridge_u8::{BridgeError, LookupTable, NativeAction, NativeExecutor};

/// Finto motore: conta le azioni eseguite.
#[derive(Default)]
struct CountingEngine {
    executed: Vec<NativeAction>,
}

impl NativeExecutor for CountingEngine {
    fn execute(&mut self, action: NativeAction) -> Result<(), BridgeError> {
        self.executed.push(action);
        Ok(())
    }
}

fn main() -> Result<(), BridgeError> {
    // --- Fase OFFLINE (build/analisi): genera il mapping per la mod ---
    let entries = [
        (0x0001u16, NativeAction::NativeTick),
        (0x0002, NativeAction::NativeHud),
        (0x1234, NativeAction::PlaySound),
        (0xBEEF, NativeAction::SetBlock),
    ];
    let path = std::env::temp_dir().join("bridge-u8-demo-mapping.bin");
    write_mapping(std::fs::File::create(&path)?, &entries)?;
    println!("mapping.bin scritto in {} ({} entry)", path.display(), entries.len());

    // --- Avvio del motore: carica la LUT ---
    let mut lut = LookupTable::load(&path, CountingEngine::default())?;
    println!("LUT caricata: {} ID mappati", lut.len());
    assert_eq!(read_mapping(std::fs::File::open(&path)?)?.len(), entries.len());

    // --- Gameplay: flusso di byte dalla JVM (4 pacchetti, 8 byte) ---
    let stream: [u8; 8] = [
        0x01, 0x00, // NativeTick
        0x02, 0x00, // NativeHud
        0x34, 0x12, // PlaySound
        0xEF, 0xBE, // SetBlock
    ];
    let start = Instant::now();
    lut.dispatch_byte_stream(&stream)?;
    let elapsed = start.elapsed();

    println!("azioni eseguite: {:?}", lut.executor().executed);
    println!("dispatch di 4 pacchetti in {elapsed:?}");
    Ok(())
}