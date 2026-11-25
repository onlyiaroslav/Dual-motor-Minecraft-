package dev.starling.bridge;

/**
 * Sink che inoltra le azioni risolte dalla LUT Rust al motore nativo.
 *
 * <p>PUNTO CHIAVE ANDROID: ART e la JVM OpenJDK embedded sono due runtime
 * distinti nello stesso processo; gli oggetti NON attraversano il confine.
 * Questo sink vive nella JVM embedded (creato da {@link JvmBootstrap}) e la
 * sua native method è risolta nella {@code libbridge_jni.so} caricata nella
 * JVM embedded: da lì il crate Rust inoltra gli ID al motore via callback C.
 *
 * <p>NON passare mai un'implementazione Kotlin/ART come sink a JNI_CreateJavaVM.
 */
public final class EngineSink implements NativeTransport.NativeSink {
    @Override
    public void onActions(int[] actionIds) {
        nativeOnActions(actionIds);
    }

    /** Implementata in starling/native/starling_jvm_host.cpp (callback C verso il motore). */
    private static native void nativeOnActions(int[] actionIds);
}