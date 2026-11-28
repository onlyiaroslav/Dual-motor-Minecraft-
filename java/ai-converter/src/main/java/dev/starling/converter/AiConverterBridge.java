package dev.starling.converter;

/**
 * Bridge Java/Kotlin per il convertitore offline di mod con AI locale (Fase 7).
 *
 * <p>Espone il toggle "AI Locale per Conversione Mod" e la conversione di un
 * JAR in {@code mapping.bin} (dizionario di vocabolario, formato LUT bridge-u8)
 * + {@code conversion_log.json}. È un layer <b>aggiuntivo</b>: non tocca il
 * bridge a 8 bit, {@code bridge-jni} né il layer social.
 *
 * <p>La libreria nativa è {@code libmod_converter.so}, separata dalle altre.
 * Su Android va impacchettata con {@code System.loadLibrary("mod_converter")}.
 *
 * <p><b>Privacy (zero codice copiato):</b> vengono conservati solo firme di
 * metodi, ID numerici e nomi di azioni. Mai bytecode o asset, nessun upload:
 * tutto resta sul dispositivo.
 *
 * <p>Logica del toggle:
 * <ul>
 *   <li>ATTIVO: il server AI locale (SmolLM2 via HTTP, OpenAI-compatible)
 *       traduce le API sconosciute e aggiorna {@code mapping.bin};</li>
 *   <li>DISATTIVO: si usa solo la libreria esistente, le API sconosciute sono
 *       scartate e loggate come "AI disattivata";</li>
 *   <li>server non raggiungibile o timeout ⇒ fallback, come se il toggle fosse
 *       spento.</li>
 * </ul>
 *
 * <p>Uso tipico nella schermata Impostazioni:
 * <pre>
 *   AiConverterBridge conv = new AiConverterBridge(endpoint, libraryPath);
 *   String state = conv.setAiEnabled(true);   // "ready" / "not_ready"
 *   String summary = conv.convertJar(jarPath, outDir);
 *   conv.close();
 * </pre>
 */
public final class AiConverterBridge implements AutoCloseable {
    static {
        System.loadLibrary("mod_converter");
    }

    private long handle;

    /**
     * Crea un convertitore. L'AI parte disattivata (default privacy).
     *
     * @param endpoint    URL del server AI locale OpenAI-compatible
     *                    (es. {@code http://127.0.0.1:8080/v1/chat/completions});
     *                    vuoto o non-URL = endpoint predefinito
     * @param libraryPath percorso del {@code mapping.bin} esistente; vuoto = nessuna libreria
     */
    public AiConverterBridge(String endpoint, String libraryPath) {
        if (endpoint == null) {
            endpoint = "";
        }
        if (libraryPath == null) {
            libraryPath = "";
        }
        this.handle = nativeCreate(endpoint, libraryPath);
        if (this.handle == 0) {
            throw new IllegalStateException("Unable to create the converter");
        }
    }

    /**
     * Imposta il toggle "AI Locale per Conversione Mod".
     *
     * @param enabled true = arma l'AI, false = disarma (solo libreria)
     * @return lo stato risultante: {@code "ready"} / {@code "not_ready"} / {@code "disarmed"}
     */
    public synchronized String setAiEnabled(boolean enabled) {
        checkOpen();
        return nativeSetAiEnabled(handle, enabled);
    }

    /** Stato corrente del motore AI: ready / not_ready / disarmed. */
    public synchronized String aiState() {
        checkOpen();
        return nativeAiState(handle);
    }

    /**
     * Traduce una firma Java in un'azione nativa.
     *
     * @return l'action_id (0..=11) oppure -1 se nessuna azione (fallback/disattivata)
     */
    public synchronized int translateFragment(String signature) {
        checkOpen();
        return nativeTranslateFragment(handle, signature);
    }

    /**
     * Converte un JAR e scrive {@code mapping.bin} + {@code conversion_log.json}
     * nella directory {@code outDir} (creata se assente).
     *
     * @return riepilogo {@code mapped=N ai=X library=Y discarded=Z ai_ready=B}
     */
    public synchronized String convertJar(String jarPath, String outDir) {
        checkOpen();
        return nativeConvertJar(handle, jarPath, outDir);
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private void checkOpen() {
        if (handle == 0) {
            throw new IllegalStateException("AiConverterBridge already closed");
        }
    }

    // --- Native ------------------------------------------------------------

    private static native long nativeCreate(String endpoint, String libraryPath);

    private static native String nativeSetAiEnabled(long handle, boolean enabled);

    private static native String nativeAiState(long handle);

    private static native int nativeTranslateFragment(long handle, String signature);

    private static native String nativeConvertJar(long handle, String jarPath, String outDir);

    private static native void nativeFree(long handle);
}
