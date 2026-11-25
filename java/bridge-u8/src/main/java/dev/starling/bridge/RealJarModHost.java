package dev.starling.bridge;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host per jar mod REALI (Fase 1, passo 2).
 *
 * <p>Scansiona i jar, legge i manifest ({@link JarManifest}), carica gli
 * entrypoint "main" in un classloader headless e li inizializza SENZA avviare
 * il rendering di Minecraft. Ogni mod è isolata: un'eccezione (anche
 * {@code NoClassDefFoundError} da classi Minecraft mancanti) mette la mod in
 * QUARANTENA invece di far crashare l'host (base per la sandbox di Fase 3).
 *
 * <p>Limite noto e documentato: il bootstrap completo di Fabric Loader /
 * NeoForge (con le loro lifecycle e mixin) richiede che il motore fornisca le
 * classi shim di Minecraft; qui gli entrypoint che non toccano classi MC
 * girano già, gli altri vengono quarantinati con motivo. I jar originali non
 * vengono MAI decompilati o riscritti (vincolo licenze).
 */
public final class RealJarModHost implements AutoCloseable {

    public enum Status { LOADED, QUARANTINED, NO_ENTRYPOINT }

    /** Esito del caricamento di una mod (o di un suo entrypoint). */
    public static final class LoadedMod {
        public final Path jar;
        public final JarManifest manifest;
        public final String entrypoint;
        public final Status status;
        public final String reason;
        public final Object instance;

        LoadedMod(Path jar, JarManifest manifest, String entrypoint,
                  Status status, String reason, Object instance) {
            this.jar = jar;
            this.manifest = manifest;
            this.entrypoint = entrypoint;
            this.status = status;
            this.reason = reason;
            this.instance = instance;
        }
    }

    private HeadlessClassLoader loader;
    private final List<LoadedMod> mods = new ArrayList<>();
    private final List<HeadlessModHost.Mod> tickable = new ArrayList<>();

    /** Carica i jar (tipicamente {@code ModRegistry.ScanResult.ready}). */
    public List<LoadedMod> load(List<Path> jars) throws IOException {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        loader = new HeadlessClassLoader(urls, RealJarModHost.class.getClassLoader());

        for (Path jar : jars) {
            JarManifest manifest;
            try {
                manifest = JarManifest.read(jar);
            } catch (BridgeException e) {
                mods.add(new LoadedMod(jar, null, "", Status.QUARANTINED,
                        "manifest non valido: " + e.getMessage(), null));
                continue;
            }

            List<String> entrypoints = manifest.mainEntrypoints;
            if (manifest.loader == JarManifest.Loader.NEOFORGE) {
                entrypoints = JarManifest.scanAnnotatedClasses(jar,
                        "Lnet/neoforged/fml/common/Mod;");
            } else if (manifest.loader == JarManifest.Loader.FORGE
                    && entrypoints.isEmpty()) {
                entrypoints = JarManifest.scanAnnotatedClasses(jar,
                        "Lnet/minecraftforge/fml/common/Mod;");
            }

            if (entrypoints.isEmpty()) {
                mods.add(new LoadedMod(jar, manifest, "", Status.NO_ENTRYPOINT,
                        "nessun entrypoint main trovato", null));
                continue;
            }

            for (String entry : entrypoints) {
                mods.add(loadEntrypoint(jar, manifest, entry));
            }
        }
        return Collections.unmodifiableList(mods);
    }

    private LoadedMod loadEntrypoint(Path jar, JarManifest manifest, String entry) {
        try {
            Class<?> clazz = loader.loadClass(entry);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (instance instanceof HeadlessModHost.Mod) {
                // Mod bridge-native: partecipa ai tick del motore.
                tickable.add((HeadlessModHost.Mod) instance);
            } else {
                // Fabric ModInitializer (o simile): onInitialize() via reflection,
                // senza dipendenza compile-time da Fabric.
                Method init = findNoArgMethod(clazz, "onInitialize");
                if (init != null) {
                    init.invoke(instance);
                }
            }
            return new LoadedMod(jar, manifest, entry, Status.LOADED, "", instance);
        } catch (Throwable t) {
            // Quarantena: mai propagare (classi MC mancanti, static init, ecc.).
            return new LoadedMod(jar, manifest, entry, Status.QUARANTINED,
                    t.getClass().getSimpleName() + ": " + t.getMessage(), null);
        }
    }

    private static Method findNoArgMethod(Class<?> clazz, String name) {
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    public void enableAll(U8Bridge bridge) throws Exception {
        for (HeadlessModHost.Mod mod : tickable) {
            mod.onEnable(bridge);
        }
    }

    /** Chiamato dal tick del motore Rust (hook native_tick, 20 TPS). */
    public void tickAll(U8Bridge bridge) throws Exception {
        for (HeadlessModHost.Mod mod : tickable) {
            mod.onTick(bridge);
        }
    }

    public void disableAll(U8Bridge bridge) throws Exception {
        for (HeadlessModHost.Mod mod : tickable) {
            mod.onDisable(bridge);
        }
    }

    public List<LoadedMod> mods() {
        return Collections.unmodifiableList(mods);
    }

    @Override
    public void close() throws IOException {
        if (loader != null) {
            loader.close();
        }
    }
}