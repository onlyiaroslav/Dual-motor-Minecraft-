# Fase 7 — Conversione offline con AI locale

Tool di conversione delle mod Java per Starling, eseguito **offline** (mai durante
il gameplay). Produce un dizionario di vocabolario (`mapping.bin`) e un log
(`conversion_log.json`) a partire dalle **sole firme** dei metodi, con l'aiuto
facoltativo di un modello linguistico locale (**SmolLM2 360M**, GGUF Q4_K_M)
esposto da un **server HTTP OpenAI-compatible** in ascolto su `127.0.0.1`
(`llama-server`, LM Studio, Ollama, ...). Niente FFI né compilazione C++.

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
   │         ├─ Ready    → server locale → azione (source=smollm2)
   │         ├─ Disarmed → scarta, "AI disattivata" (source=disabled)
   │         └─ NotReady/timeout/errore HTTP → scarta (source=fallback)
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
- `AiBackend` (trait) + **`HttpAiBackend`**: client HTTP verso un server
  OpenAI-compatible locale. Il modello gira in un **processo separato**; il
  crate non carica pesi né compila C++.
- **Endpoint predefinito**:
  `http://127.0.0.1:8080/v1/chat/completions` (`DEFAULT_ENDPOINT`),
  sovrascrivibile via `LocalAiConfig::endpoint` (preferenza Android
  `ai_locale_endpoint`).
- **Health check** (`load` / `arm`): connessione TCP all'host/porta
  dell'endpoint con timeout di 2 s. Se il server non risponde lo stato resta
  `NotReady`.
- **Prompt** inviato a SmolLM2:
  `"Sei un traduttore. Data questa firma: <firma>. Rispondi solo con l'azione
  nativa tra [NativeTick, NativeHud, NativeKey, PlaySound, SetBlock]. Se non
  sei sicuro, rispondi NONE."`
- **Richiesta**: `POST` JSON con `messages[0].content` = prompt,
  `temperature: 0`, `max_tokens: 16`, `stream: false`.
- **Risposta**: si estrae `choices[0].message.content`; il testo viene parsato
  in modo tollerante (case-insensitive, backtick/asterischi/spazi).
  Azione valida ⇒ `Some(NativeAction)`; `NONE`, testo sconosciuto, risposta
  malformata, errore HTTP o timeout ⇒ `Ok(None)` (fallback).
- **Timeout**: 5 s per richiesta (`INFERENCE_TIMEOUT_SECS`), 2 s per la
  connessione (`CONNECT_TIMEOUT_SECS`). Anche `LocalAiEngine` applica il proprio
  `config.timeout_ms` (default 5000).
- `LocalAiEngine`:
  - `translate_fragment(firma) -> Option<NativeAction>` (API richiesta);
  - `translate(firma) -> TranslationOutcome { native_action, source, took_ms }`;
  - `translate_in_background(firma) -> JoinHandle<TranslationOutcome>` (thread
    separato, la UI non blocca il gameplay);
  - `arm()` / `disarm()` (toggle), `state()`, `is_available()`.
- Stati: `Ready` (server raggiungibile), `NotReady` (server assente/non pronto),
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
L'endpoint è nella chiave `ai_locale_endpoint` (default
`http://127.0.0.1:8080/v1/chat/completions`).

## Server AI locale (OpenAI-compatible)

Il modello **non** viene caricato nell'app: gira in un processo separato che
espone `POST /v1/chat/completions`. Endpoint predefinito:
`http://127.0.0.1:8080/v1/chat/completions`.

### llama.cpp (`llama-server`)

```bash
llama-server -m smollm2-360m-instruct-q4_k_m.gguf \
  --host 127.0.0.1 --port 8080 -c 512
```

### Ollama

```bash
ollama serve
ollama pull smollm2:360m
# endpoint: http://127.0.0.1:11434/v1/chat/completions   modello: smollm2:360m
```

### LM Studio

