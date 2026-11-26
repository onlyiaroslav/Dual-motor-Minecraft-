#!/usr/bin/env bash
# =============================================================================
# setup-ai-local.sh — Scarica e posiziona i file per l'AI locale di Doge Client
#
# Scarica (se possibile) o guida al posizionamento di:
#   - llama-server (eseguibile arm64-v8a)
#   - smollm2-360m-instruct-q4_k_m.gguf (~250 MB)
#
# I file finiscono in starling/src/main/assets/ai/ e vengono estratti
# automaticamente dall'app al primo avvio.
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_DIR="$ROOT_DIR/starling/src/main/assets/ai"
MODEL_URL="https://huggingface.co/ggml-org/smolLM2-360M-Instruct-GGUF/resolve/main/smolLM2-360M-Instruct-Q4_K_M.gguf"

mkdir -p "$ASSET_DIR"

echo "============================================================"
echo "  Setup AI locale — Doge Client"
echo "============================================================"
echo ""

# --- 1. Modello GGUF ---------------------------------------------------------
MODEL_DEST="$ASSET_DIR/smolLM2-360M-Instruct-Q4_K_M.gguf"

if [[ -f "$MODEL_DEST" ]]; then
    echo "[OK] Modello già presente: $MODEL_DEST"
else
    echo "[*] Scaricamento modello SmolLM2 360M Q4_K_M (~250 MB)…"
    if command -v curl &>/dev/null; then
        if curl -L --fail --progress-bar -o "$MODEL_DEST" "$MODEL_URL"; then
            echo "[OK] Modello scaricato: $MODEL_DEST"
        else
            echo "[WARN] Download fallito. Scarica manualmente:"
            echo "       $MODEL_URL"
            echo "       e mettilo in: $ASSET_DIR/"
        fi
    elif command -v wget &>/dev/null; then
        if wget -q --show-progress -O "$MODEL_DEST" "$MODEL_URL"; then
            echo "[OK] Modello scaricato: $MODEL_DEST"
        else
            echo "[WARN] Download fallito. Scarica manualmente:"
            echo "       $MODEL_URL"
            echo "       e mettilo in: $ASSET_DIR/"
        fi
    else
        echo "[WARN] Nessun curl/wget disponibile. Scarica manualmente:"
        echo "       $MODEL_URL"
        echo "       e mettilo in: $ASSET_DIR/"
    fi
fi

# --- 2. Eseguibile llama-server --------------------------------------------
SERVER_DEST="$ASSET_DIR/llama-server"

if [[ -f "$SERVER_DEST" ]]; then
    echo "[OK] Eseguibile già presente: $SERVER_DEST"
else
    echo ""
    echo "[!] Eseguibile llama-server NON trovato."
    echo "    Compilalo tu per Android arm64-v8a (NDK r27+) oppure recuperalo"
    "    da una build llama.cpp Android."
    echo ""
    echo "    Esempio compilazione (host Linux):"
    echo "      git clone https://github.com/ggerganov/llama.cpp"
    echo "      cd llama.cpp && cmake -B build -G Ninja -DLLAMA_NATIVE=OFF"
    echo "      cmake --build build --target llama-server"
    echo "    Poi copia build/llama-server in: $ASSET_DIR/"
    echo ""
    echo "    Oppure, se hai adb e un dispositivo Android con llama-server già"
    "    installato, puoi fare pull del binario."
fi

# --- 3. Riepilogo ------------------------------------------------------------
echo ""
echo "============================================================"
echo "  Stato asset AI:"
if [[ -f "$SERVER_DEST" ]]; then
    echo "  [OK] llama-server: $SERVER_DEST"
else
    echo "  [MANCANTE] llama-server → $SERVER_DEST"
fi
if [[ -f "$MODEL_DEST" ]]; then
    echo "  [OK] Modello: $MODEL_DEST"
else
    echo "  [MANCANTE] Modello → $MODEL_DEST"
fi
echo "============================================================"
echo ""
echo "Se entrambi i file sono presenti e 'Avvia server AI locale"
echo "automaticamente' è attivo nelle Impostazioni, l'app lancerà"
echo "il server su 127.0.0.1:8080 senza alcuna configurazione utente."
echo ""
