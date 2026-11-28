//! Ring buffer SPSC (single-producer / single-consumer) su memoria condivisa.
//!
//! # Layout (little-endian, header 128 byte, cache-line separata)
//!
//! ```text
//! offset 0   u32  magic = 0x31475242 ("BRG1")
//! offset 4   u32  versione = 1
//! offset 8   u32  capacità dati in byte (potenza di 2, >= 16)
//! offset 12  u32  flags (riservato)
//! offset 16  u64  write_index  — pubblicato dal produttore Java (release)
//! offset 24  u64  dropped_bytes — byte scartati dal produttore (relaxed)
//! offset 64  u64  read_index   — pubblicato dal consumatore Rust (release)
//! offset 128 ...  dati[capacità]
//! ```
//!
//! `write_index` (scritto da Java) e `read_index` (scritto da Rust) stanno su
//! cache line diverse (0 e 64): niente false sharing tra i due core.
//!
//! # Protocollo
//!
//! - Gli indici sono **monotoni crescenti** (mai azzerati); lo slot fisico è
//!   `indice & (capacità - 1)`. Il wrap del buffer è gestito con due copie.
//! - Produttore: legge `read_index` (acquire), verifica spazio libero, scrive
//!   i byte, pubblica `write_index` (release).
//! - Consumatore: legge `write_index` (acquire), copia i byte disponibili,
//!   pubblica `read_index` (release).
//! - Overflow: il produttore scarta e conta in `dropped_bytes` (mai bloccare
//!   il thread della JVM); il motore può loggare il contatore.
//!
//! # Sicurezza memoria
//!
//! La memoria è condivisa con la JVM (direct ByteBuffer o mmap): tutto
//! l'`unsafe` è confinato qui. Il puntatore base DEVE essere allineato a 8 byte
//! (gli indici sono atomici u64; su aarch64 un accesso non allineato è SIGBUS).
//! Il crate `bridge-u8` resta `forbid(unsafe_code)`.

use std::ptr;
use std::sync::atomic::{AtomicU64, Ordering};

use bridge_u8::{BridgeError, LookupTable, NativeExecutor};

/// Magic del ring: "BRG1" come u32 little-endian.
pub const RING_MAGIC: u32 = 0x3147_5242;
/// Versione del layout.
pub const RING_VERSION: u32 = 1;
/// Dimensione header (dati da offset 128).
pub const HEADER_LEN: usize = 128;
/// Offset degli indici atomici.
pub const WRITE_OFF: usize = 16;
pub const DROPPED_OFF: usize = 24;
pub const READ_OFF: usize = 64;

/// Consumatore Rust del ring (lato motore).
///
/// # Safety
///
/// Il puntatore base deve restare valido e mappato per tutta la vita del
/// consumer, con alignment >= 8. Un solo consumatore per ring.
pub struct RingConsumer {
    base: *mut u8,
    capacity: usize,
    mask: usize,
    read: u64,
    scratch: Vec<u8>,
}

// SAFETY: il ring è progettato per essere condiviso tra thread/core;
// l'accesso è disciplinato dagli atomici acquire/release.
unsafe impl Send for RingConsumer {}

impl RingConsumer {
    /// Aggancia il consumatore a una regione condivisa già inizializzata
    /// dal produttore Java. Valida magic, versione, capacità e allineamento.
    ///
    /// # Safety
    ///
    /// `base` deve puntare a `len` byte validi, mappati e allineati a 8.
    pub unsafe fn attach(base: *mut u8, len: usize) -> Result<Self, BridgeError> {
        if base.is_null() {
            return Err(BridgeError::InvalidRing("null pointer".into()));
        }
        if (base as usize) % 8 != 0 {
            return Err(BridgeError::InvalidRing(
                "base not aligned to 8 bytes (SIGBUS risk on aarch64)".into(),
            ));
        }
        if len < HEADER_LEN + 16 {
            return Err(BridgeError::InvalidRing("region too small".into()));
        }
        let magic = (base as *const u32).read_unaligned();
        if magic != RING_MAGIC {
            return Err(BridgeError::InvalidRing(format!(
                "invalid magic: 0x{magic:08X} (expected 0x{RING_MAGIC:08X})"
            )));
        }
        let version = (base.add(4) as *const u32).read_unaligned();
        if version != RING_VERSION {
            return Err(BridgeError::InvalidRing(format!(
                "version not supported: {version}"
            )));
        }
        let capacity = (base.add(8) as *const u32).read_unaligned() as usize;
        if capacity < 16 || !capacity.is_power_of_two() {
            return Err(BridgeError::InvalidRing(format!(
                "invalid capacity: {capacity} (power of 2, >= 16)"
            )));
        }
        if capacity > len - HEADER_LEN {
            return Err(BridgeError::InvalidRing(
                "capacity greater than the mapped region".into(),
            ));
        }
        Ok(Self {
            base,
            capacity,
            mask: capacity - 1,
            read: 0,
            scratch: Vec::new(),
        })
    }

