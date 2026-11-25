//! Lookup Table a 8 bit: risolve ID numerici inviati dalla JVM in azioni native.
//!
//! # Perché un array piatto invece di `HashMap<u16, _>`
//!
//! La risoluzione avviene con **indicizzazione diretta** `slot[wire_id]`:
//! niente hashing, niente probe, un solo accesso in memoria (~128 KiB di tabella,
//! cache-friendly). È il percorso più veloce possibile per rimanere ampiamente
//! sotto il budget di 5 ms/frame a 60 FPS.
//!
//! # Protocollo di wire
//!
//! La JVM headless invia un flusso di byte (`u8`). Ogni pacchetto è un ID a
//! 16 bit little-endian (2 byte). [`LookupTable::dispatch_byte_stream`] accetta
//! slice di qualsiasi lunghezza, anche con pacchetti spezzati tra chiamate
//! (l'eventuale byte dispari viene conservato per la chiamata successiva).

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

use crate::action::NativeAction;
use crate::error::BridgeError;
use crate::mapping::read_mapping;

/// Sentinel per slot non mappati. Nessun ID azione valido può essere `u16::MAX`.
const UNMAPPED: u16 = u16::MAX;
/// Dimensione dello spazio degli ID di wire.
const SLOTS: usize = 65_536;

/// Lato motore: esegue concretamente l'azione nativa risolta dalla LUT.
///
/// Starling/Horizon implementa questo trait collegandolo ai propri sistemi
/// (rendering, mondo, audio, HUD…). Il crate non conosce il motore.
pub trait NativeExecutor {
    /// Esegue l'azione. Deve essere non bloccante e veloce (hot path).
    fn execute(&mut self, action: NativeAction) -> Result<(), BridgeError>;
}

/// LUT precompilata + esecutore nativo + buffer per byte spaiati.
pub struct LookupTable<E: NativeExecutor> {
    /// Indicizzazione diretta: `slots[wire_id]` = ID azione (o `UNMAPPED`).
    slots: Box<[u16]>,
    executor: E,
    /// Byte dispari in attesa del secondo byte del pacchetto.
    carry: Option<u8>,
    mapped: usize,
}

impl<E: NativeExecutor> LookupTable<E> {
    /// Crea una LUT vuota con l'esecutore dato.
    pub fn new(executor: E) -> Self {
        Self {
            slots: vec![UNMAPPED; SLOTS].into_boxed_slice(),
            executor,
            carry: None,
            mapped: 0,
        }
    }

    /// Costruisce la LUT da entry già parse (es. generate dall'analisi offline).
    ///
    /// Rifiuta ID di wire duplicati: il mapping deve essere univoco.
    pub fn from_entries(
        entries: &[(u16, NativeAction)],
        executor: E,
    ) -> Result<Self, BridgeError> {
        let mut lut = Self::new(executor);
        for &(wire_id, action) in entries {
            lut.insert(wire_id, action)?;
        }
        Ok(lut)
    }

    /// Carica la LUT da un reader in formato `mapping.bin`.
    pub fn from_mapping_reader<R: Read>(reader: R, executor: E) -> Result<Self, BridgeError> {
        let entries = read_mapping(reader)?;
        Self::from_entries(&entries, executor)
    }

    /// Carica `mapping.bin` da disco all'avvio del motore.
    ///
    /// Su Android il file va tenuto nello storage specifico dell'app
    /// (es. `getExternalFilesDir()`), compatibile con lo Scoped Storage di
    /// Android 15: nessun permesso runtime richiesto.
    pub fn load(path: impl AsRef<Path>, executor: E) -> Result<Self, BridgeError> {
        let file = File::open(path)?;
        Self::from_mapping_reader(BufReader::new(file), executor)
    }

    /// Inserisce un mapping. Errore se l'ID di wire è già occupato.
    pub fn insert(&mut self, wire_id: u16, action: NativeAction) -> Result<(), BridgeError> {
        let slot = &mut self.slots[wire_id as usize];
        if *slot != UNMAPPED {
            return Err(BridgeError::DuplicateMapping(wire_id));
        }
        *slot = action.as_u16();
        self.mapped += 1;
        Ok(())
    }

    /// Risolve un ID di wire senza eseguirlo (ispezione/debug).
    #[inline]
    pub fn get(&self, wire_id: u16) -> Option<NativeAction> {
        let raw = self.slots[wire_id as usize];
        if raw == UNMAPPED {
            None
        } else {
            NativeAction::try_from(raw).ok()
        }
    }

