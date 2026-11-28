//! # bridge-jni
//!
//! Trasporto **in-process** per il bridge a 8 bit (progetto "Doppio Motore").
//!
//! Due modalità, entrambe senza rete:
//!
//! 1. **Ring buffer condiviso** (consigliata): Java scrive i pacchetti u16 LE
//!    in un direct ByteBuffer (o mmap), Rust fa polling dal thread del motore
//!    e dispatcha nella LUT. Zero chiamate JNI per pacchetto.
//! 2. **Dispatch diretto JNI**: `nativeDispatch(handle, byte[], len)` per
//!   flush sincroni senza ring.
//!
//! In entrambe le modalità le azioni risolte tornano al lato Java/motore con
//! **una callback batch per poll** (`NativeSink.onActions(int[])`), non una
//! per azione: il costo JNI è ammortizzato sull'intero frame.
//!
//! # Crash risk (vedi anche docs/ANDROID.md)
//!
//! - Il puntatore del direct buffer deve essere allineato a 8 byte: verificato
//!   in `RingConsumer::attach`, altrimenti `InvalidRing` (mai SIGBUS silenzioso).
//! - Endianness: solo little-endian (Android/aarch64 lo è sempre).
//! - Indici u64 atomici: Java usa VarHandle acquire/release, Rust
//!   `AtomicU64` Acquire/Release. Mai letture/scritture plain sugli indici.
//! - `panic = "abort"` nel profilo release: un panic Rust non attraversa mai
//!   il confine JNI (unwind attraverso FFI = UB).
//! - Tutto l'`unsafe` è confinato in questo crate; `bridge-u8` resta puro.

pub mod ring;

use std::ffi::c_void;

use jni::objects::{GlobalRef, JByteArray, JByteBuffer, JClass, JObject, JString, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jint, jlong, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};

use bridge_u8::{BridgeError, LookupTable, NativeAction, NativeExecutor};
use ring::RingConsumer;

/// Raccoglie gli ID azione durante il dispatch; inviati in batch a Java.
#[derive(Default)]
struct Collector {
    ids: Vec<jint>,
}

impl NativeExecutor for Collector {
    fn execute(&mut self, action: NativeAction) -> Result<(), BridgeError> {
        self.ids.push(action.as_u16() as jint);
        Ok(())
    }
}

/// Stato per istanza di bridge (handle opaco restituito a Java).
struct BridgeHandle {
    sink: GlobalRef,
    on_actions: jni::objects::JMethodID,
    consumer: RingConsumer,
    lut: LookupTable<Collector>,
}

fn throw(env: &mut JNIEnv, class: &str, msg: &str) {
    let _ = env.throw_new(class, msg);
}

/// Invia a Java gli ID raccolti (una sola chiamata JNI per poll).
fn flush_actions(env: &mut JNIEnv, h: &mut BridgeHandle) -> jni::errors::Result<()> {
    let ids = std::mem::take(&mut h.lut.executor_mut().ids);
    if ids.is_empty() {
        return Ok(());
    }
    let arr = env.new_int_array(ids.len() as jint)?;
    env.set_int_array_region(&arr, 0, &ids)?;
    let obj: JObject = arr.into();
    let args = [JValue::Object(&obj).as_jni()];
    // SAFETY: method ID risolto in attach sulla classe del sink, tenuta viva
    // dal GlobalRef; signature "([I)V" verificata in attach.
    let call = unsafe {
        env.call_method_unchecked(
            &h.sink,
            h.on_actions,
            ReturnType::Primitive(Primitive::Void),
            &args,
        )
    };
    let _ = env.delete_local_ref(obj);
    call?;
    Ok(())
}

