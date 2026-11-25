# Fase 7 — Conversione offline con AI locale

Tool di conversione delle mod Java per Starling, eseguito **offline** (mai durante
il gameplay). Produce un dizionario di vocabolario (`mapping.bin`) e un log
(`conversion_log.json`) a partire dalle **sole firme** dei metodi, con l'aiuto
facoltativo di un modello linguistico locale (**SmolLM2 360M**, GGUF Q4_K_M, via
llama.cpp).

## Vincoli (fondamentali)

1. **Zero codice copiato.** Il tool non conserva né esporta bytecode Java o asset
   delle mod. Estrae solo firme (`classe`, `nome metodo`, `descriptor JVM`) e
   produce ID numerici + nomi di azioni. Niente decompilazione.
2. **Nessun upload.** Per le mod "All Rights Reserved" tutto resta sul
   dispositivo: il modello è locale, l'inferenza è locale, gli artefatti sono
   locali.
3. **Zero regressioni.** I crate `mod-analyzer`, `local-ai`, `mod-converter` sono
   additivi: non toccano `bridge-u8`, `bridge-jni`, `starling-features`, il game
   loop o `starling-social`.

## Architettura

```
JAR della mod
   │  mod-analyzer::extract_method_signatures   (parser ZIP + class file JVM)
   ▼
[ JavaMethodSignature { class_name, name, descriptor } ]   ← solo firme
   │  mod-converter::convert_signatures
   │    ├─ libreria mapping.bin esistente?  → usa quell'azione (source=library)
   │    └─ altrimenti → LocalAiEngine.translate(firma)
   │         ├─ Ready    → SmolLM2 → azione (source=smollm2)
   │         ├─ Disarmed → scarta, "AI disattivata" (source=disabled)
   │         └─ NotReady/timeout → scarta (source=fallback)
   ▼
ConversionReport
   ├─ bridge_u8::mapping::write_mapping → mapping.bin   (wire_id u16 → action_id u16)
   └─ conversion_log_json              → conversion_log.json
```

### `mod-analyzer`
- Parser JAR minimale (central directory + local header), entry **stored** e
  **deflate** (`flate2`).
- Parser class file JVM **big-endian**: magic `0xCAFEBABE`, constant pool,
  interfacce, campi, metodi. Estrae `name_index`/`descriptor_index` → stringhe.
- Output: `JavaMethodSignature`. Nessun bytecode viene conservato.

### `local-ai`
- `AiBackend` (trait) + `LlamaCppBackend` (wrapper llama.cpp). Il punto di
  inserimento del runtime reale (`llama_model_load` / `llama_infer`) è in
  `backend.rs`; nella v0 l'inferenza è un placeholder che ritorna errore, così
  l'orchestrazione è testabile senza il runtime.
- `LocalAiEngine`:
  - `translate_fragment(firma) -> Option<NativeAction>` (API richiesta);
  - `translate(firma) -> TranslationOutcome { native_action, source, took_ms }`;
  - `translate_in_background(firma) -> JoinHandle<TranslationOutcome>` (thread
    separato, la UI non blocca il gameplay);
  - `arm()` / `disarm()` (toggle), `state()`, `is_available()`;
  - timeout (`config.timeout_ms`): se superato ⇒ fallback.
