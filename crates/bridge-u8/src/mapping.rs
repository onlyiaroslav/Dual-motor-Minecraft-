//! Formato binario `mapping.bin` (LUT precompilata, little-endian).
//!
//! Layout del file:
//!
//! | Offset | Dim | Campo                          |
//! |--------|-----|--------------------------------|
//! | 0      | 4   | magic `BU8M`                   |
//! | 4      | 2   | versione formato (`u16` = 1)   |
//! | 6      | 4   | numero entry (`u32`)           |
//! | 10     | 4×N | record: `wire_id u16` + `action_id u16` |
//!
//! Il file viene generato **offline** (fase di build/analisi, mai a runtime
//! durante il gameplay) e caricato una sola volta all'avvio del motore.

use std::io::{self, Read, Write};

use crate::action::NativeAction;
use crate::error::BridgeError;

/// Magic del file: "Bridge U8 Mapping".
pub const MAGIC: [u8; 4] = *b"BU8M";
/// Versione del formato supportata.
pub const VERSION: u16 = 1;
/// Dimensione dell'header in byte.
pub const HEADER_LEN: usize = 10;
/// Dimensione di un record in byte.
pub const RECORD_LEN: usize = 4;
/// Massimo numero di entry (spazio degli ID di wire a 16 bit).
pub const MAX_ENTRIES: u32 = 65_536;

/// Converte un errore di lettura in errore di mapping, distinguendo l'EOF.
fn read_err(e: io::Error) -> BridgeError {
    if e.kind() == io::ErrorKind::UnexpectedEof {
        BridgeError::TruncatedMapping
    } else {
        BridgeError::Io(e)
    }
}

/// Legge e valida un mapping da un reader qualsiasi (file, `Cursor`, socket…).
///
/// Non alloca mai più di `MAX_ENTRIES` record: il conteggio dichiarato
/// nell'header viene verificato prima di leggere i record.
pub fn read_mapping<R: Read>(mut reader: R) -> Result<Vec<(u16, NativeAction)>, BridgeError> {
    let mut magic = [0u8; 4];
    reader.read_exact(&mut magic).map_err(read_err)?;
    if magic != MAGIC {
        return Err(BridgeError::InvalidMagic(magic));
    }

    let mut buf2 = [0u8; 2];
    reader.read_exact(&mut buf2).map_err(read_err)?;
    let version = u16::from_le_bytes(buf2);
    if version != VERSION {
        return Err(BridgeError::UnsupportedVersion(version));
    }

    let mut buf4 = [0u8; 4];
    reader.read_exact(&mut buf4).map_err(read_err)?;
    let count = u32::from_le_bytes(buf4);
    if count > MAX_ENTRIES {
        return Err(BridgeError::TooManyEntries(count));
    }

    let mut entries = Vec::with_capacity(count as usize);
    let mut record = [0u8; RECORD_LEN];
    for _ in 0..count {
        reader.read_exact(&mut record).map_err(read_err)?;
        let wire_id = u16::from_le_bytes([record[0], record[1]]);
        let action_id = u16::from_le_bytes([record[2], record[3]]);
        let action = NativeAction::try_from(action_id)?;
        entries.push((wire_id, action));
    }
    Ok(entries)
}

/// Scrive un mapping nel formato binario. Usato dal tool di analisi offline
/// che genera la LUT per una mod nuova (mai durante il gameplay).
pub fn write_mapping<W: Write>(
    mut writer: W,
    entries: &[(u16, NativeAction)],
) -> Result<(), BridgeError> {
    if entries.len() as u64 > MAX_ENTRIES as u64 {
        return Err(BridgeError::TooManyEntries(entries.len() as u32));
    }
    writer.write_all(&MAGIC)?;
    writer.write_all(&VERSION.to_le_bytes())?;
    writer.write_all(&(entries.len() as u32).to_le_bytes())?;
    for &(wire_id, action) in entries {
        writer.write_all(&wire_id.to_le_bytes())?;
        writer.write_all(&action.as_u16().to_le_bytes())?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn sample() -> Vec<(u16, NativeAction)> {
        vec![
            (0x1234, NativeAction::PlaySound),
            (0x0001, NativeAction::NativeTick),
            (0xFFFF, NativeAction::Noop),
        ]
    }

    #[test]
    fn roundtrip_write_read() {
        let entries = sample();
        let mut buf = Vec::new();
        write_mapping(&mut buf, &entries).unwrap();
        assert_eq!(buf.len(), HEADER_LEN + RECORD_LEN * entries.len());
        let letti = read_mapping(Cursor::new(&buf)).unwrap();
        assert_eq!(letti, entries);
    }

    #[test]
    fn magic_non_valido() {
        let buf = b"XXXX\x01\x00\x00\x00\x00\x00";
        assert!(matches!(
            read_mapping(Cursor::new(buf)),
            Err(BridgeError::InvalidMagic(_))
        ));
    }

    #[test]
    fn versione_non_supportata() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&MAGIC);
        buf.extend_from_slice(&99u16.to_le_bytes());
        buf.extend_from_slice(&0u32.to_le_bytes());
        assert!(matches!(
            read_mapping(Cursor::new(&buf)),
            Err(BridgeError::UnsupportedVersion(99))
        ));
    }

    #[test]
    fn header_troncato() {
        assert!(matches!(
            read_mapping(Cursor::new(b"BU8M\x01\x00")),
            Err(BridgeError::TruncatedMapping)
        ));
    }

    #[test]
    fn record_troncato() {
        let mut buf = Vec::new();
        write_mapping(&mut buf, &sample()).unwrap();
        buf.pop(); // rimuove un byte dall'ultimo record
        assert!(matches!(
            read_mapping(Cursor::new(&buf)),
            Err(BridgeError::TruncatedMapping)
        ));
    }

    #[test]
    fn conteggio_eccessivo() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&MAGIC);
        buf.extend_from_slice(&VERSION.to_le_bytes());
        buf.extend_from_slice(&(MAX_ENTRIES + 1).to_le_bytes());
        assert!(matches!(
            read_mapping(Cursor::new(&buf)),
            Err(BridgeError::TooManyEntries(_))
        ));
    }

    #[test]
    fn id_azione_sconosciuto_nel_file() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&MAGIC);
        buf.extend_from_slice(&VERSION.to_le_bytes());
        buf.extend_from_slice(&1u32.to_le_bytes());
        buf.extend_from_slice(&0x0001u16.to_le_bytes());
        buf.extend_from_slice(&999u16.to_le_bytes());
        assert!(matches!(
            read_mapping(Cursor::new(&buf)),
            Err(BridgeError::UnknownActionId(999))
        ));
    }
}