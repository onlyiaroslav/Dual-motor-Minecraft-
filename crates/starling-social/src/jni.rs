//! Export JNI per la schermata "Amici" del launcher Kotlin.
//!
//! Attivato dalla feature `jni`; produce `libstarling_social.so` **separata**
//! da `bridge-jni` (che resta intatto). Gli handle sono ID opachi in una
//! registry globale: nessun puntatore raw attraversa il confine JNI e il
//! crate mantiene `#![forbid(unsafe_code)]`.
//!
//! Formato di `listFriends`: una stringa per amico, `nickname|uuid|online`
//! (`online` = `1`/`0`). Il formato è documentato in `SocialBridge.java`.

// Solo il confine FFI richiede `#[no_mangle]` (attributo unsafe); il resto
// del crate resta sotto `deny(unsafe_code)`.
#![allow(unsafe_code)]

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;

use crate::friends::FriendList;
use crate::network_auth::{AuthConfig, OfflineAuthServer};
use crate::token::{TokenStore, DEFAULT_TTL};
use crate::SocialError;

/// Stato per istanza della UI (lista amici + token + server auth).
struct SocialHandle {
    friends: Arc<Mutex<FriendList>>,
    tokens: Arc<Mutex<TokenStore>>,
    server: Option<OfflineAuthServer>,
}

fn registry() -> &'static Mutex<HashMap<i64, SocialHandle>> {
    static REG: OnceLock<Mutex<HashMap<i64, SocialHandle>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> i64 {
    static NEXT: AtomicI64 = AtomicI64::new(1);
    NEXT.fetch_add(1, Ordering::SeqCst)
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
}

/// Accesso all'handle senza trattenere `JNIEnv` (evita prestiti sovrapposti).
fn with_handle<R>(
    handle: i64,
    f: impl FnOnce(&mut SocialHandle) -> Result<R, SocialError>,
) -> Result<R, SocialError> {
    if handle == 0 {
        return Err(SocialError::Server("handle nullo".into()));
    }
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    match reg.get_mut(&handle) {
        Some(h) => f(h),
        None => Err(SocialError::Server("handle non valido o già liberato".into())),
    }
}

/// Esegue il corpo con protezione da panic: un panic non deve mai attraversare
/// il confine JNI (sarebbe UB). In caso di panic: eccezione Java + default.
macro_rules! guarded {
    ($env:expr, $default:expr, $body:expr) => {
        match catch_unwind(AssertUnwindSafe(|| $body)) {
            Ok(v) => v,
            Err(_) => {
                throw(&mut $env, "panic interno nel layer social");
                $default
            }
        }
    };
}

fn string_array(env: &mut JNIEnv, items: &[String]) -> jobjectArray {
    let arr = match env.new_object_array(items.len() as i32, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, s) in items.iter().enumerate() {
        let js = match env.new_string(s) {
            Ok(v) => v,
            Err(_) => return std::ptr::null_mut(),
        };
        let obj = JObject::from(js);
        if env.set_object_array_element(&arr, i as i32, &obj).is_err() {
            return std::ptr::null_mut();
        }
    }
    arr.into_raw()
}

