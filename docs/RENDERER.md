# Renderer 3D e game loop (starling-render)

Il tap su **Play** apre `GameActivity` (SurfaceView, landscape): renderer
voxel Rust su **wgpu/Vulkan**, game loop a 60 FPS con tick di gioco a 20 TPS,
collegato al bridge a 8 bit esistente.

Componenti adattati da [starling-client/starling](https://github.com/starling-client/starling)
(MIT, commit `0e5bb58a`): camera/matematica JOML, ruoli touch, setup della
superficie, struttura pipeline. Attribution e testi delle licenze:
[`THIRD_PARTY_LICENSES`](../THIRD_PARTY_LICENSES).

---

## Architettura

```
MainActivity (Play)
   └─ Intent → GameActivity (SurfaceView + overlay X-Ray/Salta/Esci)
        ├─ UI thread: touch/resize/jump/X-Ray ──┐
        └─ render thread "starling-render":    │  serializzati su un lock
             1. MainActivity.nativeStartGame   │  (JVM headless, best-effort)
             2. nativeCreate(Surface, w, h)    │
             3. loop: nativeFrame() ───────────┘
                  │
                  ▼  libstarling_render.so (crates/starling-render)
             ┌────────────────────────────────────────────────┐
             │ bridge::poll() → starling_jvm_poll_frame() (C) │
             │   → NativeTransport.nativePoll (bridge-jni)    │
             │   → LUT dispatch → EngineSink → callback C     │
             │ Game::update(dt)                               │
             │   • camera/touch (camera.rs, touch.rs)         │
             │   • movimento + collisioni (collision_solid)   │
             │   • tick 20 TPS + random tick (MiniWorld)      │
             │   • mesh greedy/X-Ray (starling-features)      │
             │ Renderer::draw (wgpu/Vulkan, WGSL)             │
             │ pace_frame (budget 16.667 ms)                  │
             └────────────────────────────────────────────────┘
```

- **Superficie**: `ANativeWindow_fromSurface` + `create_surface_unsafe`
  (handle raw AndroidNdk); formato non-sRGB preferito, present mode **Fifo**
  (un driver Mali stallava in Mailbox → ANR, nota di Starling), clamp al
  limite del device. La ANativeWindow è acquisita/rilasciata con
  acquire/release.
- **Mesh**: `MiniWorld::mesh_region` → `greedy_mesh` di `starling-features`
  (esclusioni di gioco: redstone, block entity, random tick, fluidi, foglie;
  X-Ray = solo minerali/fluidi; hollow per le foglie). Il renderer disegna la
  mesh derivata; `set_block` invalida same-frame, mai il contrario.
- **Collisioni**: solo `collision_solid` (proprietà di gioco): l'X-Ray non
  altera hitbox/raycast. Tap = rompi, hold = piazza (raycast + faccia libera).
- **Bridge**: una poll per frame via l'host C esistente
  (`starling_jvm_poll_frame` → `nativePoll`), azioni in batch via
  `starling_jvm_set_action_callback`. Gli ID azione non carryano payload nel
  protocollo a 8 bit: sono contabilizzati come hook, mai tradotti in mutazioni
  arbitrarie del mondo. Senza JRE installata il poll ritorna 0 e il gioco 3D
  gira lo stesso.

## Librerie e ordine di caricamento

`GameActivity` carica, in ordine: `bridge_jni` → `starling_jvm_host` →
`starling_render`. Il renderer risolve i simboli C dell'host
(`starling_jvm_poll_frame`, `starling_jvm_set_action_callback`) dalla libreria
già caricata nello stesso namespace: nessun link formale in CMake.

## Build

```bash
# come gli altri crate (task Gradle cargoNdkBuildRender, agganciato a preBuild)
cargo ndk -t arm64-v8a -p 33 -o starling/src/main/jniLibs \
    build --release -p starling-render
```

wgpu è pinnato a `=30.0.1` (la versione di Starling upstream; la 30.0.2 ha
cambiato API). Il `Cargo.lock` committed pinna anche `wgpu-types`, `naga`,
`wgpu-core`, `wgpu-hal` a 30.0.1: non fare `cargo update` alla cieca.

Test host (camera, touch, game loop, X-Ray/integrità — il layer wgpu/JNI è
solo Android):

```bash
cargo test -p starling-render
```

## Controlli

| Input | Azione |
|---|---|
| Drag metà sinistra | stick di movimento (camminata 4.3 blocchi/s) |
| Drag metà destra | camera (180° sul lato lungo, sensibilità 1.0) |
| Tap breve sul mondo | rompi il blocco mirato (reach 6) |
| Hold ≥350 ms sul mondo | piazza un blocco sulla faccia libera |
| Pulsante Salta | salto (gravità vanilla-like) |
| Pulsante X-Ray | toggle + avviso obbligatorio off→on |
| Pulsante Esci | torna al launcher |

## Limiti noti (demo)

- Mondo demo deterministico 32×24×32 (terreno, minerali, stagno, alberi, farm,
  redstone con pistone): non è il mondo di gioco completo di Horizon.
- Niente texture/atlante: colori per blocco + lambert + nebbia.
- Niente audio: gli hook `PlaySound` del bridge sono contabilizzati.
- Il movimento è camminata + salto con collisioni AABB (niente nuoto/volo).