    #[inline]
    fn write_atomic(&self) -> &AtomicU64 {
        // SAFETY: offset 16, allineamento 8 verificato in attach.
        unsafe { &*(self.base.add(WRITE_OFF) as *const AtomicU64) }
    }

    #[inline]
    fn read_atomic(&self) -> &AtomicU64 {
        // SAFETY: offset 64, allineamento 8 verificato in attach.
        unsafe { &*(self.base.add(READ_OFF) as *const AtomicU64) }
    }

    /// Byte disponibili secondo l'indice pubblicato dal produttore.
    #[inline]
    pub fn available(&self) -> u64 {
        self.write_atomic()
            .load(Ordering::Acquire)
            .wrapping_sub(self.read)
    }

    /// Indice di lettura pubblicato (diagnostica).
    #[inline]
    pub fn read_index(&self) -> u64 {
        self.read
    }

    /// Byte scartati dal produttore per overflow (diagnostica).
    #[inline]
    pub fn dropped_bytes(&self) -> u64 {
        // SAFETY: offset 24, allineamento 8 verificato in attach.
        unsafe { &*(self.base.add(DROPPED_OFF) as *const AtomicU64) }.load(Ordering::Relaxed)
    }

    /// Consuma tutti i byte disponibili e li dispatcha nella LUT.
    ///
    /// Ritorna il numero di byte consumati. Fail-fast: se un ID non è mappato
    /// l'errore è propagato, ma gli indici avanzano comunque (il flusso è
    /// compromesso; in Fase 3 la mod colpevole viene isolata a monte).
    /// I byte spaiati tra poll sono gestiti dal carry della `LookupTable`.
    pub fn poll<E: NativeExecutor>(
        &mut self,
        lut: &mut LookupTable<E>,
    ) -> Result<usize, BridgeError> {
        let write = self.write_atomic().load(Ordering::Acquire);
        let avail = write.wrapping_sub(self.read);
        if avail == 0 {
            return Ok(0);
        }
        let n = avail.min(self.capacity as u64) as usize;

        // Copia in scratch (zero allocazioni a regime: Vec riusato).
        self.scratch.clear();
        self.scratch.resize(n, 0);
        let start = (self.read & self.mask as u64) as usize;
        let first = n.min(self.capacity - start);
        // SAFETY: dati dentro la regione validata; indici disciplinati dagli atomici.
        unsafe {
            let data = self.base.add(HEADER_LEN);
            ptr::copy_nonoverlapping(data.add(start), self.scratch.as_mut_ptr(), first);
            if first < n {
                ptr::copy_nonoverlapping(data, self.scratch.as_mut_ptr().add(first), n - first);
            }
        }

        let res = lut.dispatch_byte_stream(&self.scratch);

        // Pubblica l'avanzamento anche in caso di errore (stream compromesso).
        self.read = self.read.wrapping_add(n as u64);
        self.read_atomic().store(self.read, Ordering::Release);
        res.map(|_| n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bridge_u8::NativeAction;

    #[derive(Default)]
    struct Recording(Vec<NativeAction>);
    impl NativeExecutor for Recording {
        fn execute(&mut self, a: NativeAction) -> Result<(), BridgeError> {
            self.0.push(a);
            Ok(())
        }
    }

    /// Regione allineata a 8 byte (Vec<u64>), header inizializzato come farebbe Java.
    struct TestRing {
        mem: Vec<u64>,
        capacity: usize,
        write: u64,
    }

    impl TestRing {
        fn new(capacity: usize) -> Self {
            let mut mem = vec![0u64; (HEADER_LEN + capacity) / 8];
            let base = mem.as_mut_ptr() as *mut u8;
            unsafe {
                (base as *mut u32).write_unaligned(RING_MAGIC);
                (base.add(4) as *mut u32).write_unaligned(RING_VERSION);
                (base.add(8) as *mut u32).write_unaligned(capacity as u32);
            }
            Self { mem, capacity, write: 0 }
        }

        fn base(&mut self) -> *mut u8 {
            self.mem.as_mut_ptr() as *mut u8
        }

        fn len(&self) -> usize {
            self.mem.len() * 8
        }

        /// Simula il produttore Java: scrive byte e pubblica write_index (release).
        fn produce(&mut self, bytes: &[u8]) {
            let base = self.base();
            for &b in bytes {
                let pos = (self.write & (self.capacity as u64 - 1)) as usize;
                unsafe { base.add(HEADER_LEN + pos).write(b) };
                self.write += 1;
            }
            unsafe {
                (&*(base.add(WRITE_OFF) as *const AtomicU64))
                    .store(self.write, Ordering::Release);
            }
        }
    }

    fn lut() -> LookupTable<Recording> {
        LookupTable::from_entries(
            &[(0x1234, NativeAction::PlaySound), (0x0001, NativeAction::NativeTick)],
            Recording::default(),
        )
        .unwrap()
    }

    #[test]
    fn poll_vuoto() {
        let mut ring = TestRing::new(64);
        let mut consumer = unsafe { RingConsumer::attach(ring.base(), ring.len()) }.unwrap();
        let mut l = lut();
        assert_eq!(consumer.poll(&mut l).unwrap(), 0);
        assert!(l.executor().0.is_empty());
    }

    #[test]
    fn due_pacchetti_dispatchati() {
        let mut ring = TestRing::new(64);
        ring.produce(&[0x34, 0x12, 0x01, 0x00]);
        let mut consumer = unsafe { RingConsumer::attach(ring.base(), ring.len()) }.unwrap();
        let mut l = lut();
        assert_eq!(consumer.poll(&mut l).unwrap(), 4);
        assert_eq!(
            l.executor().0,
            vec![NativeAction::PlaySound, NativeAction::NativeTick]
        );
        assert_eq!(consumer.read_index(), 4);
        assert_eq!(consumer.available(), 0);
    }

    #[test]
    fn wrap_del_buffer() {
        let mut ring = TestRing::new(16); // capacità minuscola per forzare il wrap
        let mut consumer = unsafe { RingConsumer::attach(ring.base(), ring.len()) }.unwrap();
        let mut l = lut();
        // 6 byte iniziali, consumati; poi 12 byte che attraversano il bordo.
        ring.produce(&[0x34, 0x12, 0x34, 0x12, 0x34, 0x12]);
        assert_eq!(consumer.poll(&mut l).unwrap(), 6);
        ring.produce(&[0x34, 0x12, 0x34, 0x12, 0x34, 0x12, 0x34, 0x12, 0x34, 0x12, 0x34, 0x12]);
        assert_eq!(consumer.poll(&mut l).unwrap(), 12);
        assert_eq!(l.executor().0.len(), 9);
        assert!(l.executor().0.iter().all(|a| *a == NativeAction::PlaySound));
    }

    #[test]
    fn byte_spaiato_tra_poll() {
        let mut ring = TestRing::new(64);
        let mut consumer = unsafe { RingConsumer::attach(ring.base(), ring.len()) }.unwrap();
        let mut l = lut();
        ring.produce(&[0x34]);
        assert_eq!(consumer.poll(&mut l).unwrap(), 1);
        assert!(l.executor().0.is_empty()); // pacchetto incompleto
        ring.produce(&[0x12]);
        assert_eq!(consumer.poll(&mut l).unwrap(), 1);
        assert_eq!(l.executor().0, vec![NativeAction::PlaySound]);
    }

    #[test]
    fn magic_non_valido_rifiutato() {
        let mut ring = TestRing::new(64);
        unsafe { (ring.base() as *mut u32).write_unaligned(0xDEADBEEF) };
        let res = unsafe { RingConsumer::attach(ring.base(), ring.len()) };
        assert!(matches!(res, Err(BridgeError::InvalidRing(_))));
    }

    #[test]
    fn base_non_allineata_rifiutata() {
        let mut ring = TestRing::new(64);
        let base = unsafe { ring.base().add(1) }; // disallineato
        let res = unsafe { RingConsumer::attach(base, ring.len()) };
        assert!(matches!(res, Err(BridgeError::InvalidRing(_))));
    }

    #[test]
    fn id_non_mappato_avanza_comunque() {
        let mut ring = TestRing::new(64);
        ring.produce(&[0x00, 0x99]); // ID non mappato
        let mut consumer = unsafe { RingConsumer::attach(ring.base(), ring.len()) }.unwrap();
        let mut l = lut();
        assert!(matches!(
            consumer.poll(&mut l),
            Err(BridgeError::UnmappedWireId(0x9900))
        ));
        assert_eq!(consumer.read_index(), 2); // stream compromesso, si avanza
    }
}