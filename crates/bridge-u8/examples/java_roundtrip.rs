//! Roundtrip cross-linguaggio: legge `mapping.bin` e `stream.bin` prodotti
//! dalla demo Java (`dev.starling.bridge.RoundtripDemo`) e li dispatcha.
//!
//! Verifica che il contratto di wire (u16 little-endian) sia identico tra
//! JVM headless e motore Rust.
//!
//! Esegui: `cargo run -p bridge-u8 --example java_roundtrip [dir]`
//! (default dir: `/tmp/bridge-u8-java`)

use std::path::PathBuf;
use std::time::Instant;

use bridge_u8::{BridgeError, LookupTable, NativeAction, NativeExecutor};

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
    let dir = PathBuf::from(
        std::env::args()
            .nth(1)
            .unwrap_or_else(|| "/tmp/bridge-u8-java".to_string()),
    );

    // LUT generata dalla JVM (in produzione: tool di analisi offline).
    let mut lut = LookupTable::load(dir.join("mapping.bin"), CountingEngine::default())?;
    println!("LUT caricata da {}: {} ID mappati", dir.display(), lut.len());

    // Byte prodotti dalla JVM headless durante un frame.
    let bytes = std::fs::read(dir.join("stream.bin"))?;
    println!("stream.bin: {} byte ({} pacchetti)", bytes.len(), bytes.len() / 2);

    let start = Instant::now();
    lut.dispatch_byte_stream(&bytes)?;
    let elapsed = start.elapsed();

    println!("azioni eseguite: {:?}", lut.executor().executed);
    println!("dispatch in {elapsed:?}");
    assert_eq!(
        lut.executor().executed,
        vec![
            NativeAction::NativeTick,
            NativeAction::NativeHud,
            NativeAction::PlaySound,
            NativeAction::SetBlock
        ],
        "roundtrip Java→Rust fallito"
    );
    println!("roundtrip Java→Rust OK ✔");
    Ok(())
}