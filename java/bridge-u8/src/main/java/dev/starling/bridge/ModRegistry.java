package dev.starling.bridge;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gestione dell'avvio (Fase 1): scansiona la cartella mod e decide, per ogni
 * jar, se il mapping è già nella LUT locale.
 *
 * <ul>
 *   <li>Mapping presente ({@code <nome>.mapping.bin} in {@code lutDir}) →
 *       la mod è pronta e viene caricata.</li>
 *   <li>Mapping assente → la mod va in coda di analisi OFFLINE (mai durante
 *       il gameplay); al prossimo avvio sarà nella LUT.</li>
 * </ul>
 *
 * <p>Su Android 15 entrambe le directory devono stare nello storage specifico
 * dell'app (Scoped Storage): nessun permesso runtime richiesto.
 */
public final class ModRegistry {

    /** Esito della scansione iniziale. */
    public static final class ScanResult {
        /** Mod con mapping esistente: caricabili subito. */
        public final List<Path> ready;
        /** Mod nuove: da analizzare offline prima del prossimo avvio. */
        public final List<Path> needsAnalysis;

        ScanResult(List<Path> ready, List<Path> needsAnalysis) {
            this.ready = Collections.unmodifiableList(ready);
            this.needsAnalysis = Collections.unmodifiableList(needsAnalysis);
        }
    }

    private ModRegistry() {
    }

    /** Scansiona {@code modsDir} (*.jar) cercando i mapping in {@code lutDir}. */
    public static ScanResult scan(Path modsDir, Path lutDir) throws IOException {
        List<Path> ready = new ArrayList<>();
        List<Path> pending = new ArrayList<>();
        if (Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path jar : jars) {
                    String name = jar.getFileName().toString();
                    String base = name.substring(0, name.length() - 4);
                    Path mapping = lutDir.resolve(base + ".mapping.bin");
                    if (Files.isRegularFile(mapping)) {
                        ready.add(jar);
                    } else {
                        pending.add(jar);
                    }
                }
            }
        }
        Collections.sort(ready);
        Collections.sort(pending);
        return new ScanResult(ready, pending);
    }
}