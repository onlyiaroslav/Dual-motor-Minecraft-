//! Simulazione del game loop lato Rust (senza JVM): un thread produttore
//! mock scrive nel ring a 20 TPS, il "motore" consuma a 60 FPS.
//!
//! Verifica: zero perdite, poll sotto il budget di 5 ms, pacing corretto.
//! Esegui: `cargo run --release -p bridge-jni --example game_loop`

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use bridge_jni::ring::{RingConsumer, HEADER_LEN, RING_MAGIC, RING_VERSION, WRITE_OFF};
use bridge_u8::{BridgeError, LookupTable, NativeAction, NativeExecutor};

const CAPACITY: usize = 65_536;
const FRAMES: u32 = 180; // 3 secondi a 60 FPS

#[derive(Default)]
struct Counting {
    packets: usize,
}

impl NativeExecutor for Counting {
    fn execute(&mut self, action: NativeAction) -> Result<(), BridgeError> {
        assert_eq!(action, NativeAction::PlaySound);
        self.packets += 1;
        Ok(())
    }
}

fn main() {
    // Regione allineata a 8 byte, header inizializzato come farebbe Java.
    let mut mem = vec![0u64; (HEADER_LEN + CAPACITY) / 8];
    let base = mem.as_mut_ptr() as *mut u8;
    unsafe {
        (base as *mut u32).write_unaligned(RING_MAGIC);
        (base.add(4) as *mut u32).write_unaligned(RING_VERSION);
        (base.add(8) as *mut u32).write_unaligned(CAPACITY as u32);
    }
    let base_addr = base as usize;
    let stop = Arc::new(AtomicBool::new(false));

    // Produttore mock (simula la JVM): 5 pacchetti per tick, 20 TPS.
    let stop_p = Arc::clone(&stop);
    let producer = thread::spawn(move || {
        let base = base_addr as *mut u8;
        let write_atomic = unsafe { &*(base.add(WRITE_OFF) as *const AtomicU64) };
        let mut write: u64 = 0;
        let mut produced: u64 = 0;
        while !stop_p.load(Ordering::Relaxed) {
            for _ in 0..5 {
                let pos = (write & (CAPACITY as u64 - 1)) as usize;
                unsafe {
                    base.add(HEADER_LEN + pos).write(0x34);
                    base.add(HEADER_LEN + ((pos + 1) & (CAPACITY - 1))).write(0x12);
                }
                write += 2;
                produced += 1;
            }
            write_atomic.store(write, Ordering::Release);
            thread::sleep(Duration::from_millis(50));
        }
        produced
    });

    // Motore: poll a 60 FPS con frame pacing.
    let mut consumer = unsafe { RingConsumer::attach(base, mem.len() * 8) }.unwrap();
    let mut lut = LookupTable::from_entries(
        &[(0x1234, NativeAction::PlaySound)],
        Counting::default(),
    )
    .unwrap();

    let frame_budget = Duration::from_micros(16_667);
    let mut max_poll = Duration::ZERO;
    let start = Instant::now();
    for _ in 0..FRAMES {
        let t0 = Instant::now();
        let n = consumer.poll(&mut lut).unwrap();
        let elapsed = t0.elapsed();
        if elapsed > max_poll {
            max_poll = elapsed;
        }
        assert!(n <= CAPACITY);
        if elapsed < frame_budget {
            thread::sleep(frame_budget - elapsed);
        }
    }
    stop.store(true, Ordering::Relaxed);
    let produced = producer.join().unwrap();

    // Drain finale.
    loop {
        let n = consumer.poll(&mut lut).unwrap();
        if n == 0 {
            break;
        }
    }

    let wall = start.elapsed();
    let received = lut.executor().packets as u64;
    println!(
        "{FRAMES} frame in {wall:?}: prodotti={produced} ricevuti={received} \
         max_poll={max_poll:?} dropped={}",
        consumer.dropped_bytes()
    );
    assert_eq!(received, produced, "perdite di pacchetti!");
    assert_eq!(consumer.dropped_bytes(), 0, "overflow del ring!");
    assert!(max_poll < Duration::from_millis(5), "poll oltre budget!");
    assert!(wall >= Duration::from_millis(16_667 * FRAMES as u64 / 1000));
    println!("game loop Rust: PASS (zero perdite, zero scarti, pacing 60 FPS)");
}