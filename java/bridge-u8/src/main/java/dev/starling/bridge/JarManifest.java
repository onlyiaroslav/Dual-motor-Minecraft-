package dev.starling.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Lettura dei manifest REALI dei jar mod (nessuna decompilazione: si leggono
 * solo metadati e, per NeoForge/Forge, si cerca il descrittore dell'annotazione
 * {@code @Mod} nei byte delle classi).
 *
 * <ul>
 *   <li>Fabric: {@code fabric.mod.json} → entrypoints "main" (gli entrypoint
 *       "client" sono rendering: ignorati, il rendering è del motore Rust).</li>
 *   <li>NeoForge: {@code META-INF/neoforge.mods.toml}.</li>
 *   <li>Forge legacy: {@code META-INF/mods.toml} / {@code mcmod.info}.</li>
 * </ul>
 */
public final class JarManifest {

    public enum Loader { FABRIC, NEOFORGE, FORGE, UNKNOWN }

    public final Loader loader;
    public final String id;
    public final String version;
    public final String name;
    /** Entrypoint "main" dichiarati nel manifest (Fabric). */
    public final List<String> mainEntrypoints;

    private JarManifest(Loader loader, String id, String version, String name,
                        List<String> entrypoints) {
        this.loader = loader;
        this.id = id;
        this.version = version;
        this.name = name;
        this.mainEntrypoints = entrypoints;
    }

    /** Legge il manifest di un jar reale. */
    public static JarManifest read(Path jar) throws IOException, BridgeException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry fabric = jf.getJarEntry("fabric.mod.json");
            if (fabric != null) {
                return readFabric(readEntry(jf, fabric));
            }
            JarEntry neo = jf.getJarEntry("META-INF/neoforge.mods.toml");
            if (neo != null) {
                return readModsToml(readEntry(jf, neo), Loader.NEOFORGE);
            }
            JarEntry forge = jf.getJarEntry("META-INF/mods.toml");
            if (forge != null) {
                return readModsToml(readEntry(jf, forge), Loader.FORGE);
            }
            JarEntry mcmod = jf.getJarEntry("mcmod.info");
            if (mcmod != null) {
                return readMcmodInfo(readEntry(jf, mcmod));
            }
            return new JarManifest(Loader.UNKNOWN, jar.getFileName().toString(), "", "",
                    new ArrayList<>());
        }
    }

    @SuppressWarnings("unchecked")
    private static JarManifest readFabric(String json) throws BridgeException {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) {
            throw new BridgeException("fabric.mod.json: radice non oggetto");
        }
        Map<String, Object> map = (Map<String, Object>) root;
        String id = str(map.get("id"));
        String version = str(map.get("version"));
        String name = str(map.get("name"));
        List<String> entries = new ArrayList<>();
        Object eps = map.get("entrypoints");
        if (eps instanceof Map) {
            Object main = ((Map<String, Object>) eps).get("main");
            if (main instanceof List) {
                for (Object o : (List<Object>) main) {
                    if (o instanceof String) {
                        entries.add((String) o);
                    } else if (o instanceof Map) {
                        // Forma { "value": "com.X", "adapter": "..." }
                        Object value = ((Map<String, Object>) o).get("value");
                        if (value instanceof String) {
                            entries.add((String) value);
                        }
                    }
                }
            }
        }
        return new JarManifest(Loader.FABRIC, id, version, name, entries);
    }

    /** Estrazione lineare dei campi dal TOML (subset sufficiente ai manifest). */
    private static JarManifest readModsToml(String toml, Loader loader) {
        String id = "";
        String version = "";
        String name = "";
        boolean inMods = false;
        boolean done = false;
        for (String line : toml.split("\n")) {
            String t = line.trim();
            if (t.startsWith("[[mods]]")) {
                if (done) {
                    break; // solo il primo blocco [[mods]]
                }
                inMods = true;
                continue;
            }
            if (t.startsWith("[") && !t.startsWith("[[mods]]")) {
                if (inMods) {
                    done = true;
                    inMods = false;
                }
                continue;
            }
            if (inMods) {
                if (t.startsWith("modId")) {
                    id = tomlValue(t);
                } else if (t.startsWith("version")) {
                    version = tomlValue(t);
                } else if (t.startsWith("displayName")) {
                    name = tomlValue(t);
                }
            }
        }
        return new JarManifest(loader, id, version, name, new ArrayList<>());
    }

    private static String tomlValue(String line) {
        int eq = line.indexOf('=');
        if (eq < 0) {
            return "";
        }
        String v = line.substring(eq + 1).trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static JarManifest readMcmodInfo(String json) throws BridgeException {
        Object root = MiniJson.parse(json);
        // mcmod.info può essere un array diretto o { "modlist": [...] }
        Object first = null;
        if (root instanceof List && !((List<Object>) root).isEmpty()) {
            first = ((List<Object>) root).get(0);
        } else if (root instanceof Map) {
            Object list = ((Map<String, Object>) root).get("modlist");
            if (list instanceof List && !((List<Object>) list).isEmpty()) {
                first = ((List<Object>) list).get(0);
            }
        }
        if (first instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) first;
            return new JarManifest(Loader.FORGE, str(m.get("modid")), str(m.get("version")),
                    str(m.get("name")), new ArrayList<>());
        }
        return new JarManifest(Loader.FORGE, "", "", "", new ArrayList<>());
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : (o == null ? "" : o.toString());
    }

    private static String readEntry(JarFile jf, JarEntry entry) throws IOException {
        try (InputStream in = jf.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Scopre entrypoint NeoForge/Forge cercando il descrittore dell'annotazione
     * {@code @Mod} nei byte delle classi (analisi statica, nessuna esecuzione).
     *
     * @param descriptor es. {@code Lnet/neoforged/fml/common/Mod;}
     */
    public static List<String> scanAnnotatedClasses(Path jar, String descriptor)
            throws IOException {
        List<String> found = new ArrayList<>();
        byte[] needle = descriptor.getBytes(StandardCharsets.UTF_8);
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jf.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                if (indexOf(bytes, needle) >= 0) {
                    String className = e.getName()
                            .substring(0, e.getName().length() - 6)
                            .replace('/', '.');
                    found.add(className);
                }
            }
        }
        return found;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}