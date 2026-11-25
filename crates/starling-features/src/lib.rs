//! # starling-features
//!
//! Feature client per **Starling** (Android, Rust + Vulkan) con un vincolo
//! rigido: **integrità assoluta della logica di gioco** (tick 20 TPS,
//! redstone, farm, collisioni, luce, spawn mob).
//!
//! ## Moduli
//!
//! - [`config`]: toggle utente, incluso `xray_enabled` con avviso obbligatorio
//!   all'attivazione ("Solo per Singleplayer/Server privati…").
//! - [`xray`]: filtro di solo rendering (minerali + fluidi + entità); hitbox
//!   e collisioni invariate.
//! - [`greedy`]: greedy meshing con esclusioni di gioco (redstone, block
//!   entity, aggiornamenti frequenti, random tick, fluidi, foglie).
//! - [`bake`]: mesh statiche per casse/shulker chiuse, invalidazione O(1)
//!   same-frame a ogni block update (pistone, hopper, apertura).
//! - [`leaves`]: A2C/dithered alpha e hollow (culling foglie interne);
//!   random tick e passaggio luce restano vanilla.
//! - [`world`]: `MiniWorld` di riferimento con la logica di gioco, usato dai
//!   test di integrità (pistoni, farm, luce, raycast).
//!
//! ## Principio architetturale
//!
//! Il mondo è la sorgente di verità. Il rendering è derivato: `set_block` /
//! `block_update` invalidano le mesh, mai il contrario. Nessun modulo di
//! rendering scrive nello stato di gioco.

#![forbid(unsafe_code)]

pub mod bake;
pub mod blocks;
pub mod config;
pub mod greedy;
pub mod leaves;
pub mod world;
pub mod xray;

pub use bake::{BakeUpdate, StaticBakeManager};
pub use blocks::{props, BlockId, BlockProps, Pos};
pub use config::{LeavesMode, RenderConfig, XRAY_WARNING};
pub use greedy::{greedy_mesh, Quad, Volume};
pub use world::{Dir, MiniWorld};
pub use xray::{collision_solid, render_visible};