package dev.starling.bridge;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bootstrap della JVM headless dentro il processo Starling (in-process).
 *
 * <p>Flusso:
 * <ol>
 *   <li>{@link ModRegistry#scan}: mapping presente → mod pronta; assente →
 *       coda di analisi OFFLINE (mai durante il gameplay);</li>
 *   <li>caricamento jar reali con {@link RealJarModHost} (quarantena per mod
 *       che falliscono);</li>
 *   <li>ring buffer condiviso + attach JNI;</li>
 *   <li>thread di tick mod (20 TPS) che produce pacchetti nel ring;</li>
 *   <li>il thread del motore Rust chiama {@code NativeTransport.nativePoll}
 *       una volta per frame e riceve le azioni nel sink.</li>
 * </ol>
 *
 * <p>La libreria {@code libbridge_jni.so} deve essere già caricata
 * ({@code System.loadLibrary("bridge_jni")}).
 */
public final class JvmBootstrap {
    private static ScheduledExecutorService ticker;
    private static RealJarModHost host;
    private static RingBuffer ring;

    private JvmBootstrap() {
    }

    /**
     * Avvio produzione (Starling): sink {@link EngineSink} che inoltra le
     * azioni al motore nativo via JNI → callback C.
     */
    public static long start(String modsDir, String lutDir, String mappingPath)
            throws Exception {
        return start(modsDir, lutDir, mappingPath, new EngineSink());
    }

    /**
     * Avvia il lato Java del bridge. Ritorna l'handle JNI da passare a
     * {@code nativePoll} dal thread del motore.
     *
     * @param modsDir     cartella mod (storage specifico app, Scoped Storage)
     * @param lutDir      directory dei mapping.bin precompilati
     * @param mappingPath mapping globale per la LUT Rust
     * @param sink        riceve gli ID azione risolti da Rust (batch/frame)
     */
    public static synchronized long start(String modsDir, String lutDir,
                                          String mappingPath,
                                          NativeTransport.NativeSink sink) throws Exception {
        ModRegistry.ScanResult scan = ModRegistry.scan(Path.of(modsDir), Path.of(lutDir));
        for (Path pending : scan.needsAnalysis) {
            // Coda di analisi offline: qui si logga soltanto, MAI analisi a runtime.
            System.out.println("[bridge] mod senza mapping, analisi offline richiesta: "
                    + pending.getFileName());
        }

        host = new RealJarModHost();
        host.load(scan.ready);
        for (RealJarModHost.LoadedMod m : host.mods()) {
            if (m.status != RealJarModHost.Status.LOADED) {
                System.out.println("[bridge] mod " + m.jar.getFileName() + " ["
                        + m.entrypoint + "] → " + m.status + ": " + m.reason);
            }
        }

        LookupTable lut = LookupTable.load(Path.of(mappingPath));
        ring = RingBuffer.create(65_536);
        long handle = NativeTransport.nativeAttach(mappingPath, ring.buffer(), sink);
        if (handle == 0) {
            throw new BridgeException("nativeAttach fallito");
        }

        U8Bridge bridge = new U8Bridge(lut, ring.asOutputStream());
        host.enableAll(bridge);
        bridge.flush();

        final RealJarModHost h = host;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bridge-mod-tick");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(() -> {
            try {
                h.tickAll(bridge);
                bridge.flush(); // una pubblicazione release per tick
            } catch (Throwable t) {
                // Fase 3: qui scatterà l'isolamento della mod colpevole.
                System.err.println("[bridge] tick mod fallito: " + t);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);

        return handle;
    }

    /** Ferma il tick e rilascia l'handle JNI. */
    public static synchronized void stop(long handle) {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
        NativeTransport.nativeDetach(handle);
        host = null;
        ring = null;
    }
}