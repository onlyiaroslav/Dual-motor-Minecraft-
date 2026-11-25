# Doppio Motore — bridge Rust ↔ Java

Architettura ibrida per **Starling** (Android, fork di Horizon Android) e
**Horizon** (PC): il motore nativo Rust (Vulkan/wgpu) gestisce rendering,
fisica, chunk e input; una **JVM headless** esegue i jar Fabric/NeoForge
**originali** delle mod Java. Comunicazione in-process tramite bridge a 8 bit
con Lookup Table precompilata. **Zero AI a runtime.**

## Layout

```
crates/bridge-u8/     # Lato Rust: LUT u16 → NativeAction, dispatch_byte_stream
crates/bridge-jni/    # Trasporto in-process: JNI + ring buffer SPSC condiviso
crates/starling-features/  # Feature client: X-Ray, greedy meshing, bake, foglie
crates/starling-social/    # Fase 6: amici, token monouso (OsRng), auth offline, LAN/P2P
crates/mod-analyzer/       # Fase 7: parser JAR/class → sole firme dei metodi (zero bytecode)
crates/local-ai/           # Fase 7: client HTTP SmolLM2, toggle, timeout, fallback
crates/mod-converter/      # Fase 7: orchestrazione → mapping.bin + conversion_log.json
java/bridge-u8/       # Lato Java: LUT, U8Bridge, RingBuffer, host headless,
                      #   lettore manifest reali (Fabric/NeoForge/Forge)
java/pack-importer/   # Importatore texture pack: zip-slip/bomb guard, mcmeta,
                      #   gate anti-loop (fix download infinito)
java/social-bridge/   # Bridge Java + test JNI della schermata "Amici"
java/ai-converter/    # Fase 7: bridge Java + test JNI del convertitore/AI locale
docs/ANDROID.md       # Build NDK, Service Android, rischi di crash
docs/STARLING_INTEGRATION.md  # Pacchetto finale: comandi, CMake/Gradle, modifiche Linux→Android
docs/starling_jvm_host.cpp  # Snippet C++ illustrativo (sostituito da starling/native/)
starling/             # Integrazione produzione: native host C++, CMake, Gradle,
                      #   BridgeService.kt, TexturePackImporter.kt, FriendsScreen.kt,
                      #   SettingsScreen.kt (Fase 7: toggle AI Locale)
tests/game_loop_sim.cpp     # Simulazione 60 FPS: C++ + JVM reale + Rust in-process
RECOMMENDED_PACKS.md  # Pack leggeri compatibili con le ottimizzazioni
docs/SOCIAL.md        # Fase 6: protocollo auth, token, JNI, LAN, test
docs/CONVERSION.md    # Fase 7: parser JAR, AI locale, toggle, mapping.bin, log, test
scripts/roundtrip.sh  # Verifica cross-linguaggio file-based
scripts/jni-test.sh   # Verifica JNI end-to-end in-process
scripts/game-loop-sim.sh    # Game loop 60 FPS + stress attach/detach (anti-leak)
```

## Contratto di wire (congelato)

- Flusso di byte `u8`; ogni pacchetto = ID `u16` **little-endian** (2 byte).
- `mapping.bin`: magic `BU8M`, versione u16=1, conteggio u32, record
  `wire_id u16 + action_id u16`. Generato **offline**, caricato all'avvio.
- ID azione stabili e identici nei due linguaggi (0–11, vedi `NativeAction`).
- JVM: bufferizza e fa **un flush per frame**. Rust: indicizzazione diretta
  in array piatto (65536 slot), fail-fast su ID non mappato.

## Trasporto in-process (Fase 1, passo 2)

Ring buffer SPSC su memoria condivisa (direct ByteBuffer in-process o mmap):

```
header 128 B: magic BRG1 | ver | capacità | flags
              write_index u64 @16 (Java, release) | dropped u64 @24
              read_index  u64 @64 (Rust, release)  ← cache line separate
dati @128: pacchetti u16 LE, indici monotoni, slot = idx & (cap-1)
```

- Java produce (`RingBuffer` / `U8Bridge` invariato), Rust consuma con
  `nativePoll` dal thread del motore: **zero chiamate JNI per pacchetto**.
- Le azioni risolte tornano al sink Java in **batch** (una callback per frame).
- Alternativa senza ring: `nativeDispatch(handle, byte[], len)`.
- Overflow: mai bloccante (scarto contato in `dropped_bytes`).

## Caricamento mod reali

- `JarManifest`: legge `fabric.mod.json`, `neoforge.mods.toml`, `mods.toml`,
  `mcmod.info` dai jar originali (nessuna decompilazione).
- `RealJarModHost`: entrypoint "main" in classloader headless (blocklist
  rendering); quarantena per-mod su qualunque `Throwable`.
- `ModRegistry.scan`: mapping presente → pronta; assente → analisi offline.
- Limite noto: il bootstrap completo Fabric Loader/NeoForge richiede le classi
  shim di Minecraft fornite dal motore (prossimo milestone).

