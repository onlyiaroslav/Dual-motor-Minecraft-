package dev.starling.social;

/**
 * Bridge Java/Kotlin per il layer social di Starling (Fase 6).
 *
 * <p>Espone la lista amici persistente, la generazione dei codici monouso e
 * il mini-server TCP di autenticazione offline. È un layer <b>aggiuntivo</b>:
 * non tocca l'autenticazione Microsoft né il bridge a 8 bit.
 *
 * <p>La libreria nativa è {@code libstarling_social.so}, separata da
 * {@code bridge-jni}. Su Android va impacchettata con
 * {@code System.loadLibrary("starling_social")} (stesso nome).
 *
 * <p>Formato di {@link #listFriends()}: ogni elemento è
 * {@code nickname|uuid|online} con {@code online} = {@code "1"}/{@code "0"}.
 *
 * <p>Uso tipico nella schermata "Amici":
 * <pre>
 *   SocialBridge social = new SocialBridge(friendsFile.getAbsolutePath());
 *   social.addFriend("Steve_01");
 *   String code = social.generateInvite(hostIp, 25565); // valido 5 minuti
 *   int port = social.startAuthServer(25566);
 *   // ... mostrare code nella UI ...
 *   social.close();
 * </pre>
 */
public final class SocialBridge implements AutoCloseable {
    static {
        System.loadLibrary("starling_social");
    }

    private long handle;

    /**
     * Apre (o crea) la lista amici sul file indicato.
     *
     * @param friendsFile percorso del file locale; stringa vuota = lista in memoria
     */
    public SocialBridge(String friendsFile) {
        if (friendsFile == null) {
            friendsFile = "";
        }
        this.handle = nativeCreate(friendsFile);
        if (this.handle == 0) {
            throw new IllegalStateException("impossibile aprire la lista amici");
        }
    }

    /** Aggiunge un amico per nickname. Ritorna l'UUID locale generato. */
    public synchronized String addFriend(String nickname) {
        checkOpen();
        return nativeAddFriend(handle, nickname);
    }

    /** Lista amici come {@code nickname|uuid|online}. */
    public synchronized String[] listFriends() {
        checkOpen();
        return nativeListFriends(handle);
    }

    /** Rimuove un amico. {@code true} se esisteva. */
    public synchronized boolean removeFriend(String nickname) {
        checkOpen();
        return nativeRemoveFriend(handle, nickname);
    }

    /**
     * Genera un codice monouso (8 caratteri, TTL 5 minuti) per il mondo host.
     *
     * @param host IP o host del mondo (es. "192.168.1.10")
     * @param port porta del mondo
     */
    public synchronized String generateInvite(String host, int port) {
        checkOpen();
        return nativeGenerateInvite(handle, host, port);
    }

    /**
     * Avvia il mini-server auth (porta dedicata, separata dal bridge a 8 bit).
     *
     * @param port porta di ascolto; 0 = effimera
     * @return la porta effettiva
     */
    public synchronized int startAuthServer(int port) {
        checkOpen();
        int actual = nativeStartAuthServer(handle, port);
        if (actual <= 0) {
            throw new IllegalStateException("avvio server auth fallito");
        }
        return actual;
    }

    /** Porta effettiva del server auth, oppure 0 se fermo. */
    public synchronized int authPort() {
        checkOpen();
        return nativeAuthPort(handle);
    }

    /** Ferma il server auth (le connessioni attive scadono al timeout). */
    public synchronized void stopAuthServer() {
        checkOpen();
        nativeStopAuthServer(handle);
    }

    /** TTL dei codici in secondi (300 = 5 minuti). */
    public static int tokenTtlSeconds() {
        return nativeTokenTtlSeconds();
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
            throw new IllegalStateException("SocialBridge già chiuso");
        }
    }

    // --- Native ------------------------------------------------------------

    private static native long nativeCreate(String friendsFile);

    private static native String nativeAddFriend(long handle, String nickname);

    private static native String[] nativeListFriends(long handle);

    private static native boolean nativeRemoveFriend(long handle, String nickname);

    private static native String nativeGenerateInvite(long handle, String host, int port);

    private static native int nativeStartAuthServer(long handle, int port);

    private static native void nativeStopAuthServer(long handle);

    private static native int nativeAuthPort(long handle);

    private static native int nativeTokenTtlSeconds();

    private static native void nativeFree(long handle);
}
