//! Renderer voxel: pipeline wgpu + shader WGSL per la mesh greedy di
//! `starling-features` (X-Ray, hollow ed esclusioni di gioco sono applicati
//! a monte, in `game.rs`/`starling-features`: qui si disegna e basta).
//!
//! Struttura della pipeline adattata da Starling (`starling-client/starling`,
//! commit `0e5bb58a`, `crates/horizon-client-render/src/gfx.rs`): depth
//! Depth32Float, cull back-face, un uniform buffer per frame. MIT — vedi
//! THIRD_PARTY_LICENSES.

use crate::game::{Game, Vertex, FAR, FOV};
use crate::surface::GpuSurface;

/// Shader voxel: lambert + ambient + nebbia verso il cielo. Niente texture
/// nel demo: il colore per blocco arriva dalla palette di `game.rs`.
const SHADER: &str = r#"
struct Uniforms {
    view_proj: mat4x4<f32>,
    eye: vec4<f32>,
};
@group(0) @binding(0) var<uniform> u: Uniforms;

struct VsIn {
    @location(0) pos: vec3<f32>,
    @location(1) normal: vec3<f32>,
    @location(2) color: vec3<f32>,
};
struct VsOut {
    @builtin(position) clip: vec4<f32>,
    @location(0) normal: vec3<f32>,
    @location(1) color: vec3<f32>,
    @location(2) world: vec3<f32>,
};

@vertex
fn vs_main(in: VsIn) -> VsOut {
    var out: VsOut;
    out.clip = u.view_proj * vec4<f32>(in.pos, 1.0);
    out.normal = in.normal;
    out.color = in.color;
    out.world = in.pos;
    return out;
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
    let n = normalize(in.normal);
    let sun = normalize(vec3<f32>(0.4, 0.8, 0.3));
    let light = max(dot(n, sun), 0.0);
    var col = in.color * (0.35 + 0.65 * light);
    // Nebbia verso il colore del cielo (profondità senza texture).
    let dist = length(in.world - u.eye.xyz);
    let fog = clamp((dist - 24.0) / 48.0, 0.0, 1.0);
    col = mix(col, vec3<f32>(0.47, 0.65, 0.90), fog);
    return vec4<f32>(col, 1.0);
}
"#;

/// Cielo (clear color), uguale alla nebbia dello shader.
const SKY: wgpu::Color = wgpu::Color { r: 0.47, g: 0.65, b: 0.90, a: 1.0 };

pub struct Renderer {
    pipeline: wgpu::RenderPipeline,
    uniform_buf: wgpu::Buffer,
    bind_group: wgpu::BindGroup,
    depth: wgpu::Texture,
    depth_view: wgpu::TextureView,
    depth_size: (u32, u32),
    vertex_buf: Option<wgpu::Buffer>,
    vertex_count: u32,
    uploaded_version: u64,
}

