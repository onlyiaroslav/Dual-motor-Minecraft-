//! Camera yaw/pitch, matrice di vista e proiezione prospettica.
//!
//! Adattato da Starling (`starling-client/starling`, commit `0e5bb58a`,
//! `crates/horizon-client-render/src/camera.rs`): la trigonometria float e
//! `rotation_yxz`/`rotation(quat)` seguono JOML 1.10.9 (`Matrix4f`,
//! `Quaternionf`), la `perspective` è `Matrix4f.setPerspective` di JOML.
//! MIT — vedi THIRD_PARTY_LICENSES (Starling/Horizon e JOML).
//!
//! Copyright (c) 2015-2026 Richard Greenlees (JOML)
//! Copyright (c) 2026 Ludovic Marie and the Horizon contributors
//!
//! Convenzioni (come vanilla): yaw 0 = sud (+Z), yaw 90 = ovest (-X);
//! pitch positivo = guardare in basso. La vista è la coniugata della
//! rotazione della camera, non un look-at world-up.

/// `(float) (Math.PI / 180.0)`.
const DEG_TO_RAD: f32 = std::f32::consts::PI / 180.0;

/// `Math.sin(float)`: `java.lang.Math.sin` ristretto a f32 (JOML senza fast math).
pub fn sin(rad: f32) -> f32 {
    f64::from(rad).sin() as f32
}

/// `Math.cosFromSin(float, float)` di JOML. Il wrap usa `floor` (invece del
/// troncamento verso zero) così il segno è corretto anche per angoli negativi
/// o fuori giro: lo yaw può crescere senza limite con i drag del touch.
pub fn cos_from_sin(sin: f32, angle: f32) -> f32 {
    let cos = (1.0 - sin * sin).sqrt();
    let a = angle + std::f32::consts::FRAC_PI_2;
    let b = a - (a / std::f32::consts::TAU).floor() * std::f32::consts::TAU;
    if b >= std::f32::consts::PI { -cos } else { cos }
}

/// Un `Quaternionf` JOML (x, y, z, w).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Quat {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub w: f32,
}

impl Quat {
    /// `Quaternionf.rotationYXZ(angleY, angleX, angleZ)`.
    pub fn rotation_yxz(angle_y: f32, angle_x: f32, angle_z: f32) -> Self {
        let sx = sin(angle_x * 0.5);
        let cx = cos_from_sin(sx, angle_x * 0.5);
        let sy = sin(angle_y * 0.5);
        let cy = cos_from_sin(sy, angle_y * 0.5);
        let sz = sin(angle_z * 0.5);
        let cz = cos_from_sin(sz, angle_z * 0.5);
        let x = cy * sx;
        let y = sy * cx;
        let z = sy * sx;
        let w = cy * cx;
        Self {
            x: x * cz + y * sz,
            y: y * cz - x * sz,
            z: w * sz - z * cz,
            w: w * cz + z * sz,
        }
    }
}

/// `Matrix4f.rotation(Quaternionfc)` di JOML, column-major (solo i 3×3 della
/// rotazione; il resto è l'identità).
pub fn rotation_matrix(q: Quat) -> [f32; 16] {
    let (w2, x2, y2, z2) = (q.w * q.w, q.x * q.x, q.y * q.y, q.z * q.z);
    let dzw = q.z * q.w * 2.0;
    let dxy = q.x * q.y * 2.0;
    let dxz = q.x * q.z * 2.0;
    let dyw = q.y * q.w * 2.0;
    let dyz = q.y * q.z * 2.0;
    let dxw = q.x * q.w * 2.0;
    let mut m = [0.0f32; 16];
    m[0] = w2 + x2 - z2 - y2;
    m[1] = dxy + dzw;
    m[2] = dxz - dyw;
    m[4] = -dzw + dxy;
    m[5] = y2 - z2 + w2 - x2;
    m[6] = dyz + dxw;
    m[8] = dyw + dxz;
    m[9] = dyz - dxw;
    m[10] = z2 - y2 - x2 + w2;
    m[15] = 1.0;
    m
}

/// `Camera.getViewRotationMatrix`/`view_matrix` di Starling: la coniugata del
/// quaternione `setRotation(yaw, pitch)` della camera, come matrice 4×4
/// column-major (mondo → vista, senza traslazione).
pub fn view_matrix(yaw: f32, pitch: f32) -> [f32; 16] {
    let q = Quat::rotation_yxz(
        std::f32::consts::PI - yaw * DEG_TO_RAD,
        -pitch * DEG_TO_RAD,
        0.0,
    );
    rotation_matrix(Quat { x: -q.x, y: -q.y, z: -q.z, w: q.w })
}

/// `Projection.getMatrix` di Starling: `Matrix4f.setPerspective(fov, aspect,
/// near, far, zZeroToOne)` di JOML, column-major. `zero_to_one` per la depth
/// di wgpu/Vulkan (NDC z 0..1).
pub fn perspective(
    fov_degrees: f32,
    width: f32,
    height: f32,
    near: f32,
    far: f32,
    zero_to_one: bool,
) -> [f32; 16] {
    let h = f64::from(fov_degrees * DEG_TO_RAD * 0.5).tan() as f32;
    let aspect = width / height;
    let mut m = [0.0f32; 16];
    m[0] = 1.0 / (h * aspect);
    m[5] = 1.0 / h;
    m[10] = (if zero_to_one { far } else { far + near }) / (near - far);
    m[14] = (if zero_to_one { far } else { far + far }) * near / (near - far);
    m[11] = -1.0;
    m
}

