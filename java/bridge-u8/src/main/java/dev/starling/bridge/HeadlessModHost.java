package dev.starling.bridge;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Host headless per mod Java: NESSUN rendering, NESSUNA GUI.
 *
 * <p>Il motore Rust (Starling/Horizon) gestisce mondo, fisica e rendering;
 * qui gira solo la logica di gioco delle mod. Un classloader con blocklist
 * impedisce di caricare classi di rendering ({@code org.lwjgl}, {@code java.awt},
 * {@code com.mojang.blaze3d}, …): se una mod tenta il rendering, fallisce in
 * modo controllato invece di corrompere lo stato.
 *
 * <p>NOTA: questo host carica mod scritte contro l'API {@link Mod} (primo
 * passo della Fase 1). Il caricamento dei jar Fabric/NeoForge ORIGINALI
 * avverrà incorporando i rispettivi loader nello stesso processo ( JNI,
 * come la PR #10216): i jar non vengono mai decompilati o riscritti.
 */
public final class HeadlessModHost implements AutoCloseable {

    /** Contratto minimo di una mod bridge-native (logica di gioco, no render). */
    public interface Mod {
        default void onEnable(U8Bridge bridge) throws Exception {
        }

        default void onTick(U8Bridge bridge) throws Exception {
        }

        default void onDisable(U8Bridge bridge) throws Exception {
        }
    }

    static {
        // Headless globale: nessuna inizializzazione di toolkit grafici.
        System.setProperty("java.awt.headless", "true");
    }

    private final HeadlessClassLoader loader;
    private final List<Mod> mods = new ArrayList<>();

    /** Crea l'host con jar (o directory di classi) delle mod sul classpath. */
    public HeadlessModHost(Path... jarsOrDirs) throws IOException {
        URL[] urls = new URL[jarsOrDirs.length];
        for (int i = 0; i < jarsOrDirs.length; i++) {
            urls[i] = jarsOrDirs[i].toUri().toURL();
        }
        // Parent = classloader dell'app: le classi API del bridge sono condivise.
        // La blocklist headless è applicata PRIMA della delegazione al parent.
        loader = new HeadlessClassLoader(urls, HeadlessModHost.class.getClassLoader());
    }

    /** Carica e registra una mod per nome classe (deve implementare {@link Mod}). */
    public Mod loadMod(String className) throws BridgeException {
        try {
            Class<?> clazz = loader.loadClass(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Mod)) {
                throw new BridgeException(className + " non implementa HeadlessModHost.Mod");
            }
            Mod mod = (Mod) instance;
            mods.add(mod);
            return mod;
        } catch (ClassNotFoundException e) {
            throw new BridgeException("classe mod non trovata: " + className, e);
        } catch (ReflectiveOperationException e) {
            throw new BridgeException("istanziazione mod fallita: " + className, e);
        }
    }

    /** Verifica se una classe è caricabile (test della blocklist headless). */
    public Class<?> probeClass(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    public void enableAll(U8Bridge bridge) throws Exception {
        for (Mod mod : mods) {
            mod.onEnable(bridge);
        }
    }

    /** Chiamato dal tick del motore Rust (hook native_tick). */
    public void tickAll(U8Bridge bridge) throws Exception {
        for (Mod mod : mods) {
            mod.onTick(bridge);
        }
    }

    public void disableAll(U8Bridge bridge) throws Exception {
        for (Mod mod : mods) {
            mod.onDisable(bridge);
        }
    }

    public int modCount() {
        return mods.size();
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}