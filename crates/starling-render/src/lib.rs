//! # starling-render
//!
//! Renderer voxel 3D + game loop per **Starling Android** (Doge Client):
//! wgpu/Vulkan su `ANativeWindow` (SurfaceView), camera/movimento, touch,
//! tick 20 TPS e connessione al bridge a 8 bit (`nativePoll`).
//!
//! Componenti adattati da [`starling-client/starling`](https://github.com/starling-client/starling)
//! (MIT, commit `0e5bb58a`) —vedi THIRD_PARTY_LICENSES:
//!
//! - [`camera`]: yaw/pitch, vista e proiezione (matematica JOML via Horizon);
//! - [`touch`]: ruoli delle dita (stick/look), tap/hold, `camera_delta`;
//! - [`surface`]: setup wgpu/Vulkan sulla ANativeWindow (formato, present mode);
//! - [`renderer`]: pipeline voxel (depth, cull, uniforms per frame).
//!
//! Il meshing viene da `crates/starling-features` (greedy con esclusioni di
//! gioco, X-Ray, hollow): il renderer disegna la mesh derivata, mai il contrario.
//! Il poll del bridge passa dall'host C (`starling_jvm_poll_frame` →
//! `NativeTransport.nativePoll`), una volta per frame.
//!
//! Su host (non Android) restano compilabili e testabili [`camera`], [`touch`],
//! [`game`] e [`bridge`] (stub): il layer wgpu/JNI è solo Android.

pub mod bridge;
pub mod camera;
pub mod game;
pub mod touch;

#[cfg(target_os = "android")]
pub mod renderer;
#[cfg(target_os = "android")]
pub mod surface;

#[cfg(target_os = "android")]
mod jni;