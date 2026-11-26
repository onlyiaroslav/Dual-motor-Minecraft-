# Asset AI locale — Doge Client

Questa cartella contiene l'eseguibile del server AI locale e il modello GGUF
usati dalla conversione mod con AI locale (Fase 7). Sono estratti automaticamente
dall'app nello storage privato al primo avvio (se `ai_auto_start_server` è attivo).

## File da inserire qui

| File | Descrizione | Dimensione indicativa |
|---|---|---|
| `llama-server` | Eseguibile compilato per `arm64-v8a` (Android NDK) | ~10–30 MB |
| `smollm2-360m-instruct-q4_k_m.gguf` | Modello SmolLM2 360M quantizzato Q4_K_M | ~250 MB |

## Come ottenere i file

### Opzione A — script automatico (consigliato)

```bash
cd /data/data/com.termux/files/home/Dual-motor-Minecraft-
bash scripts/setup-ai-local.sh
```

Lo script scarica (se possibile) o ti indica dove recuperare:
- `llama-server` compilato per Android arm64
- `smollm2-360m-instruct-q4_k_m.gguf`

### Opzione B — manuale

1. Compila `llama-server` per Android arm64 con llama.cpp (NDK r27+).
2. Scarica il modello da HuggingFace:
   ```
   https://huggingface.co/ggml-org/smolLM2-360M-Instruct-GGUF/resolve/main/smolLM2-360M-Instruct-Q4_K_M.gguf
   ```
3. Copiali entrambi in `starling/src/main/assets/ai/`.

## Comportamento dell'app

- Se entrambi i file sono presenti in `assets/ai/` e l'utente ha abilitato
  "Avvia server AI locale automaticamente", l'app li estrae in `filesDir/ai/`
  e lancia `llama-server -m modello --host 127.0.0.1 --port 8080` in background.
- Se i file mancano o il server non parte, l'AI resta disattivata con fallback
  silenzioso: **nessun crash**, la conversione mod funziona solo con la libreria
  esistente (`mapping.bin`).

## Licenza

Il modello e `llama-server` sono soggetti alle rispettive licenze upstream
(llama.cpp MIT, SmolLM2 Apache 2.0). L'app non include né distribuisce questi
file per default: vanno aggiunti dall'utente o dallo script di setup.
