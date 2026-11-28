//! Controlli touch: stick di movimento + drag per la camera + tap/hold sul mondo.
//!
//! Adattato da Starling (`starling-client/starling`, commit `0e5bb58a`,
//! `crates/horizon-client-render/src/touch.rs` e `touch/aim.rs`), scritto da
//! Vasysik e i contributori di Starling. MIT — vedi THIRD_PARTY_LICENSES.
//!
//! Come nell'originale: ogni dito riceve un ruolo quando atterra (stick o
//! look) e lo tiene fino al sollevamento, così movimento e camera non si
//! rubano le dita a vicenda. Un look senza drag è un tap (breve) o un hold
//! (lungo): qui il tap rompe il blocco mirato, l'hold ne piazza uno.
//!
//! Coordinate normalizzate 0..1 sulla superficie (y in basso), come
//! `TouchControls.update` dell'originale.

use std::collections::BTreeMap;
use std::time::{Duration, Instant};

/// Un world touch tenuto così a lungo senza drag diventa un hold (mining/use).
const HOLD_DELAY: Duration = Duration::from_millis(350);
/// Un look che si muove oltre questa frazione dell'altezza è un drag, non un tap.
const DRAG_SLOP: f32 = 0.018;
/// Raggio dello stick, frazione del lato corto della superficie.
const STICK_RADIUS: f32 = 0.15;
/// Zona dello stick: parte sinistra dello schermo (frazione della larghezza).
const STICK_ZONE: f32 = 0.4;

/// Fase del dito (da `MotionEvent` Android).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Phase {
    Down,
    Move,
    Up,
}

/// Cosa fa un dito, deciso quando atterra (come `Role` dell'originale).
#[derive(Clone, Copy, Debug)]
enum Role {
    /// Stick: misurato da questo anchor.
    Move { anchor: [f32; 2] },
    /// Camera; senza drag, un tap breve o un hold lungo agisce sul mondo.
    Look {
        start: [f32; 2],
        last: [f32; 2],
        since: Instant,
        /// Oltre DRAG_SLOP: è un drag della camera, non un tap.
        dragged: bool,
        /// Divenuto hold prima del drag: continua ad agire mentre gira.
        committed: bool,
    },
}

/// Stato dei controlli touch. Il gioco lo alimenta con [`Self::update`] e
/// legge `move_vec`, [`Self::drain_look`], `tap` e `hold` una volta per frame.
pub struct TouchControls {
    fingers: BTreeMap<i32, Role>,
    surface: [f32; 2],
    /// Vettore dello stick (-1..1): x = destra, y = avanti.
    pub move_vec: [f32; 2],
    /// Sensibilità del look (1.0 = un drag sul lato lungo gira di 180°).
    pub sensitivity: f32,
    /// Tap breve sul mondo (punto normalizzato), consumato dal gioco.
    pub tap: Option<[f32; 2]>,
    /// Hold lungo sul mondo (punto normalizzato), consumato dal gioco.
    pub hold: Option<[f32; 2]>,
    look_acc: [f32; 2],
}

impl Default for TouchControls {
    fn default() -> Self {
        Self {
            fingers: BTreeMap::new(),
            surface: [1.0, 1.0],
            move_vec: [0.0; 2],
            sensitivity: 1.0,
            tap: None,
            hold: None,
            look_acc: [0.0; 2],
        }
    }
}

impl TouchControls {
    /// Dimensioni della superficie in pixel (per `camera_delta`).
    pub fn set_surface(&mut self, width: u32, height: u32) {
        self.surface = [width.max(1) as f32, height.max(1) as f32];
    }

    /// Un evento dito. `point` è normalizzato 0..1 (y in basso).
    pub fn update(&mut self, id: i32, phase: Phase, point: [f32; 2]) {
        let surface = self.surface;
        let sensitivity = self.sensitivity;
        match phase {
            Phase::Down => {
                let role = if point[0] < STICK_ZONE {
                    Role::Move { anchor: point }
                } else {
                    Role::Look {
                        start: point,
                        last: point,
                        since: Instant::now(),
                        dragged: false,
                        committed: false,
                    }
                };
                self.fingers.insert(id, role);
            }
            Phase::Move => {
                let Some(role) = self.fingers.get_mut(&id) else { return };
                match role {
                    Role::Move { anchor } => {
                        let r = STICK_RADIUS * self.surface[0].min(self.surface[1])
                            / self.surface[0].max(self.surface[1]);
                        // Stick: spostamento dall'anchor, clampato al raggio.
                        let dx = (point[0] - anchor[0]) / r.max(1e-6);
                        let dy = (point[1] - anchor[1]) / r.max(1e-6);
                        let len = (dx * dx + dy * dy).sqrt();
                        let k = if len > 1.0 { 1.0 / len } else { 1.0 };
                        // y dello schermo in basso → avanti è -dy.
                        self.move_vec = [dx * k, -dy * k];
                    }
                    Role::Look { start, last, since, dragged, committed } => {
                        let dist = [point[0] - start[0], point[1] - start[1]];
                        if (dist[0] * dist[0] + dist[1] * dist[1]).sqrt() > DRAG_SLOP {
                            *dragged = true;
                        }
                        if *dragged {
                            let delta = [point[0] - last[0], point[1] - last[1]];
                            let turn = camera_delta(delta, surface, sensitivity);
                            self.look_acc[0] += turn[0];
                            self.look_acc[1] += turn[1];
                        } else if !*committed && since.elapsed() >= HOLD_DELAY {
                            *committed = true;
                            self.hold = Some(*start);
                        }
                        *last = point;
                    }
                }
            }
            Phase::Up => {
                if let Some(role) = self.fingers.remove(&id) {
                    match role {
                        Role::Move { .. } => self.move_vec = [0.0; 2],
                        Role::Look { start, since, dragged, .. } => {
                            if !dragged && since.elapsed() < HOLD_DELAY {
                                self.tap = Some(start);
                            }
                        }
                    }
                }
            }
        }
    }