/// `NativeTransport.nativeAttach(String mappingPath, ByteBuffer ring, NativeSink sink) -> long`
///
/// Valuta il mapping, aggancia il ring e registra il sink. Ritorna 0 e solleva
/// un'eccezione Java se qualcosa non è valido (buffer non direct, ring malformato…).
#[no_mangle]
pub extern "system" fn Java_dev_starling_bridge_NativeTransport_nativeAttach<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    mapping_path: JString<'local>,
    ring_buf: JObject<'local>,
    sink: JObject<'local>,
) -> jlong {
    let path: String = match env.get_string(&mapping_path) {
        Ok(s) => s.into(),
        Err(e) => {
            throw(&mut env, "java/lang/IllegalArgumentException", &e.to_string());
            return 0;
        }
    };
    let ring_buf = JByteBuffer::from(ring_buf);
    let base = match env.get_direct_buffer_address(&ring_buf) {
        Ok(p) => p,
        Err(_) => {
            throw(
                &mut env,
                "java/lang/IllegalArgumentException",
                "ring: a DIRECT ByteBuffer is required (allocateDirect or mmap)",
            );
            return 0;
        }
    };
    let len = match env.get_direct_buffer_capacity(&ring_buf) {
        Ok(n) => n,
        Err(e) => {
            throw(&mut env, "java/lang/IllegalArgumentException", &e.to_string());
            return 0;
        }
    };
    // SAFETY: base/len provengono da GetDirectBufferAddress/Capacity della JVM;
    // la regione resta viva finché Java tiene il ByteBuffer (documentato).
    let consumer = match unsafe { RingConsumer::attach(base, len) } {
        Ok(c) => c,
        Err(e) => {
            throw(&mut env, "java/lang/IllegalArgumentException", &e.to_string());
            return 0;
        }
    };
    let lut = match LookupTable::load(&path, Collector::default()) {
        Ok(l) => l,
        Err(e) => {
            throw(&mut env, "dev/starling/bridge/BridgeException", &e.to_string());
            return 0;
        }
    };
    let sink_ref = match env.new_global_ref(&sink) {
        Ok(r) => r,
        Err(e) => {
            throw(&mut env, "java/lang/RuntimeException", &e.to_string());
            return 0;
        }
    };
    let method = match env.get_object_class(&sink) {
        Ok(class) => match env.get_method_id(class, "onActions", "([I)V") {
            Ok(m) => m,
            Err(e) => {
                throw(&mut env, "java/lang/IllegalArgumentException", &e.to_string());
                return 0;
            }
        },
        Err(e) => {
            throw(&mut env, "java/lang/RuntimeException", &e.to_string());
            return 0;
        }
    };
    let handle = BridgeHandle {
        sink: sink_ref,
        on_actions: method,
        consumer,
        lut,
    };
    Box::into_raw(Box::new(handle)) as jlong
}

/// `NativeTransport.nativePoll(long handle) -> int`
///
/// Consuma i byte disponibili nel ring, dispatcha nella LUT e invia gli ID al
/// sink in batch. Ritorna i byte consumati, o -1 con eccezione pendente.
/// Da chiamare dal thread del motore (stesso thread che fa attach, o qualsiasi
/// thread Java: l'JNIEnv è quello della chiamata).
#[no_mangle]
pub extern "system" fn Java_dev_starling_bridge_NativeTransport_nativePoll(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        throw(&mut env, "java/lang/IllegalStateException", "null handle");
        return -1;
    }
    // SAFETY: handle creato da nativeAttach, non ancora detachato (contratto Java).
    let h = unsafe { &mut *(handle as *mut BridgeHandle) };
    let polled = h.consumer.poll(&mut h.lut);
    // Consegna le azioni eseguite prima dell'eventuale errore.
    if flush_actions(&mut env, h).is_err() {
        return -1; // eccezione Java del sink già pendente: si propaga
    }
    match polled {
        Ok(n) => n as jint,
        Err(e) => {
            throw(&mut env, "dev/starling/bridge/BridgeException", &e.to_string());
            -1
        }
    }
}

/// `NativeTransport.nativeDispatch(long handle, byte[] data, int len) -> int`
///
/// Modalità senza ring: dispatch sincrono di byte forniti da Java.
#[no_mangle]
pub extern "system" fn Java_dev_starling_bridge_NativeTransport_nativeDispatch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
    len: jint,
) -> jint {
    if handle == 0 {
        throw(&mut env, "java/lang/IllegalStateException", "null handle");
        return -1;
    }
    let arr_len = match env.get_array_length(&data) {
        Ok(n) => n,
        Err(e) => {
            throw(&mut env, "java/lang/IllegalArgumentException", &e.to_string());
            return -1;
        }
    };
    if len < 0 || len > arr_len {
        throw(&mut env, "java/lang/IllegalArgumentException", "len out of range");
        return -1;
    }
    let mut buf = vec![0i8; len as usize];
    if let Err(e) = env.get_byte_array_region(&data, 0, &mut buf) {
        throw(&mut env, "java/lang/RuntimeException", &e.to_string());
        return -1;
    }
    // SAFETY: reinterpretazione i8 -> u8, stessa dimensione e allineamento.
    let bytes = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const u8, buf.len()) };

    // SAFETY: handle valido per contratto Java.
    let h = unsafe { &mut *(handle as *mut BridgeHandle) };
    let res = h.lut.dispatch_byte_stream(bytes);
    if flush_actions(&mut env, h).is_err() {
        return -1;
    }
    match res {
        Ok(()) => len,
        Err(e) => {
            throw(&mut env, "dev/starling/bridge/BridgeException", &e.to_string());
            -1
        }
    }
}

/// `NativeTransport.nativeDetach(long handle)` — rilascia LUT, ring e global ref.
#[no_mangle]
pub extern "system" fn Java_dev_starling_bridge_NativeTransport_nativeDetach(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: handle creato da nativeAttach; dopo detach Java non deve più usarlo.
        unsafe { drop(Box::from_raw(handle as *mut BridgeHandle)) };
    }
}

/// Versione JNI richiesta dalla libreria.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut c_void) -> jint {
    JNI_VERSION_1_8
}