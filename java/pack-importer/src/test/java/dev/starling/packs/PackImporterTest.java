package dev.starling.packs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Test dell'importatore pack e del gate anti-loop (zero dipendenze). */
public final class PackImporterTest {
    private static int passed;
    private static int failed;
    private static final PackImporter IMPORTER = new PackImporter();

    public static void main(String[] args) throws Exception {
        testPackValido();
        testMcmetaGenerato();
        testMcmetaCorretto();
        testRootAnnidata();
        testZipSlip();
        testZipNonValido();
        testZipVuoto();
        testTroppeEntry();
        testNomeSanificato();
        testGateAntiLoop();
        System.out.printf("%nTest PackImporter: %d passati, %d falliti%n", passed, failed);
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

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return buf.toByteArray();
    }

    private static Map<String, byte[]> packEntries(boolean withMcmeta) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        if (withMcmeta) {
            m.put("pack.mcmeta",
                    "{\"pack\":{\"pack_format\":34,\"description\":\"test\"}}"
                            .getBytes(StandardCharsets.UTF_8));
        }
        m.put("assets/minecraft/textures/block/stone.png", new byte[]{1, 2, 3});
        return m;
    }

    private static Path freshPacksDir() throws IOException {
        return Files.createTempDirectory("starling-packs");
    }

    private static void testPackValido() throws Exception {
        System.out.println("testPackValido");
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(packEntries(true))), dir, "TestPack", 34);
        check(r.ok, "pack valido importato");
        check(Files.exists(dir.resolve("TestPack/pack.mcmeta")), "pack.mcmeta presente");
        check(Files.exists(dir.resolve("TestPack/assets/minecraft/textures/block/stone.png")),
                "texture estratta");
        check(!r.mcmetaGenerated, "mcmeta esistente non rigenerato");
    }

    private static void testMcmetaGenerato() throws Exception {
        System.out.println("testMcmetaGenerato");
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(packEntries(false))), dir, "NoMeta", 34);
        check(r.ok && r.mcmetaGenerated, "pack senza mcmeta: importato con mcmeta generato");
        String meta = Files.readString(dir.resolve("NoMeta/pack.mcmeta"));
        check(meta.contains("\"pack_format\":34"), "pack_format corretto per il launcher");
    }

    private static void testMcmetaCorretto() throws Exception {
        System.out.println("testMcmetaCorretto");
        Map<String, byte[]> m = packEntries(false);
        m.put("pack.mcmeta", "{\"pack\":{\"description\":\"rotto\"}}"
                .getBytes(StandardCharsets.UTF_8));
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(m)), dir, "Broken", 34);
        check(r.ok && r.mcmetaGenerated, "mcmeta senza pack_format corretto");
        check(Files.readString(dir.resolve("Broken/pack.mcmeta")).contains("pack_format"),
                "pack_format aggiunto");
    }

    private static void testRootAnnidata() throws Exception {
        System.out.println("testRootAnnidata");
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("MyPack/pack.mcmeta",
                "{\"pack\":{\"pack_format\":34}}".getBytes(StandardCharsets.UTF_8));
        m.put("MyPack/assets/minecraft/textures/block/dirt.png", new byte[]{9});
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(m)), dir, "Nested", 34);
        check(r.ok, "zip con cartella annidata importato");
        check(Files.exists(dir.resolve("Nested/pack.mcmeta")),
                "radice appiattita (riconoscimento immediato)");
    }

    private static void testZipSlip() throws Exception {
        System.out.println("testZipSlip");
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("../evil.txt", "pwned".getBytes(StandardCharsets.UTF_8));
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(m)), dir, "Evil", 34);
        check(!r.ok, "zip-slip rifiutato: " + r.error);
        check(!Files.exists(dir.getParent().resolve("evil.txt")),
                "nessun file scritto fuori dalla sandbox");
        try (var children = Files.list(dir)) {
            check(children.findAny().isEmpty(), "temp ripulita dopo il rifiuto");
        }
    }

    private static void testZipNonValido() throws Exception {
        System.out.println("testZipNonValido");
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream("non sono uno zip".getBytes(StandardCharsets.UTF_8)),
                dir, "Garbage", 34);
        check(!r.ok && r.error != null, "zip non valido scartato con messaggio: " + r.error);
    }

    private static void testZipVuoto() throws Exception {
        System.out.println("testZipVuoto");
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(new LinkedHashMap<>())), dir, "Empty", 34);
        check(!r.ok, "zip vuoto scartato");
    }

    private static void testTroppeEntry() throws Exception {
        System.out.println("testTroppeEntry");
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i <= PackImporter.MAX_ENTRIES; i++) {
            m.put("f" + i + ".txt", new byte[]{0});
        }
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(m)), dir, "Bomb", 34);
        check(!r.ok && r.error.contains("troppe entry"), "zip-bomb (entry) rifiutata");
    }

    private static void testNomeSanificato() throws Exception {
        System.out.println("testNomeSanificato");
        Path dir = freshPacksDir();
        PackImporter.Result r = IMPORTER.importZip(
                new ByteArrayInputStream(zip(packEntries(true))), dir, "../../evil", 34);
        check(r.ok, "nome con percorsi ostili importato in sicurezza");
        check(r.packDir.startsWith(dir), "destinazione dentro packsDir");
        check(r.packDir.getFileName().toString().equals(".._.._evil"),
                "nome sanitificato: " + r.packDir.getFileName());
    }

    private static void testGateAntiLoop() {
        System.out.println("testGateAntiLoop");
        PackGate gate = new PackGate();
        // Pack esterno invalido → NESSUN download base (fix del loop infinito).
        PackGate.BaseAction a = gate.onExternalPackInvalid("pack non valido");
        check(a == PackGate.BaseAction.NONE, "pack invalido → nessun download");
        check(gate.externalRejected(), "stato di rifiuto registrato");
        check(gate.lastRejectReason().equals("pack non valido"), "motivo per il messaggio utente");
        // Anche dopo più rifiuti, il gate non triggera mai download.
        gate.onExternalPackInvalid("altro pack rotto");
        check(gate.checkBaseFiles(false) == PackGate.BaseAction.NONE,
                "base presenti → nessun download (nessun loop)");
        // Download SOLO se i file base mancano davvero.
        check(gate.checkBaseFiles(true) == PackGate.BaseAction.DOWNLOAD,
                "base mancanti → download legittimo");
        // Pack valido resetta lo stato.
        gate.onExternalPackValid();
        check(!gate.externalRejected(), "pack valido resetta il rifiuto");
    }
}