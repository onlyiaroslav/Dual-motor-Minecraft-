package dev.starling.packs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Importatore texture pack (core, testabile su JVM).
 *
 * <p>Estrae lo zip scelto dall'utente nella cartella {@code resourcepacks}
 * della sandbox privata ({@code getExternalFilesDir}), generando o correggendo
 * {@code pack.mcmeta} perché Starling riconosca il pack immediatamente.
 *
 * <p>Sicurezza:
 * <ul>
 *   <li>zip-slip: entry con "..", percorsi assoluti o fuori dalla temp → rifiuto;</li>
 *   <li>zip-bomb: limiti su numero entry, dimensione entry e totale;</li>
 *   <li>pack non valido → scartato con errore, i file base NON vengono mai
 *       toccati (fix del loop di download infinito: vedi {@link PackGate}).</li>
 * </ul>
 */
public final class PackImporter {
    public static final int MAX_ENTRIES = 20_000;
    public static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;

    /** Esito dell'import. */
    public static final class Result {
        public final boolean ok;
        public final Path packDir;
        public final String error;
        public final boolean mcmetaGenerated;

        private Result(boolean ok, Path packDir, String error, boolean generated) {
            this.ok = ok;
            this.packDir = packDir;
            this.error = error;
            this.mcmetaGenerated = generated;
        }

        static Result ok(Path dir, boolean generated) {
            return new Result(true, dir, null, generated);
        }

        static Result fail(String error) {
            return new Result(false, null, error, false);
        }
    }

    /**
     * Importa uno zip. Non solleva mai: gli errori tornano nel Result
     * (il launcher mostra il messaggio e NON riavvia i download base).
     */
    public Result importZip(InputStream zipStream, Path packsDir, String name, int packFormat) {
        Path temp = null;
        try {
            Files.createDirectories(packsDir);
            temp = Files.createTempDirectory(packsDir, ".import-");

            long total = 0;
            int entries = 0;
            try (ZipInputStream zin = new ZipInputStream(zipStream)) {
                ZipEntry e;
                byte[] buf = new byte[8192];
                while ((e = zin.getNextEntry()) != null) {
                    if (++entries > MAX_ENTRIES) {
                        return Result.fail("zip scartato: troppe entry (> " + MAX_ENTRIES + ")");
                    }
                    String entryName = e.getName();
                    // Zip-slip: niente "..", percorsi assoluti o drive letter.
                    if (entryName.contains("..") || entryName.startsWith("/")
                            || entryName.startsWith("\\") || entryName.contains(":")) {
                        return Result.fail("zip scartato: entry non sicura (zip-slip): " + entryName);
                    }
                    Path target = temp.resolve(entryName).normalize();
                    if (!target.startsWith(temp)) {
                        return Result.fail("zip scartato: entry fuori dalla sandbox: " + entryName);
                    }
                    if (e.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    long written = 0;
                    try (OutputStream out = Files.newOutputStream(target)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            written += n;
                            total += n;
                            if (written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                                return Result.fail("zip scartato: dimensione eccessiva (zip-bomb)");
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
            if (entries == 0) {
                return Result.fail("zip vuoto o non valido: nessun pack importato");
            }

            // Radice del pack: supporta zip con una cartella annidata.
            Path root = findRoot(temp);
            if (root == null) {
                return Result.fail("pack non valido: né pack.mcmeta né assets/ trovati");
            }

            // Genera o correggi i metadati per il riconoscimento immediato.
            boolean generated = ensureMcmeta(root, packFormat, name);

            String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
                safe = "pack";
            }
            Path dest = packsDir.resolve(safe);
            if (Files.exists(dest)) {
                deleteRecursively(dest);
            }
            Files.move(root, dest, StandardCopyOption.ATOMIC_MOVE);
            return Result.ok(dest, generated);
        } catch (IOException | RuntimeException ex) {
            return Result.fail("import fallito: " + ex.getMessage());
        } finally {
            // Qualunque fallimento: la temp viene rimossa. I file base del
            // launcher non vengono MAI toccati da questo percorso.
            if (temp != null) {
                try {
                    deleteRecursively(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static Path findRoot(Path temp) throws IOException {
        if (isPackRoot(temp)) {
            return temp;
        }
        Path single = null;
        try (var children = Files.list(temp)) {
            for (Path c : (Iterable<Path>) children::iterator) {
                if (Files.isDirectory(c)) {
                    if (single != null) {
                        return null; // più cartelle: non è un pack root unico
                    }
                    single = c;
                }
            }
        }
        return single != null && isPackRoot(single) ? single : null;
    }

    private static boolean isPackRoot(Path p) {
        return Files.exists(p.resolve("pack.mcmeta")) || Files.isDirectory(p.resolve("assets"));
    }

    /** Genera pack.mcmeta se assente, lo corregge se privo di pack_format. */
    private static boolean ensureMcmeta(Path root, int packFormat, String name) throws IOException {
        Path mcmeta = root.resolve("pack.mcmeta");
        String desc = name.replace("\"", "'");
        String fresh = "{\"pack\":{\"pack_format\":" + packFormat
                + ",\"description\":\"" + desc + " (importato da Starling)\"}}";
        if (!Files.exists(mcmeta)) {
            Files.writeString(mcmeta, fresh, StandardCharsets.UTF_8);
            return true;
        }
        String existing = Files.readString(mcmeta, StandardCharsets.UTF_8);
        if (!existing.contains("pack_format")) {
            Files.writeString(mcmeta, fresh, StandardCharsets.UTF_8);
            return true;
        }
        return false;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}