## Feature client (starling-features)

Vincolo rigido: **integrità della logica di gioco** (tick 20 TPS, redstone,
farm, collisioni, luce, spawn). Il rendering è derivato, mai sorgente di verità.

- **X-Ray toggle** (`RenderConfig::xray_enabled`): rendering di soli minerali,
  fluidi ed entità; avviso obbligatorio all'attivazione ("Solo per
  Singleplayer/Server privati…"); hitbox/collisioni invariate (test con raycast).
- **Greedy meshing** con esclusioni: redstone, block entity, aggiornamenti
  frequenti, random tick (colture/farmland), fluidi, foglie → mai mergeati.
- **Baking statico** casse/shulker chiuse: ogni block update (pistone, hopper,
  apertura) distrugge la mesh baked O(1) nello stesso frame.
- **Foglie** A2C/dithered + hollow (culling interne e facce same-block);
  decadimento random tick e passaggio luce restano vanilla.
- **Importatore texture pack**: `ACTION_OPEN_DOCUMENT` (Kotlin) + core Java con
  zip-slip/zip-bomb guard, generazione/correzione `pack.mcmeta`, gate anti-loop
  (pack esterno invalido → scartato con errore, MAI restart download base).

Test di integrità: pistone che spinge cassa baked, farm identica con
ottimizzazioni on/off, decadimento foglie, luce/spawn mob invariati.

## Social offline (starling-social)

Fase 6, **puramente additiva**: non tocca l'autenticazione Microsoft, il game
loop, `starling-features` o `bridge-jni`. È un layer per account locali.

- **Amici** (`Friend`): nickname, UUID locale v4 (OsRng), stato online/offline;
  `FriendList` persistente su file con salvataggio atomico.
- **Codici monouso** (`TokenStore`): 8 caratteri da alfabeto senza ambiguità
  (40 bit), generati con `rand::rngs::OsRng`, TTL 5 minuti, `HashMap` con
  IP/porta del mondo host, consumati e invalidati al primo uso.
- **Mini-server TCP auth** (`network_auth`): porta dedicata, separata dal
  bridge a 8 bit. `LOGIN` offline → `CODE_REQUIRED`; `TOKEN` valido →
  `WELCOME <uuid> <nick> offline` con profilo finto e `online_mode=false` solo
  per quel giocatore; `LOGIN_MS` → `PASS_THROUGH microsoft` (standard intatto).
  Max 3 tentativi, timeout, token mai loggati.
- **LAN** (`LanTunnel`): TCP diretto sulla stessa Wi-Fi. `FutureP2pTunnel` è lo
  stub esplicito (iroh/libp2p) dietro il trait `P2pTunnel`, non implementato.
- **UI "Amici"** (`starling/FriendsScreen.kt`): lista, aggiunta per nickname,
  "Invita nel mondo" con codice, countdown 5 minuti e copia negli appunti.
- **JNI** (`--features jni`): `libstarling_social.so` separata; la classe
  `java/social-bridge/.../SocialBridge.java` espone lista, inviti e server.

Test: token (formato, unicità, monouso, scadenza), due client reali
(host + offline), riuso negato, Microsoft pass-through, LAN, e convivenza con
X-Ray/casse baked senza desync (mondo di controllo vs mondo con join).

## Conversione offline con AI locale (Fase 7)

Tool **offline** di conversione mod, puramente additivo: non tocca `bridge-u8`,
`bridge-jni`, `starling-features`, il game loop o il layer social. L'AI gira
**solo in conversione**, mai a runtime durante il gameplay.

- **`mod-analyzer`**: parser JAR (ZIP) + class file JVM che estrae **solo le
  firme** dei metodi (`classe`, `nome`, `descriptor`). Nessun bytecode, nessuna
  decompilazione, nessun asset. Gestisce entry stored e deflate.
- **`local-ai`** (`LocalAiEngine` + `HttpAiBackend`): client **HTTP** verso un
  server OpenAI-compatible locale (default
  `http://127.0.0.1:8080/v1/chat/completions`; `llama-server`, LM Studio,
  Ollama…). Nessun FFI/compilazione C++: il modello (SmolLM2 360M GGUF Q4_K_M)
  gira in un processo separato. Espone
  `translate_fragment(firma) -> Option<NativeAction>`, formatta il prompt
  (risposta vincolata a `NativeTick`, `NativeHud`, `NativeKey`, `PlaySound`,
  `SetBlock` o `NONE`), estrae `choices[0].message.content`, gira su thread
  separato (`translate_in_background`) con **timeout di 5 s** e fallback.
  Stati: `Ready` / `NotReady` / `Disarmed`; se il server non risponde la
  traduzione restituisce `None` (fallback), mai un panic.