- Stati: `Ready` (modello caricato), `NotReady` (modello assente/non caricato),
  `Disarmed` (spento dall'utente). **Se `Disarmed` o `NotReady`, l'AI non viene
  mai interrogata.**

### `mod-converter`
- `signature_key(sig)` = `classe.metodo` + descriptor; `wire_id_for(key)` = hash
  FNV-1a deterministico a 16 bit (stabile tra esecuzioni, per ritrovare la firma
  nella libreria esistente).
- `convert_signatures` / `convert_jar`: la **libreria ha la precedenza**; poi AI.
- `write_mapping_bin` (usa `bridge_u8::mapping::write_mapping`, formato `BU8M`
  v1), `write_conversion_log` (JSON scritto a mano, senza dipendenze).
- `convert_jar_to_files`: pipeline completa che scrive entrambi gli artefatti.

## Logica del toggle

| Stato AI | Firma nota in libreria | Firma sconosciuta |
|----------|------------------------|-------------------|
| ATTIVO (`Ready`) | usa libreria | SmolLM2 traduce → aggiorna `mapping.bin` |
| DISATTIVO (`Disarmed`) | usa libreria | scarta, log `"AI disattivata"` |
| modello assente (`NotReady`) / timeout | usa libreria | scarta, log fallback |

Il toggle è persistito in `SharedPreferences` (`starling_settings`,
chiave `ai_locale_conversione_mod`, default **false** per privacy).

## UI Kotlin

`starling/SettingsScreen.kt` (`SettingsActivity`), stile View programmatico come
`FriendsScreen.kt` (niente Compose):
- `Switch` **"AI Locale per Conversione Mod"** → `AiConverterBridge.setAiEnabled`;
- status testuale che descrive lo stato (`ready` / `not_ready` / `disarmed`) e la
  presenza del modello;
- campo "Percorso del file .jar" + pulsante **Converti** → `convertJar`, mostra il
  riepilogo e il percorso di output.

## JNI

`mod-converter --features jni` produce `libmod_converter.so` (separata da
`bridge-jni` e `libstarling_social.so`). Pattern identico a `starling-social`:
registry di handle opachi (`Mutex<HashMap<i64, ConverterHandle>>`), macro
`guarded!` con `catch_unwind`, nessun puntatore raw attraverso JNI.

Classe Java `dev.starling.converter.AiConverterBridge`:
- `nativeCreate(modelPath, libraryPath) -> long` (AI parte disattivata);
- `nativeSetAiEnabled(handle, enabled) -> String` (stato risultante);
- `nativeAiState(handle) -> String`;
- `nativeTranslateFragment(handle, firma) -> int` (action_id o -1);
- `nativeConvertJar(handle, jarPath, outDir) -> String` (riepilogo);
- `nativeFree(handle)`.

## Formato artefatti

### `mapping.bin` (identico a `bridge-u8`)
| Offset | Dim | Campo |
|--------|-----|-------|
| 0 | 4 | magic `BU8M` |
| 4 | 2 | versione u16 = 1 |
| 6 | 4 | numero entry u32 |
| 10 | 4×N | `wire_id u16` + `action_id u16` (little-endian) |

`action_id` validi: 0–11 (`NativeAction`). Il file è ricaricabile con
`bridge_u8::mapping::read_mapping` / `LookupTable::load`.

### `conversion_log.json`
```json
{
  "tool": "starling-mod-converter",
  "ai_was_ready": false,
  "total_signatures": 2,
  "mapped": 0,
  "ai_used": 0,
  "library_used": 0,
  "discarded": 2,
  "entries": [
    { "signature": "com.example.Test.a()V", "wire_id": 1234,
      "action": null, "action_id": null,
      "source": "disabled", "reason": "AI disattivata" }
  ]
}
```
Contiene **solo** firme, ID e nomi di azioni: nessun bytecode.

## Build & test

```bash
CC=/usr/bin/gcc ~/.cargo/bin/cargo test -p mod-analyzer -p local-ai -p mod-converter
bash java/ai-converter/build.sh     # build Rust JNI + 18 test JNI end-to-end
```

Copertura:
- **mod-analyzer** (5): estrazione firme, JAR senza classi, JAR malformato,
  entry deflate, errori display.
- **local-ai** (17): load/assente, arm/disarm, AI non interrogata se disattivata
  o not-ready, traduzione, fallback, timeout, background, `translate_fragment`.
- **mod-converter** (10): wire_id deterministico, AI attiva/disattivata,
  precedenza libreria, modello assente, roundtrip `mapping.bin` con `bridge-u8`,
  struttura log, firme duplicate, zero codice copiato, pipeline su file.
- **JNI** (18): default disarmed, toggle, fallback modello assente/presente,
  conversione con AI spenta (`mapping.bin` magic `BU8M`, log "AI disattivata",
  nessun bytecode), chiusura.

## Stato attuale / prossimo passo

Il runtime llama.cpp **non è ancora collegato**: `LlamaCppBackend.translate_fragment`
è un placeholder che ritorna errore di inferenza, quindi con modello presente lo
stato è `Ready` ma la traduzione va in fallback. L'orchestrazione (toggle,
timeout, libreria, artefatti, JNI, UI) è completa e testata. Prossimo milestone:
integrare il runtime llama.cpp reale e il download guidato del modello SmolLM2.