fn new_string(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn parse_world(host: &str, port: u16) -> Result<std::net::SocketAddr, SocialError> {
    if let Ok(addr) = host.parse::<std::net::SocketAddr>() {
        return Ok(addr);
    }
    format!("{host}:{port}")
        .parse::<std::net::SocketAddr>()
        .map_err(|_| SocialError::Protocol(format!("indirizzo host non valido: {host}:{port}")))
}

// --- Export ---------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    friends_file: JString<'local>,
) -> jlong {
    guarded!(env, 0, {
        let path: String = match env.get_string(&friends_file) {
            Ok(s) => s.into(),
            Err(e) => {
                throw(&mut env, &format!("path amici non valido: {e}"));
                return 0;
            }
        };
        let list = if path.trim().is_empty() {
            Ok(FriendList::new())
        } else {
            FriendList::load(&path)
        };
        match list {
            Ok(friends) => {
                let handle = next_handle();
                registry().lock().unwrap_or_else(|e| e.into_inner()).insert(
                    handle,
                    SocialHandle {
                        friends: Arc::new(Mutex::new(friends)),
                        tokens: Arc::new(Mutex::new(TokenStore::new())),
                        server: None,
                    },
                );
                handle
            }
            Err(e) => {
                throw(&mut env, &e.to_string());
                0
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeAddFriend<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    nickname: JString<'local>,
) -> jstring {
    guarded!(env, std::ptr::null_mut(), {
        let nick: String = match env.get_string(&nickname) {
            Ok(s) => s.into(),
            Err(e) => {
                throw(&mut env, &format!("nickname non valido: {e}"));
                return std::ptr::null_mut();
            }
        };
        let uuid = match with_handle(handle, |h| {
            let mut f = h.friends.lock().unwrap_or_else(|e| e.into_inner());
            let uuid = f.add(&nick)?.uuid;
            f.save()?;
            Ok(uuid)
        }) {
            Ok(uuid) => uuid,
            Err(e) => {
                throw(&mut env, &e.to_string());
                return std::ptr::null_mut();
            }
        };
        new_string(&mut env, &uuid)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeListFriends<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jobjectArray {
    guarded!(env, std::ptr::null_mut(), {
        let items = match with_handle(handle, |h| {
            let f = h.friends.lock().unwrap_or_else(|e| e.into_inner());
            Ok(f.list()
                .iter()
                .map(|f| format!("{}|{}|{}", f.nickname, f.uuid, if f.online { 1 } else { 0 }))
                .collect::<Vec<_>>())
        }) {
            Ok(items) => items,
            Err(e) => {
                throw(&mut env, &e.to_string());
                return std::ptr::null_mut();
            }
        };
        string_array(&mut env, &items)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeRemoveFriend<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    nickname: JString<'local>,
) -> jboolean {
    guarded!(env, 0, {
        let nick: String = match env.get_string(&nickname) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        match with_handle(handle, |h| {
            let mut f = h.friends.lock().unwrap_or_else(|e| e.into_inner());
            let removed = f.remove(&nick);
            if removed {
                f.save()?;
            }
            Ok(if removed { 1 } else { 0 })
        }) {
            Ok(v) => v,
            Err(e) => {
                throw(&mut env, &e.to_string());
                0
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeGenerateInvite<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    host: JString<'local>,
    port: jint,
) -> jstring {
    guarded!(env, std::ptr::null_mut(), {
        let host: String = match env.get_string(&host) {
            Ok(s) => s.into(),
            Err(_) => {
                throw(&mut env, "host non valido");
                return std::ptr::null_mut();
            }
        };
        if !(0..=65535).contains(&port) {
            throw(&mut env, "porta non valida");
            return std::ptr::null_mut();
        }
        let addr = match parse_world(&host, port as u16) {
            Ok(a) => a,
            Err(e) => {
                throw(&mut env, &e.to_string());
                return std::ptr::null_mut();
            }
        };
        let code = match with_handle(handle, |h| {
            Ok(h.tokens
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .generate(addr))
        }) {
            Ok(c) => c,
            Err(e) => {
                throw(&mut env, &e.to_string());
                return std::ptr::null_mut();
            }
        };
        new_string(&mut env, &code)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeStartAuthServer<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    port: jint,
) -> jint {
    guarded!(env, -1, {
        if !(0..=65535).contains(&port) {
            throw(&mut env, "porta non valida");
            return -1;
        }
        match with_handle(handle, |h| {
            if let Some(s) = &h.server {
                if s.is_running() {
                    return Ok(s.local_addr().port() as jint);
                }
            }
            let cfg = AuthConfig {
                bind_addr: std::net::SocketAddr::from(([0, 0, 0, 0], port as u16)),
                ..AuthConfig::default()
            };
            let mut server = OfflineAuthServer::bind_with_state(
                cfg,
                Arc::clone(&h.tokens),
                Arc::clone(&h.friends),
            )?;
            server.start()?;
            let actual = server.local_addr().port();
            h.server = Some(server);
            Ok(actual as jint)
        }) {
            Ok(p) => p,
            Err(e) => {
                throw(&mut env, &e.to_string());
                -1
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeStopAuthServer<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    guarded!(env, (), {
        let _ = with_handle(handle, |h| {
            if let Some(mut s) = h.server.take() {
                s.stop();
            }
            Ok(())
        });
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeAuthPort<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) -> jint {
    guarded!(env, 0, {
        match with_handle(handle, |h| {
            Ok(match &h.server {
                Some(s) if s.is_running() => s.local_addr().port() as jint,
                _ => 0,
            })
        }) {
            Ok(p) => p,
            Err(e) => {
                throw(&mut env, &e.to_string());
                0
            }
        }
    })
}

/// TTL dei codici in secondi (statico, nessun handle).
#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeTokenTtlSeconds<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    DEFAULT_TTL.as_secs() as jint
}

#[no_mangle]
pub extern "system" fn Java_dev_starling_social_SocialBridge_nativeFree<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // Il Drop di SocialHandle ferma il server auth se ancora attivo.
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle);
    }
}
