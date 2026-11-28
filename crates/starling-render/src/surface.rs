//! Superficie wgpu/Vulkan su `ANativeWindow` (SurfaceView di GameActivity).
//!
//! Adattato da Starling (`starling-client/starling`, commit `0e5bb58a`,
//! `crates/horizon-client-render/src/gfx/surface_setup.rs` e `gfx.rs`):
//! scelta del formato non-sRGB, clamp al limite del device, present mode.
//! MIT — vedi THIRD_PARTY_LICENSES.
//!
//! Differenza dall'originale (winit/NativeActivity): qui la finestra arriva
//! da una `SurfaceView` via JNI, quindi `ANativeWindow_fromSurface` +
//! `create_surface_unsafe` con handle raw AndroidNdk. La `ANativeWindow` è
//! acquisita e rilasciata con acquire/release (vive quanto questa struct).

use std::ffi::c_void;
use std::ptr::NonNull;

use jni::objects::JObject;
use jni::JNIEnv;

// libandroid (NDK): ANativeWindow_fromSurface/acquire/release.
#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(
        env: *mut jni::sys::JNIEnv,
        surface: jni::sys::jobject,
    ) -> *mut c_void;
    fn ANativeWindow_acquire(window: *mut c_void);
    fn ANativeWindow_release(window: *mut c_void);
}

/// Istance/device/queue + superficie configurata, pronta per il frame.
pub struct GpuSurface {
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    surface: wgpu::Surface<'static>,
    config: wgpu::SurfaceConfiguration,
    window: *mut c_void,
}

impl GpuSurface {
    /// Crea device e superficie dalla `Surface` Java della SurfaceView.
    pub fn new(
        env: &JNIEnv,
        surface_obj: &JObject,
        width: u32,
        height: u32,
    ) -> Result<Self, String> {
        // SAFETY: env è valido per la chiamata JNI; surface_obj è la Surface
        // della SurfaceView (viva: GameActivity la tiene fino a surfaceDestroyed).
        let window = unsafe {
            ANativeWindow_fromSurface(env.get_native_interface(), surface_obj.as_raw())
        };
        if window.is_null() {
            return Err("ANativeWindow_fromSurface: superficie nulla".into());
        }
        // SAFETY: window valida, acquisita per tutta la vita di GpuSurface.
        unsafe { ANativeWindow_acquire(window) };

        let result = Self::with_window(window, width, height);
        if result.is_err() {
            // SAFETY: rilasciamo il riferimento acquisito sopra in caso di errore.
            unsafe { ANativeWindow_release(window) };
        }
        result
    }

    fn with_window(window: *mut c_void, width: u32, height: u32) -> Result<Self, String> {
        // Solo Vulkan: è il backend Android del progetto (niente GL translation).
        let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
        desc.backends = wgpu::Backends::VULKAN;
        let instance = wgpu::Instance::new(desc);

        // SAFETY: window è una ANativeWindow valida e acquisita; vive quanto
        // la superficie (Drop rilascia). Display handle Android vuoto: il
        // backend Vulkan usa VK_KHR_android_surface sulla finestra.
        let surface = unsafe {
            instance.create_surface_unsafe(wgpu::SurfaceTargetUnsafe::RawHandle {
                raw_display_handle: Some(wgpu::rwh::RawDisplayHandle::Android(
                    wgpu::rwh::AndroidDisplayHandle::new(),
                )),
                raw_window_handle: wgpu::rwh::RawWindowHandle::AndroidNdk(
                    wgpu::rwh::AndroidNdkWindowHandle::new(
                        NonNull::new(window).ok_or("ANativeWindow nulla")?,
                    ),
                ),
            })
        }
        .map_err(|e| format!("create_surface: {e}"))?;

        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            compatible_surface: Some(&surface),
            force_fallback_adapter: false,
            ..Default::default()
        }))
        .map_err(|e| format!("adapter Vulkan: {e}"))?;
        log::info!("starling-render: adapter {:?}", adapter.get_info());

        // Limits di default: massima compatibilità coi driver mobile.
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("starling-render device"),
            required_features: wgpu::Features::empty(),
            required_limits: wgpu::Limits::default(),
            memory_hints: wgpu::MemoryHints::MemoryUsage,
            ..Default::default()
        }))
        .map_err(|e| format!("device: {e}"))?;

        let caps = surface.get_capabilities(&adapter);
        let config = Self::default_config(&surface, &adapter, &caps, width, height)?;
        surface.configure(&device, &config);

        Ok(Self { device, queue, surface, config, window })
    }

    /// Configurazione di default clampata al device, formato non-sRGB
    /// preferito (`surface_format` di Starling), Fifo su Android: un driver
    /// Mali è stato visto stallare per secondi in un present Mailbox prima
    /// del primo frame, abbastanza da far scattare l'ANR (nota di Starling).
    fn default_config(
        surface: &wgpu::Surface<'static>,
        adapter: &wgpu::Adapter,
        caps: &wgpu::SurfaceCapabilities,
        width: u32,
        height: u32,
    ) -> Result<wgpu::SurfaceConfiguration, String> {
        let max_side = adapter.limits().max_texture_dimension_2d;
        let mut config = surface
            .get_default_config(adapter, width.clamp(1, max_side), height.clamp(1, max_side))
            .ok_or("surface: nessuna configurazione supportata")?;
        config.format = [wgpu::TextureFormat::Bgra8Unorm, wgpu::TextureFormat::Rgba8Unorm]
            .into_iter()
            .find(|f| caps.formats.contains(f))
            .or_else(|| caps.formats.iter().copied().find(|f| !f.is_srgb()))
            .unwrap_or(config.format);
        config.view_formats = vec![config.format.remove_srgb_suffix()];
        config.present_mode = wgpu::PresentMode::Fifo;
        Ok(config)
    }

    /// Resize della SurfaceView (surfaceChanged): riconfigura la swapchain.
    pub fn resize(&mut self, width: u32, height: u32) {
        if width == 0 || height == 0 {
            return;
        }
        let max_side = self.device.limits().max_texture_dimension_2d;
        self.config.width = width.clamp(1, max_side);
        self.config.height = height.clamp(1, max_side);
        self.surface.configure(&self.device, &self.config);
    }

    pub fn size(&self) -> (u32, u32) {
        (self.config.width, self.config.height)
    }

    pub fn format(&self) -> wgpu::TextureFormat {
        self.config.format
    }

    /// Texture del frame corrente. Su superficie outdated/persa (rotazione,
    /// background) riconfigura e riprova una volta; poi segnala l'errore.
    /// `Suboptimal` è ancora un frame valido (wgpu consiglia di riconfigurare).
    pub fn frame(&mut self) -> Result<wgpu::SurfaceTexture, String> {
        use wgpu::CurrentSurfaceTexture as Cur;
        match self.surface.get_current_texture() {
            Cur::Success(t) => Ok(t),
            Cur::Suboptimal(t) => {
                self.surface.configure(&self.device, &self.config);
                Ok(t)
            }
            Cur::Timeout | Cur::Occluded => Err("surface: timeout/occlusa".into()),
            Cur::Outdated | Cur::Lost | Cur::Validation => {
                self.surface.configure(&self.device, &self.config);
                match self.surface.get_current_texture() {
                    Cur::Success(t) | Cur::Suboptimal(t) => Ok(t),
                    other => Err(format!("surface persa: {other:?}")),
                }
            }
        }
    }
}

impl Drop for GpuSurface {
    fn drop(&mut self) {
        // SAFETY: window acquisita in new; rilasciata una sola volta qui.
        unsafe { ANativeWindow_release(self.window) };
    }
}