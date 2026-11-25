package dev.starling.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Test del caricamento jar REALI: manifest Fabric/NeoForge, entrypoint,
 * quarantena delle mod che falliscono, integrazione con ModRegistry.
 */
public final class RealJarTest {
    private static int passed;
    private static int failed;
    private static Path classesDir;

    public static void main(String[] args) throws Exception {
        classesDir = Path.of(
                RealJarTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        testMiniJson();
        testJarFabricReale();
        testQuarantena();
        testNeoForgeScan();
        testRegistryIntegrata();
        System.out.printf("%nTest RealJar: %d passati, %d falliti%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean cond, String msg) {
        if (cond) {
            passed++;
            System.out.println("  ok: " + msg);
        } else {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    private static Path buildJar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = Files.createTempDirectory("bridge-jars").resolve(name);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] sampleModClass() throws IOException {
        return Files.readAllBytes(
                classesDir.resolve("dev/starling/bridge/testmod/SampleMod.class"));
    }

    private static Map<String, byte[]> fabricJarEntries(String entrypoint) {
        String json = "{\"schemaVersion\":1,\"id\":\"sample-mod\",\"version\":\"1.0.0\","
                + "\"name\":\"Sample Mod\",\"entrypoints\":{\"main\":[\"" + entrypoint
                + "\"],\"client\":[\"rendering.Ignored\"]}}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static List<MappingFile.Entry> lutEntries() {
        List<MappingFile.Entry> entries = new ArrayList<>();
        entries.add(new MappingFile.Entry(0x0002, NativeAction.NATIVE_HUD));
        entries.add(new MappingFile.Entry(0x1234, NativeAction.PLAY_SOUND));
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static void testMiniJson() throws Exception {
        System.out.println("testMiniJson");
        Object root = MiniJson.parse(
                "{\"a\":[1,2.5,true,null,\"x\\ny\"],\"b\":{\"c\":\"d\"},\"u\":\"\\u0041\"}");
        Map<String, Object> map = (Map<String, Object>) root;
        List<Object> a = (List<Object>) map.get("a");
        check(a.size() == 5 && (Double) a.get(0) == 1.0 && (Double) a.get(1) == 2.5
                && a.get(2) == Boolean.TRUE && a.get(3) == null && a.get(4).equals("x\ny"),
                "array con numeri, booleani, null, escape");
        check(((Map<String, Object>) map.get("b")).get("c").equals("d"), "oggetti annidati");
        check(map.get("u").equals("A"), "escape unicode");
        try {
            MiniJson.parse("{\"a\":}");
            check(false, "JSON malformato deve fallire");
        } catch (BridgeException e) {
            check(true, "JSON malformato rifiutato");
        }
    }

    private static void testJarFabricReale() throws Exception {
        System.out.println("testJarFabricReale");
        Map<String, byte[]> entries =
                fabricJarEntries("dev.starling.bridge.testmod.SampleMod");
        entries.put("dev/starling/bridge/testmod/SampleMod.class", sampleModClass());
        Path jar = buildJar("sample.jar", entries);

        JarManifest manifest = JarManifest.read(jar);
        check(manifest.loader == JarManifest.Loader.FABRIC, "loader FABRIC rilevato");
        check(manifest.id.equals("sample-mod"), "id dal manifest reale");
        check(manifest.mainEntrypoints.equals(
                        Arrays.asList("dev.starling.bridge.testmod.SampleMod")),
                "entrypoint main (client ignorato: è rendering)");

        try (RealJarModHost host = new RealJarModHost()) {
            List<RealJarModHost.LoadedMod> mods = host.load(Arrays.asList(jar));
            check(mods.size() == 1 && mods.get(0).status == RealJarModHost.Status.LOADED,
                    "jar reale caricato: LOADED");

            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            U8Bridge bridge = new U8Bridge(new LookupTable(lutEntries()), capture);
            host.enableAll(bridge);
            host.tickAll(bridge);
            bridge.flush();
            byte[] atteso = {0x02, 0x00, 0x34, 0x12};
            check(Arrays.equals(capture.toByteArray(), atteso),
                    "enable+tick del jar reale → byte corretti sul wire");
        }
    }

    private static void testQuarantena() throws Exception {
        System.out.println("testQuarantena");
        Path bad = buildJar("bad.jar",
                fabricJarEntries("does.not.Exist"));
        Map<String, byte[]> goodEntries =
                fabricJarEntries("dev.starling.bridge.testmod.SampleMod");
        goodEntries.put("dev/starling/bridge/testmod/SampleMod.class", sampleModClass());
        Path good = buildJar("good.jar", goodEntries);

        try (RealJarModHost host = new RealJarModHost()) {
            List<RealJarModHost.LoadedMod> mods = host.load(Arrays.asList(bad, good));
            check(mods.size() == 2, "due mod processate");
            check(mods.get(0).status == RealJarModHost.Status.QUARANTINED,
                    "mod con entrypoint inesistente → QUARANTINED");
            check(mods.get(0).reason != null && !mods.get(0).reason.isEmpty(),
                    "motivo della quarantena registrato: " + mods.get(0).reason);
            check(mods.get(1).status == RealJarModHost.Status.LOADED,
                    "la mod buona dopo quella fallita è comunque LOADED (host sopravvive)");
        }
    }

    private static void testNeoForgeScan() throws Exception {
        System.out.println("testNeoForgeScan");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n"
                + "[[mods]]\nmodId=\"neotest\"\nversion=\"1.0\"\ndisplayName=\"Neo Test\"\n";
        // Classe fittizia che contiene il descrittore dell'annotazione @Mod:
        // lo scanner statico la trova; il caricamento fallirà (byte non validi)
        // e la mod andrà in quarantena, come previsto.
        byte[] fakeClass = ("CAFEBABE-fake-class Lnet/neoforged/fml/common/Mod; end")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/FakeMod.class", fakeClass);
        Path jar = buildJar("neo.jar", entries);

        JarManifest manifest = JarManifest.read(jar);
        check(manifest.loader == JarManifest.Loader.NEOFORGE, "loader NEOFORGE rilevato");
        check(manifest.id.equals("neotest"), "modId dal TOML reale");

        List<String> found = JarManifest.scanAnnotatedClasses(jar,
                "Lnet/neoforged/fml/common/Mod;");
        check(found.equals(Arrays.asList("com.example.FakeMod")),
                "entrypoint @Mod scoperto per scansione statica dei byte");

        try (RealJarModHost host = new RealJarModHost()) {
            List<RealJarModHost.LoadedMod> mods = host.load(Arrays.asList(jar));
            check(mods.size() == 1
                            && mods.get(0).entrypoint.equals("com.example.FakeMod")
                            && mods.get(0).status == RealJarModHost.Status.QUARANTINED,
                    "classe non valida → quarantena controllata, nessun crash");
        }
    }

    private static void testRegistryIntegrata() throws Exception {
        System.out.println("testRegistryIntegrata");
        Path modsDir = Files.createTempDirectory("bridge-mods");
        Path lutDir = Files.createTempDirectory("bridge-lut");

        Map<String, byte[]> entries =
                fabricJarEntries("dev.starling.bridge.testmod.SampleMod");
        entries.put("dev/starling/bridge/testmod/SampleMod.class", sampleModClass());
        Path sample = buildJar("sample.jar", entries);
        Files.copy(sample, modsDir.resolve("sample.jar"));
        Files.createFile(modsDir.resolve("nuova.jar")); // senza mapping
        MappingFile.write(Files.newOutputStream(lutDir.resolve("sample.mapping.bin")),
                lutEntries());

        ModRegistry.ScanResult scan = ModRegistry.scan(modsDir, lutDir);
        check(scan.ready.size() == 1
                        && scan.ready.get(0).getFileName().toString().equals("sample.jar"),
                "sample.jar pronta (mapping esistente)");
        check(scan.needsAnalysis.size() == 1
                        && scan.needsAnalysis.get(0).getFileName().toString().equals("nuova.jar"),
                "nuova.jar in coda di analisi offline");

        try (RealJarModHost host = new RealJarModHost()) {
            List<RealJarModHost.LoadedMod> mods = host.load(scan.ready);
            check(mods.size() == 1 && mods.get(0).status == RealJarModHost.Status.LOADED,
                    "ModRegistry.scan → RealJarModHost: flusso di avvio completo");
        }
    }
}