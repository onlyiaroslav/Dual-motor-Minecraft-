package dev.starling.bridge;

import java.nio.ByteBuffer;

/**
 * Trasporto in-process JNI verso il crate Rust {@code bridge-jni}
 * ({@code libbridge_jni.so}). Niente socket, niente rete.
 *
 * <p>La libreria va caricata prima dell'uso:
 * <ul>
 *   <li>Starling/Android: {@code System.loadLibrary("bridge_jni")}
 *       (jniLibs/arm64-v8a/libbridge_jni.so);</li>
 *   <li>test desktop: {@code System.load("/percorso/libbridge_jni.so")}.</li>
 * </ul>
 *
 * <p>Flusso consigliato (ring): Java produce con {@link RingBuffer}, il thread
 * del motore chiama {@link #nativePoll} una volta per frame; le azioni risolte
 * dalla LUT Rust arrivano al {@link NativeSink} in batch (una chiamata JNI
 * per frame, non per pacchetto).
 */
public final class NativeTransport {

    /** Riceve gli ID azione risolti dalla LUT Rust (batch per poll). */
    public interface NativeSink {
        void onActions(int[] actionIds);
    }

    private NativeTransport() {
    }

    /** Carica libbridge_jni.so dal java.library.path (Android: jniLibs). */
    public static void loadLibrary() {
        System.loadLibrary("bridge_jni");
    }

    /**
     * Aggancia mapping + ring + sink. Ritorna l'handle opaco (0 = errore,
     * con eccezione Java sollevata). Il ByteBuffer deve essere DIRECT e
     * restare referenziato da Java per tutta la vita dell'handle.
     */
    public static native long nativeAttach(String mappingPath, ByteBuffer ring, NativeSink sink);

    /** Consuma il ring e dispatcha. Ritorna i byte consumati, -1 su errore. */
    public static native int nativePoll(long handle) throws BridgeException;

    /** Dispatch sincrono senza ring. Ritorna i byte dispatchati, -1 su errore. */
    public static native int nativeDispatch(long handle, byte[] data, int len)
            throws BridgeException;

    /** Rilascia handle, LUT e global ref. Dopo detach l'handle non è più usabile. */
    public static native void nativeDetach(long handle);
}