/// Moltiplicazione di matrici 4×4 column-major (`a * b`).
pub fn mat4_mul(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut out = [0.0f32; 16];
    for c in 0..4 {
        for r in 0..4 {
            let mut sum = 0.0;
            for k in 0..4 {
                sum += a[k * 4 + r] * b[c * 4 + k];
            }
            out[c * 4 + r] = sum;
        }
    }
    out
}

/// Camera in prima persona: posizione dell'occhio + yaw/pitch in gradi.
#[derive(Clone, Debug)]
pub struct Camera {
    /// Posizione dell'occhio nel mondo (blocchi).
    pub position: [f32; 3],
    /// Gradi: 0 = sud (+Z), 90 = ovest (-X).
    pub yaw: f32,
    /// Gradi: positivo = in basso, clampato a ±90 come vanilla.
    pub pitch: f32,
}

impl Camera {
    pub fn new(position: [f32; 3], yaw: f32, pitch: f32) -> Self {
        Self { position, yaw, pitch: pitch.clamp(-90.0, 90.0) }
    }

    /// Turning da touch/mouse: delta in gradi (positivo = destra / in basso).
    pub fn look(&mut self, dyaw: f32, dpitch: f32) {
        self.yaw += dyaw;
        self.pitch = (self.pitch + dpitch).clamp(-90.0, 90.0);
    }

    /// Base della camera (forward, right, up) nel mondo: colonne della
    /// rotazione NON coniugata, coerenti con `view_matrix` (vista = coniugata).
    /// forward = -colonna2 (la vista guarda lungo -Z).
    pub fn basis(&self) -> ([f32; 3], [f32; 3], [f32; 3]) {
        let q = Quat::rotation_yxz(
            std::f32::consts::PI - self.yaw * DEG_TO_RAD,
            -self.pitch * DEG_TO_RAD,
            0.0,
        );
        let m = rotation_matrix(q);
        let col = |c: usize| [m[c * 4], m[c * 4 + 1], m[c * 4 + 2]];
        let f = col(2);
        ([-f[0], -f[1], -f[2]], col(0), col(1))
    }

    /// Forward orizzontale (movimento a terra): yaw only, normalizzato.
    pub fn forward_horizontal(&self) -> [f32; 3] {
        let y = self.yaw * DEG_TO_RAD;
        [-sin(y), 0.0, cos_from_sin(sin(y), y)]
    }

    /// Right orizzontale (movimento a terra): perpendicolare al forward,
    /// verso la destra dello schermo (a yaw 0 = ovest, come vanilla).
    pub fn right_horizontal(&self) -> [f32; 3] {
        let f = self.forward_horizontal();
        [-f[2], 0.0, f[0]]
    }

    /// `view_proj` per lo shader: proiezione * vista * traslazione(-occhio).
    pub fn view_projection(&self, width: f32, height: f32, fov: f32, far: f32) -> [f32; 16] {
        let proj = perspective(fov, width, height, 0.05, far, true);
        let view = view_matrix(self.yaw, self.pitch);
        let mut translate = [0.0f32; 16];
        translate[0] = 1.0;
        translate[5] = 1.0;
        translate[10] = 1.0;
        translate[15] = 1.0;
        translate[12] = -self.position[0];
        translate[13] = -self.position[1];
        translate[14] = -self.position[2];
        mat4_mul(&proj, &mat4_mul(&view, &translate))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f32, b: f32) {
        assert!((a - b).abs() < 1e-4, "{a} != {b}");
    }

    #[test]
    fn yaw_zero_guarda_sud() {
        let cam = Camera::new([0.0; 3], 0.0, 0.0);
        let (f, _, _) = cam.basis();
        approx(f[0], 0.0);
        approx(f[1], 0.0);
        approx(f[2], 1.0);
    }

    #[test]
    fn yaw_90_guarda_ovest() {
        let cam = Camera::new([0.0; 3], 90.0, 0.0);
        let (f, _, _) = cam.basis();
        approx(f[0], -1.0);
        approx(f[2], 0.0);
    }

    #[test]
    fn pitch_positivo_guarda_in_basso() {
        let cam = Camera::new([0.0; 3], 0.0, 90.0);
        let (f, _, _) = cam.basis();
        approx(f[1], -1.0);
    }

    #[test]
    fn vista_inversa_della_base() {
        // view_matrix * forward = (0, 0, -1): la vista guarda lungo -Z.
        let cam = Camera::new([0.0; 3], 37.0, -21.0);
        let (f, _, _) = cam.basis();
        let v = view_matrix(cam.yaw, cam.pitch);
        let z = v[2] * f[0] + v[6] * f[1] + v[10] * f[2];
        approx(z, -1.0);
        let x = v[0] * f[0] + v[4] * f[1] + v[8] * f[2];
        approx(x, 0.0);
    }

    #[test]
    fn pitch_clampato() {
        let mut cam = Camera::new([0.0; 3], 0.0, 0.0);
        cam.look(0.0, 200.0);
        assert_eq!(cam.pitch, 90.0);
        cam.look(0.0, -400.0);
        assert_eq!(cam.pitch, -90.0);
    }

    #[test]
    fn proiezione_punto_centrale() {
        // Un punto davanti alla camera a yaw 0 finisce al centro dello schermo.
        let cam = Camera::new([0.0, 0.0, 0.0], 0.0, 0.0);
        let vp = cam.view_projection(16.0, 9.0, 70.0, 256.0);
        let p = [0.0f32, 0.0, 10.0, 1.0];
        let clip: [f32; 4] = std::array::from_fn(|r| {
            vp[r] * p[0] + vp[4 + r] * p[1] + vp[8 + r] * p[2] + vp[12 + r] * p[3]
        });
        approx(clip[0] / clip[3], 0.0);
        approx(clip[1] / clip[3], 0.0);
        assert!(clip[3] > 0.0);
    }
}