    /// Gradi di camera accumulati dall'ultimo drain (yaw, pitch).
    pub fn drain_look(&mut self) -> [f32; 2] {
        std::mem::take(&mut self.look_acc)
    }
}

/// `touch/aim.rs::camera_delta` di Starling: un drag attraversa il lato
/// lungo = 180°, stesso angolo per drag fisicamente uguali sui due assi
/// (landscape e portrait), scalato per la sensibilità.
fn camera_delta(delta: [f32; 2], surface: [f32; 2], sensitivity: f32) -> [f32; 2] {
    let [w, h] = surface;
    let scale = w.max(h).max(1.0);
    [
        delta[0] * w / scale * 180.0 * sensitivity,
        delta[1] * h / scale * 180.0 * sensitivity,
    ]
}

/// `touch/aim.rs::screen_direction` di Starling: il raggio unitario
/// dall'occhio attraverso `point` (coordinate normalizzate, y in basso) per
/// una vista prospettica con `fov` verticale in gradi. La vista non gira:
/// un tap agisce dove atterra.
pub fn screen_direction(
    forward: [f32; 3],
    up: [f32; 3],
    point: [f32; 2],
    aspect: f32,
    fov: f32,
) -> [f32; 3] {
    // forward × up: la destra dello schermo nelle coordinate del gioco.
    let right = [
        forward[1] * up[2] - forward[2] * up[1],
        forward[2] * up[0] - forward[0] * up[2],
        forward[0] * up[1] - forward[1] * up[0],
    ];
    let t = (fov.to_radians() / 2.0).tan();
    let x = (point[0] * 2.0 - 1.0) * aspect * t;
    let y = (1.0 - point[1] * 2.0) * t;
    let ray = [
        forward[0] + right[0] * x + up[0] * y,
        forward[1] + right[1] * x + up[1] * y,
        forward[2] + right[2] * x + up[2] * y,
    ];
    let len = ray.iter().map(|v| v * v).sum::<f32>().sqrt();
    ray.map(|v| v / len)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn touch() -> TouchControls {
        let mut t = TouchControls::default();
        t.set_surface(2400, 1080);
        t
    }

    #[test]
    fn drag_fisico_uguale_su_entrambi_gli_assi() {
        let t = touch();
        // 24 pixel in orizzontale (24/2400) e 24 in verticale (24/1080):
        // stesso angolo, 180° * 24 / lato lungo = 1.8°.
        let a = camera_delta([24.0 / 2400.0, 0.0], t.surface, t.sensitivity);
        let b = camera_delta([0.0, 24.0 / 1080.0], t.surface, t.sensitivity);
        assert!((a[0] - b[1]).abs() < 1e-3);
        assert!((a[0] - 1.8).abs() < 1e-3);
    }

    #[test]
    fn stick_e_look_non_si_rubano_le_dita() {
        let mut t = touch();
        t.update(0, Phase::Down, [0.15, 0.7]); // stick a sinistra
        t.update(1, Phase::Down, [0.7, 0.5]); // look a destra
        t.update(0, Phase::Move, [0.15, 0.6]); // avanti
        t.update(1, Phase::Move, [0.8, 0.5]); // drag della camera
        assert!(t.move_vec[1] > 0.5, "stick avanti: {:?}", t.move_vec);
        let look = t.drain_look();
        assert!(look[0] > 0.0, "camera girata a destra: {look:?}");
        // Il movimento dello stick non ha girato la camera.
        assert_eq!(t.drain_look(), [0.0, 0.0]);
        t.update(0, Phase::Up, [0.15, 0.6]);
        assert_eq!(t.move_vec, [0.0, 0.0]);
    }

    #[test]
    fn tap_breve_sul_mondo() {
        let mut t = touch();
        t.update(1, Phase::Down, [0.7, 0.5]);
        t.update(1, Phase::Up, [0.7, 0.5]);
        assert_eq!(t.tap, Some([0.7, 0.5]));
        assert_eq!(t.drain_look(), [0.0, 0.0]);
    }

    #[test]
    fn drag_non_e_tap() {
        let mut t = touch();
        t.update(1, Phase::Down, [0.7, 0.5]);
        t.update(1, Phase::Move, [0.9, 0.5]); // oltre DRAG_SLOP
        t.update(1, Phase::Up, [0.9, 0.5]);
        assert_eq!(t.tap, None);
    }

    #[test]
    fn raggio_centrale_e_in_alto() {
        let f = [0.0f32, 0.0, 1.0];
        let up = [0.0f32, 1.0, 0.0];
        let d = screen_direction(f, up, [0.5, 0.5], 16.0 / 9.0, 70.0);
        assert!((d[2] - 1.0).abs() < 1e-5);
        let d = screen_direction(f, up, [0.5, 0.0], 16.0 / 9.0, 70.0);
        assert!(d[1] > 0.0, "tap in alto → raggio verso l'alto: {d:?}");
    }
}