Attivare **Local Server** (porta 1234 di default):
`http://127.0.0.1:1234/v1/chat/completions`, modello = quello caricato.

### Verifica rapida

```bash
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"smollm2-360m","messages":[{"role":"user","content":"Rispondi solo con NativeTick"}],"max_tokens":8}'
```

Su Android l'endpoint si cambia con la preferenza `ai_locale_endpoint`. Per usare
un server in esecuzione sul PC: `adb reverse tcp:8080 tcp:8080` (il `127.0.0.1`
del dispositivo inoltra alla porta del PC).

> **Android 9+**: il traffico `http://` in chiaro verso `127.0.0.1` richiede
> `usesCleartextTraffic` o una network security config dedicata (vedi
> `docs/ANDROID.md`, sezione 7).

## UI Kotlin

`starling/SettingsScreen.kt` (`SettingsActivity`), stile View programmatico come
`FriendsScreen.kt` (niente Compose):
- `Switch` **"AI Locale per Conversione Mod"** → `AiConverterBridge.setAiEnabled`;
- status testuale che descrive lo stato (`ready` / `not_ready` / `disarmed`) e
  l'endpoint del server locale;
- campo "Percorso del file .jar" + pulsante **Converti** → `convertJar`, mostra il
  riepilogo e il percorso di output.

## JNI

`mod-converter --features jni` produce `libmod_converter.so` (separata da
`bridge-jni` e `libstarling_social.so`). Pattern identico a `starling-social`:
registry di handle opachi (`Mutex<HashMap<i64, ConverterHandle>>`), macro
`guarded!` con `catch_unwind`, nessun puntatore raw attraverso JNI.

Classe Java `dev.starling.converter.AiConverterBridge`:
- `nativeCreate(endpoint, libraryPath) -> long` (AI parte disattivata);
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
# Tutti i test: logica pura, trasporto HTTP mock e mini-server TCP reale.
# Nessuna compilazione C++/llama.cpp richiesta.
CC=/usr/bin/gcc ~/.cargo/bin/cargo test -p mod-analyzer -p local-ai -p mod-converter

bash java/ai-converter/build.sh     # build Rust JNI + 18 test JNI end-to-end
```

Build Android (NDK): vedi [`docs/ANDROID.md`](ANDROID.md#7-ai-locale-server-http-locale).

Copertura:
- **mod-analyzer** (5): estrazione firme, JAR senza classi, JAR malformato,
  entry deflate, errori display.
- **local-ai** (31): prompt e parsing (`NONE`, case-insensitive, azioni valide),
  body JSON, estrazione `choices[0].message.content`, health check, trasporto
  mock (risposta valida/`NONE`/errore rete/HTTP/timeout), mini-server TCP reale
  end-to-end, porta chiusa ⇒ fallback senza panic; engine: arm/disarm, AI non
  interrogata se disattivata o not-ready, traduzione, fallback, timeout,
  background, `translate_fragment`.
- **mod-converter** (10): wire_id deterministico, AI attiva/disattivata,
  precedenza libreria, server assente, roundtrip `mapping.bin` con `bridge-u8`,
  struttura log, firme duplicate, zero codice copiato, pipeline su file.
- **JNI** (18): default disarmed, toggle, fallback server assente/non
  raggiungibile, conversione con AI spenta (`mapping.bin` magic `BU8M`, log
  "AI disattivata", nessun bytecode), chiusura.

## Stato attuale / prossimo passo

Il backend AI è un **client HTTP** verso un server OpenAI-compatible locale:
health check TCP, `POST /v1/chat/completions` con prompt SmolLM2, parsing di
`choices[0].message.content`, timeout di 5 s (2 s per la connessione) e fallback
`Ok(None)` su qualsiasi errore. Niente FFI, niente compilazione C++: il modello
vive in un processo separato. Prossimo milestone: campo endpoint nella UI
(oggi si imposta via `SharedPreferences`/`adb`), wizard di avvio del server e
download guidato del modello SmolLM2.