- **`mod-converter`**: orchestra parser + AI e produce due artefatti:
  - `mapping.bin`: dizionario di vocabolario nel formato LUT `bridge-u8`
    (`wire_id u16 → action_id u16`), ricaricabile dal motore;
  - `conversion_log.json`: registro delle decisioni per ogni firma.
- **Toggle "AI Locale per Conversione Mod"** (`starling/SettingsScreen.kt`):
  - **ATTIVO**: il server AI locale traduce le API sconosciute e aggiorna
    `mapping.bin`;
  - **DISATTIVO**: si usa solo la libreria esistente, le API sconosciute sono
    scartate e loggate come `"AI disattivata"`;
  - **server non raggiungibile o timeout** ⇒ fallback, come se il toggle fosse
    spento.
- **JNI** (`--features jni`): `libmod_converter.so` separata; la classe
  `java/ai-converter/.../AiConverterBridge.java` espone toggle, stato e
  conversione alla UI Kotlin.

**Server AI locale** (fuori dall'app, tutto in locale):

```bash
llama-server -m smollm2-360m-instruct-q4_k_m.gguf \
  --host 127.0.0.1 --port 8080 -c 512
# su Android con server sul PC:
adb reverse tcp:8080 tcp:8080
```

Dettagli e alternativa (Ollama/LM Studio) in `docs/CONVERSION.md`.

**Vincoli legali**: zero codice copiato (solo firme + ID numerici + nomi azione),
nessun upload per mod "All Rights Reserved": tutto resta sul dispositivo.

Test: parser (stored/deflate/malformato), motore (prompt/parsing, toggle,
fallback, timeout, background, AI non interrogata se disattivata), trasporto
HTTP (mock + mini-server TCP reale, porta chiusa ⇒ fallback senza panic),
convertitore (mapping roundtrip con `bridge-u8`, log "AI disattivata", precedenza
libreria, zero bytecode), e 18 test JNI end-to-end.

## Comandi

```bash
CC=/usr/bin/gcc ~/.cargo/bin/cargo test --workspace   # test Rust (tutti i crate)
bash java/bridge-u8/build.sh                          # build + 74 test Java
bash java/social-bridge/build.sh                      # build Rust JNI + 20 test JNI social
bash java/ai-converter/build.sh                       # build Rust JNI + 18 test JNI convertitore/AI
bash scripts/jni-test.sh                              # JNI end-to-end in-process
bash scripts/roundtrip.sh                             # roundtrip file-based
bash scripts/game-loop-sim.sh                         # 600 frame a 60 FPS + 20 cicli attach/detach
```

Android: vedi `docs/ANDROID.md` e `docs/STARLING_INTEGRATION.md`
(cargo-ndk, CMake/Gradle, BridgeService, modifiche Linux→Android file per file).

## Roadmap

- **Fase 1 — Android (Starling)**: bridge Rust ✅, bridge Java ✅, host
  headless ✅, trasporto JNI + ring ✅, manifest jar reali ✅; prossimo:
  classi shim MC per bootstrap completo Fabric Loader, integrazione Starling.
- **Fase 6 — Social offline**: lista amici ✅, token monouso OsRng ✅,
  mini-server auth TCP ✅, LAN ✅, UI Amici ✅; P2P (iroh/libp2p) pianificato
  dietro il trait `P2pTunnel`.
- **Fase 7 — Conversione offline con AI locale**: parser JAR (sole firme) ✅,
  `LocalAiEngine` + backend HTTP OpenAI-compatible ✅, toggle UI ✅,
  `mapping.bin` + `conversion_log.json` ✅, JNI ✅; prossimo: campo endpoint
  nella UI, wizard di avvio del server e download guidato del modello.
- **Fase 2 — PC (Horizon)**: backend wgpu (DX12 + fallback Vulkan), hook
  Java → `horizon-mod-api` (`native_tick`, `native_hud`, `native_key`).
- **Fase 3**: sandbox mod nuove prima dell'ingresso in LUT; desync check
  Rust↔JVM con log e isolamento della mod colpevole.

## Vincoli rispettati

- Zero AI a runtime (AI solo in analisi offline/build).
- Android 15: storage specifico app (Scoped Storage), pagine 16 KB, namespace
  linker, minSdk 33 per VarHandle (target 35).
- Latenza: ~286 ns/pacchetto lato Rust; budget 5 ms/frame ampiamente rispettato.
- Licenze: i jar originali vengono eseguiti, mai decompilati o riscritti.

## Licenza

**Proprietaria — All Rights Reserved.** Copyright (c) 2026 onlyiaroslav.
Il codice originale del progetto non è riutilizzabile, copiabile o
distribuibile senza permesso scritto esplicito. Vedi il file [`LICENSE`](LICENSE).
I crate dichiarano `license-file = "../../LICENSE"` e `publish = false`
(nessuna pubblicazione su crates.io). Le dipendenze di terze parti e i file
originali di gioco/mod restano soggetti alle rispettive licenze.