    /// Numero di ID mappati.
    #[inline]
    pub fn len(&self) -> usize {
        self.mapped
    }

    /// `true` se nessun ID è mappato.
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.mapped == 0
    }

    /// Byte spaiato in attesa (fine stream non allineata a 2 byte).
    #[inline]
    pub fn pending_byte(&self) -> Option<u8> {
        self.carry
    }

    /// Accesso all'esecutore nativo.
    #[inline]
    pub fn executor(&self) -> &E {
        &self.executor
    }

    /// Accesso mutuo all'esecutore nativo.
    #[inline]
    pub fn executor_mut(&mut self) -> &mut E {
        &mut self.executor
    }

    /// Risolve ed esegue un singolo ID di wire. Hot path: O(1), zero allocazioni.
    #[inline]
    pub fn dispatch_id(&mut self, wire_id: u16) -> Result<(), BridgeError> {
        let raw = self.slots[wire_id as usize];
        if raw == UNMAPPED {
            return Err(BridgeError::UnmappedWireId(wire_id));
        }
        // Gli slot sono validati all'inserimento: questo controllo è solo difesa.
        let action = NativeAction::try_from(raw)?;
        self.executor.execute(action)
    }

    /// Interpreta un flusso di byte in arrivo dalla JVM ed esegue le azioni.
    ///
    /// - Ogni 2 byte (little-endian) formano un ID di wire.
    /// - Slice di lunghezza dispari: l'ultimo byte resta in `carry` e verrà
    ///   completato alla chiamata successiva.
    /// - Fail-fast: il primo ID non mappato interrompe lo stream con
    ///   [`BridgeError::UnmappedWireId`] (la mod colpevole va isolata a monte).
    pub fn dispatch_byte_stream(&mut self, bytes: &[u8]) -> Result<(), BridgeError> {
        let mut rest = bytes;

        // Completa l'eventuale pacchetto lasciato a metà dalla chiamata precedente.
        if let Some(first) = self.carry.take() {
            match rest.split_first() {
                Some((&second, tail)) => {
                    self.dispatch_id(u16::from_le_bytes([first, second]))?;
                    rest = tail;
                }
                None => {
                    self.carry = Some(first);
                    return Ok(());
                }
            }
        }

        let mut chunks = rest.chunks_exact(2);
        for chunk in &mut chunks {
            let wire_id = u16::from_le_bytes([chunk[0], chunk[1]]);
            self.dispatch_id(wire_id)?;
        }
        if let Some(&b) = chunks.remainder().first() {
            self.carry = Some(b);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mapping::write_mapping;
    use std::io::Cursor;
    use std::time::{Duration, Instant};

    /// Esecutore che registra le azioni eseguite, per verificare il dispatch.
    #[derive(Default)]
    struct RecordingExecutor {
        executed: Vec<NativeAction>,
    }

    impl NativeExecutor for RecordingExecutor {
        fn execute(&mut self, action: NativeAction) -> Result<(), BridgeError> {
            self.executed.push(action);
            Ok(())
        }
    }

    fn lut_con_mapping() -> LookupTable<RecordingExecutor> {
        // 0x1234 -> PlaySound, 0x0001 -> NativeTick, 0xBEEF -> SetBlock
        let entries = [
            (0x1234u16, NativeAction::PlaySound),
            (0x0001, NativeAction::NativeTick),
            (0xBEEF, NativeAction::SetBlock),
        ];
        LookupTable::from_entries(&entries, RecordingExecutor::default()).unwrap()
    }

    /// REQUISITO: pacchetto di 2 byte -> azione corretta in meno di 1 ms.
    #[test]
    fn pacchetto_2_byte_eseguito_sotto_1ms() {
        let mut lut = lut_con_mapping();

        // Riscaldamento: evita che il primo dispatch paghi cache fredde/fault di pagina.
        lut.dispatch_byte_stream(&[0x34, 0x12]).unwrap();
        lut.executor_mut().executed.clear();

        // Pacchetto: ID 0x1234 little-endian -> [0x34, 0x12] -> PlaySound.
        let packet: [u8; 2] = [0x34, 0x12];
        let start = Instant::now();
        lut.dispatch_byte_stream(&packet).unwrap();
        let elapsed = start.elapsed();

        assert_eq!(lut.executor().executed, vec![NativeAction::PlaySound]);
        assert!(
            elapsed < Duration::from_millis(1),
            "dispatch troppo lento: {elapsed:?} (budget 1 ms)"
        );
    }

    /// Budget di frame: 300 pacchetti (5 frame a 60 FPS) in meno di 5 ms.
    #[test]
    fn budget_5ms_per_300_pacchetti() {
        let mut lut = lut_con_mapping();
        let mut stream = Vec::with_capacity(600);
        for _ in 0..300 {
            stream.extend_from_slice(&[0x34, 0x12]);
        }

        lut.dispatch_byte_stream(&stream).unwrap(); // warm-up
        lut.executor_mut().executed.clear();

        let start = Instant::now();
        lut.dispatch_byte_stream(&stream).unwrap();
        let elapsed = start.elapsed();

        assert_eq!(lut.executor().executed.len(), 300);
        assert!(
            elapsed < Duration::from_millis(5),
            "budget frame sforato: {elapsed:?} per 300 pacchetti"
        );
    }

    #[test]
    fn stream_multiplo_nell_ordine_corretto() {
        let mut lut = lut_con_mapping();
        // 0x0001, 0xBEEF, 0x1234 in little-endian.
        lut.dispatch_byte_stream(&[0x01, 0x00, 0xEF, 0xBE, 0x34, 0x12])
            .unwrap();
        assert_eq!(
            lut.executor().executed,
            vec![
                NativeAction::NativeTick,
                NativeAction::SetBlock,
                NativeAction::PlaySound
            ]
        );
        assert_eq!(lut.pending_byte(), None);
    }

    #[test]
    fn byte_spaiato_completato_alla_chiamata_successiva() {
        let mut lut = lut_con_mapping();
        lut.dispatch_byte_stream(&[0x34]).unwrap(); // metà pacchetto
        assert!(lut.executor().executed.is_empty());
        assert_eq!(lut.pending_byte(), Some(0x34));

        lut.dispatch_byte_stream(&[0x12]).unwrap(); // completamento
        assert_eq!(lut.executor().executed, vec![NativeAction::PlaySound]);
        assert_eq!(lut.pending_byte(), None);
    }

    #[test]
    fn stream_vuoto_ok() {
        let mut lut = lut_con_mapping();
        lut.dispatch_byte_stream(&[]).unwrap();
        assert!(lut.executor().executed.is_empty());
    }

    #[test]
    fn id_non_mappato_errore() {
        let mut lut = lut_con_mapping();
        assert!(matches!(
            lut.dispatch_byte_stream(&[0x00, 0x99]),
            Err(BridgeError::UnmappedWireId(0x9900))
        ));
        assert!(lut.executor().executed.is_empty());
    }

    #[test]
    fn duplicato_rifiutato() {
        let mut lut = lut_con_mapping();
        assert!(matches!(
            lut.insert(0x1234, NativeAction::Noop),
            Err(BridgeError::DuplicateMapping(0x1234))
        ));
        assert_eq!(lut.len(), 3);
    }

    #[test]
    fn caricamento_da_file_binario() {
        let entries = [(0x1234u16, NativeAction::PlaySound)];
        let mut buf = Vec::new();
        write_mapping(&mut buf, &entries).unwrap();

        let mut lut =
            LookupTable::from_mapping_reader(Cursor::new(&buf), RecordingExecutor::default())
                .unwrap();
        assert_eq!(lut.len(), 1);
        assert_eq!(lut.get(0x1234), Some(NativeAction::PlaySound));
        assert_eq!(lut.get(0x0000), None);

        lut.dispatch_byte_stream(&[0x34, 0x12]).unwrap();
        assert_eq!(lut.executor().executed, vec![NativeAction::PlaySound]);
    }

    #[test]
    fn errore_esecutore_propagato() {
        struct FailingExecutor;
        impl NativeExecutor for FailingExecutor {
            fn execute(&mut self, _: NativeAction) -> Result<(), BridgeError> {
                Err(BridgeError::Executor("boom".into()))
            }
        }
        let mut lut =
            LookupTable::from_entries(&[(1, NativeAction::Noop)], FailingExecutor).unwrap();
        assert!(matches!(
            lut.dispatch_byte_stream(&[0x01, 0x00]),
            Err(BridgeError::Executor(_))
        ));
    }
}