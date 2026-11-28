//! Errors of the 8-bit bridge.

use std::fmt;
use std::io;

/// Errors produced by the `bridge-u8` crate.
#[derive(Debug)]
pub enum BridgeError {
    /// I/O error while loading/saving the mapping.
    Io(io::Error),
    /// The file does not start with the `BU8M` magic.
    InvalidMagic([u8; 4]),
    /// Unsupported format version for this crate.
    UnsupportedVersion(u16),
    /// File ended before expected (incomplete header or record).
    TruncatedMapping,
    /// The file declares more entries than the maximum possible (65536).
    TooManyEntries(u32),
    /// The same wire ID is mapped twice.
    DuplicateMapping(u16),
    /// The file contains an action ID that does not match any `NativeAction`.
    UnknownActionId(u16),
    /// Wire ID received from the JVM but absent from the LUT.
    UnmappedWireId(u16),
    /// The native executor rejected/failed the action.
    Executor(String),
    /// Invalid shared buffer (ring): magic, version, alignment, capacity.
    InvalidRing(String),
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(e) => write!(f, "I/O error: {e}"),
            Self::InvalidMagic(m) => write!(f, "invalid magic: {m:?} (expected b\"BU8M\")"),
            Self::UnsupportedVersion(v) => write!(f, "unsupported mapping version: {v}"),
            Self::TruncatedMapping => write!(f, "mapping file truncated"),
            Self::TooManyEntries(n) => write!(f, "too many entries in mapping: {n} (max 65536)"),
            Self::DuplicateMapping(id) => write!(f, "duplicate wire ID: 0x{id:04X}"),
            Self::UnknownActionId(id) => write!(f, "unknown action ID: {id}"),
            Self::UnmappedWireId(id) => write!(f, "unmapped wire ID: 0x{id:04X}"),
            Self::Executor(msg) => write!(f, "native executor error: {msg}"),
            Self::InvalidRing(msg) => write!(f, "invalid ring buffer: {msg}"),
        }
    }
}

impl std::error::Error for BridgeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<io::Error> for BridgeError {
    fn from(e: io::Error) -> Self {
        Self::Io(e)
    }
}