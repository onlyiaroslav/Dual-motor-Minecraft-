# bridge-u8

Bridge IPC a 8 bit del progetto **Doppio Motore**: collega il motore nativo
Rust (Starling su Android / Horizon su PC) a una JVM headless che esegue i jar
Fabric/NeoForge **originali** delle mod Java.

## Protocollo di wire

- Flusso di byte (`u8`), pacchetti da **2 byte**: ID di wire `u16` little-endian.
- La JVM non invia mai stringhe/oggetti: solo ID numerici.
- La LUT risolve l'ID in una `NativeAction` con indicizzazione diretta
  (`slot[wire_id]`, array piatto di 65536 slot ≈ 128 KiB): O(1), zero hashing,
  zero allocazioni nell'hot path.
- Byte spaiati tra chiamate gestiti con carry interno (`pending_byte()`).
- Fail-fast su ID non mappato → `BridgeError::UnmappedWireId` (la mod colpevole
  viene isolata dal livello superiore, Fase 3).

## Formato `mapping.bin` (little-endian)

| Offset | Dim | Campo |
|--------|-----|-------|
| 0 | 4 | magic `BU8M` |
| 4 | 2 | versione (`u16` = 1) |
| 6 | 4 | numero entry (`u32`, max 65536) |
| 10 | 4×N | record: `wire_id u16` + `action_id u16` |

Generato **offline** (analisi in fase di build, mai durante il gameplay;
l'AI può assistere solo qui). Gli ID azione sono stabili: non riutilizzare mai
un ID dismesso.

## Uso

```rust
use bridge_u8::{LookupTable, NativeAction, NativeExecutor, BridgeError};

struct Engine;
impl NativeExecutor for Engine {
    fn execute(&mut self, action: NativeAction) -> Result<(), BridgeError> {
        // collega ai sistemi nativi: rendering, mondo, audio, HUD…
        Ok(())
    }
}

let mut lut = LookupTable::load("mapping.bin", Engine)?;   // all'avvio
lut.dispatch_byte_stream(&bytes_dalla_jvm)?;               // ogni frame
```

Demo: `cargo run -p bridge-u8 --example demo`
Test: `cargo test -p bridge-u8`

## Note Android 15 (Fase 1)

- `mapping.bin` va nello storage specifico dell'app (`getExternalFilesDir()`):
  compatibile con lo Scoped Storage, nessun permesso runtime.
- Il crate è `#![forbid(unsafe_code)]`, zero dipendenze, solo std:
  cross-compilazione NDK senza attriti.
- La JVM headless (senza rendering) verrà collegata in un crate separato;
  questo crate definisce solo il contratto di comunicazione.

## Roadmap

- **Fase 1**: Starling (Rust + Vulkan) + JVM headless + questo bridge. ✅ crate IPC
- **Fase 2**: Horizon desktop, backend wgpu (DX12 + fallback Vulkan),
  hook Java mappati su `horizon-mod-api` (`native_tick`, `native_hud`, `native_key`).
- **Fase 3**: sandbox per mod nuove prima dell'ingresso in LUT; desync check
  Rust↔JVM con log e isolamento della mod colpevole.

## Licenze

Il bridge esegue il jar originale (come il Java Bridge ufficiale, PR #10216):
nessuna decompilazione o riscrittura di mod "All Rights Reserved".