impl Renderer {
    pub fn new(gpu: &GpuSurface) -> Self {
        let device = &gpu.device;
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("starling voxel shader"),
            source: wgpu::ShaderSource::Wgsl(std::borrow::Cow::Borrowed(SHADER)),
        });
        let bind_group_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("starling uniforms"),
            entries: &[wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::VERTEX | wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            }],
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("starling pipeline layout"),
            bind_group_layouts: &[Some(&bind_group_layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("starling voxel pipeline"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs_main"),
                compilation_options: Default::default(),
                buffers: &[Some(wgpu::VertexBufferLayout {
                    array_stride: std::mem::size_of::<Vertex>() as u64,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &[
                        wgpu::VertexAttribute {
                            format: wgpu::VertexFormat::Float32x3,
                            offset: 0,
                            shader_location: 0,
                        },
                        wgpu::VertexAttribute {
                            format: wgpu::VertexFormat::Float32x3,
                            offset: 12,
                            shader_location: 1,
                        },
                        wgpu::VertexAttribute {
                            format: wgpu::VertexFormat::Float32x3,
                            offset: 24,
                            shader_location: 2,
                        },
                    ],
                })],
            },
            primitive: wgpu::PrimitiveState {
                topology: wgpu::PrimitiveTopology::TriangleList,
                front_face: wgpu::FrontFace::Ccw,
                cull_mode: Some(wgpu::Face::Back),
                ..Default::default()
            },
            depth_stencil: Some(wgpu::DepthStencilState {
                format: wgpu::TextureFormat::Depth32Float,
                depth_write_enabled: Some(true),
                depth_compare: Some(wgpu::CompareFunction::Less),
                stencil: Default::default(),
                bias: Default::default(),
            }),
            multisample: Default::default(),
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs_main"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format: gpu.format().remove_srgb_suffix(),
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        });

        // Uniforms: mat4 view_proj (64 byte) + vec4 eye (16 byte).
        let uniform_buf = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("starling uniforms"),
            size: 80,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("starling uniforms"),
            layout: &bind_group_layout,
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: uniform_buf.as_entire_binding(),
            }],
        });

        let (w, h) = gpu.size();
        let (depth, depth_view) = Self::depth_texture(device, w, h);
        Self {
            pipeline,
            uniform_buf,
            bind_group,
            depth,
            depth_view,
            depth_size: (w, h),
            vertex_buf: None,
            vertex_count: 0,
            uploaded_version: u64::MAX,
        }
    }

    fn depth_texture(device: &wgpu::Device, w: u32, h: u32) -> (wgpu::Texture, wgpu::TextureView) {
        let tex = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("starling depth"),
            size: wgpu::Extent3d { width: w.max(1), height: h.max(1), depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Depth32Float,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            view_formats: &[],
        });
        let view = tex.create_view(&Default::default());
        (tex, view)
    }

    /// Resize: ricrea la depth se le dimensioni sono cambiate.
    pub fn resize(&mut self, gpu: &GpuSurface) {
        let size = gpu.size();
        if size != self.depth_size {
            let (depth, view) = Self::depth_texture(&gpu.device, size.0, size.1);
            self.depth = depth;
            self.depth_view = view;
            self.depth_size = size;
        }
    }

    /// Disegna un frame: upload mesh se cambiata, uniforms, render pass.
    /// `frame` è consumato dal present (wgpu 30: `Queue::present`).
    pub fn draw(&mut self, gpu: &GpuSurface, game: &Game, frame: wgpu::SurfaceTexture) {
        let device = &gpu.device;

        // Mesh derivata dal mondo: upload solo quando la versione cambia.
        if game.mesh_version() != self.uploaded_version {
            let vertices = game.vertices();
            let bytes = bytemuck::cast_slice(vertices);
            let buf = device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("starling voxel mesh"),
                size: bytes.len().max(4) as u64,
                usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
            gpu.queue.write_buffer(&buf, 0, bytes);
            self.vertex_buf = Some(buf);
            self.vertex_count = vertices.len() as u32;
            self.uploaded_version = game.mesh_version();
        }

        let (w, h) = gpu.size();
        let view_proj = game.camera.view_projection(w as f32, h as f32, FOV, FAR);
        let eye = game.camera.position;
        let mut uniforms = [0u8; 80];
        uniforms[..64].copy_from_slice(bytemuck::cast_slice(&view_proj));
        uniforms[64..76].copy_from_slice(bytemuck::cast_slice(&eye));
        gpu.queue.write_buffer(&self.uniform_buf, 0, &uniforms);

        // Vista non-sRGB (config.view_formats la include): il pipeline disegna
        // in lineare, come l'originale; il formato sRGB della swapchain è solo
        // per il present.
        let view = frame.texture.create_view(&wgpu::TextureViewDescriptor {
            format: Some(gpu.format().remove_srgb_suffix()),
            ..Default::default()
        });
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("starling frame"),
        });
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("starling world pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    resolve_target: None,
                    depth_slice: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(SKY),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                    view: &self.depth_view,
                    depth_ops: Some(wgpu::Operations {
                        load: wgpu::LoadOp::Clear(1.0),
                        store: wgpu::StoreOp::Store,
                    }),
                    stencil_ops: None,
                }),
                ..Default::default()
            });
            if self.vertex_count > 0 {
                if let Some(buf) = &self.vertex_buf {
                    pass.set_pipeline(&self.pipeline);
                    pass.set_bind_group(0, &self.bind_group, &[]);
                    pass.set_vertex_buffer(0, buf.slice(..));
                    pass.draw(0..self.vertex_count, 0..1);
                }
            }
        }
        gpu.queue.submit(Some(encoder.finish()));
        gpu.queue.present(frame);
    }
}