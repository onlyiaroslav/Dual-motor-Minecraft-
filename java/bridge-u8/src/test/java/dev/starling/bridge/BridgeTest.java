package dev.starling.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test auto-verificanti (zero dipendenze, niente JUnit).
 * Esegui con: java -cp build/classes dev.starling.bridge.BridgeTest
 */
public final class BridgeTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testEncoding2Byte();
        testOrdineStreamMultiplo();
        testRoundtripMapping();
        testMagicNonValido();
        testMappingTroncato();
        testAzioneNonMappata();
        testWireDuplicato();
        testLatenzaSingoloPacchetto();
        testBudget300Pacchetti();
        testHostHeadless();
        testBlocklistRendering();
        testRegistryScan();

        System.out.printf("%nTest Java: %d passati, %d falliti%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // --- helper -----------------------------------------------------------

    private static void check(boolean cond, String msg) {
        if (cond) {
            passed++;
            System.out.println("  ok: " + msg);
        } else {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    private static List<MappingFile.Entry> sampleEntries() {
        List<MappingFile.Entry> entries = new ArrayList<>();
        entries.add(new MappingFile.Entry(0x0001, NativeAction.NATIVE_TICK));
        entries.add(new MappingFile.Entry(0x0002, NativeAction.NATIVE_HUD));
        entries.add(new MappingFile.Entry(0x1234, NativeAction.PLAY_SOUND));
        entries.add(new MappingFile.Entry(0xBEEF, NativeAction.SET_BLOCK));
        return entries;
    }

    private static U8Bridge bridgeSuBuffer(ByteArrayOutputStream capture)
            throws BridgeException {
        return new U8Bridge(new LookupTable(sampleEntries()), capture);
    }

    // --- test -------------------------------------------------------------

    /** REQUISITO: un'azione → pacchetto di 2 byte little-endian esatto. */
    private static void testEncoding2Byte() throws Exception {
        System.out.println("testEncoding2Byte");
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try (U8Bridge bridge = bridgeSuBuffer(capture)) {
            bridge.send(NativeAction.PLAY_SOUND); // wire 0x1234
        }
        byte[] atteso = {0x34, 0x12};
        check(Arrays.equals(capture.toByteArray(), atteso),
                "PLAY_SOUND → [0x34, 0x12] (identico al test Rust)");
    }

    private static void testOrdineStreamMultiplo() throws Exception {
        System.out.println("testOrdineStreamMultiplo");
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try (U8Bridge bridge = bridgeSuBuffer(capture)) {
            bridge.send(NativeAction.NATIVE_TICK);
            bridge.send(NativeAction.NATIVE_HUD);
            bridge.send(NativeAction.PLAY_SOUND);
            bridge.send(NativeAction.SET_BLOCK);
            check(bridge.pendingBytes() == 8, "8 byte accumulati prima del flush");
        }
        byte[] atteso = {0x01, 0x00, 0x02, 0x00, 0x34, 0x12, (byte) 0xEF, (byte) 0xBE};
        check(Arrays.equals(capture.toByteArray(), atteso), "ordine pacchetti corretto");
    }

    private static void testRoundtripMapping() throws Exception {
        System.out.println("testRoundtripMapping");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        MappingFile.write(buf, sampleEntries());
        List<MappingFile.Entry> letti =
                MappingFile.read(new ByteArrayInputStream(buf.toByteArray()));
        check(letti.size() == 4, "4 entry rilette");
        boolean uguali = true;
        for (int i = 0; i < letti.size(); i++) {
            uguali &= letti.get(i).wireId == sampleEntries().get(i).wireId
                    && letti.get(i).action == sampleEntries().get(i).action;
        }
        check(uguali, "roundtrip write/read identico");
    }

    private static void testMagicNonValido() {
        System.out.println("testMagicNonValido");
        byte[] buf = {'X', 'X', 'X', 'X', 1, 0, 0, 0, 0, 0};
        try {
            MappingFile.read(new ByteArrayInputStream(buf));
            check(false, "magic non valido deve fallire");
        } catch (BridgeException e) {
            check(true, "magic non valido rifiutato");
        } catch (IOException e) {
            check(false, "errore inatteso: " + e);
        }
    }

    private static void testMappingTroncato() throws Exception {
        System.out.println("testMappingTroncato");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        MappingFile.write(buf, sampleEntries());
        byte[] pieno = buf.toByteArray();
        byte[] troncato = Arrays.copyOf(pieno, pieno.length - 1);
        try {
            MappingFile.read(new ByteArrayInputStream(troncato));
            check(false, "mapping troncato deve fallire");
        } catch (BridgeException | java.io.EOFException e) {
            check(true, "mapping troncato rifiutato");
        }
    }

    private static void testAzioneNonMappata() throws Exception {
        System.out.println("testAzioneNonMappata");
        // LUT con una sola entry: PLAY_SOUND non è mappato.
        List<MappingFile.Entry> entries = new ArrayList<>();
        entries.add(new MappingFile.Entry(0x0001, NativeAction.NATIVE_TICK));
        U8Bridge bridge = new U8Bridge(new LookupTable(entries), new ByteArrayOutputStream());
        try {
            bridge.send(NativeAction.PLAY_SOUND);
            check(false, "azione non mappata deve fallire");
        } catch (BridgeException e) {
            check(true, "azione non mappata rifiutata");
        }
    }

    private static void testWireDuplicato() {
        System.out.println("testWireDuplicato");
        List<MappingFile.Entry> entries = new ArrayList<>();
        entries.add(new MappingFile.Entry(0x1234, NativeAction.PLAY_SOUND));
        entries.add(new MappingFile.Entry(0x1234, NativeAction.SET_BLOCK));
        try {
            new LookupTable(entries);
            check(false, "wire duplicato deve fallire");
        } catch (BridgeException e) {
            check(true, "wire duplicato rifiutato");
        }
    }

    /** REQUISITO: singolo pacchetto inviato in meno di 1 ms. */
    private static void testLatenzaSingoloPacchetto() throws Exception {
        System.out.println("testLatenzaSingoloPacchetto");
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try (U8Bridge bridge = bridgeSuBuffer(capture)) {
            bridge.send(NativeAction.PLAY_SOUND); // warm-up
            bridge.flush();
            long start = System.nanoTime();
            bridge.send(NativeAction.PLAY_SOUND);
            bridge.flush();
            long elapsedNs = System.nanoTime() - start;
            check(elapsedNs < 1_000_000,
                    "send+flush < 1 ms (misurato: " + elapsedNs / 1000 + " µs)");
        }
    }

    /** Budget frame: 300 pacchetti (5 frame a 60 FPS) in meno di 5 ms. */
    private static void testBudget300Pacchetti() throws Exception {
        System.out.println("testBudget300Pacchetti");
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try (U8Bridge bridge = bridgeSuBuffer(capture)) {
            for (int i = 0; i < 300; i++) {
                bridge.send(NativeAction.PLAY_SOUND);
            }
            bridge.flush(); // warm-up
            long start = System.nanoTime();
            for (int i = 0; i < 300; i++) {
                bridge.send(NativeAction.PLAY_SOUND);
            }
            bridge.flush();
            long elapsedNs = System.nanoTime() - start;
            check(elapsedNs < 5_000_000,
                    "300 send+flush < 5 ms (misurato: " + elapsedNs / 1000 + " µs)");
            check(bridge.packetsSent() == 600, "600 pacchetti totali inviati");
        }
    }

    private static void testHostHeadless() throws Exception {
        System.out.println("testHostHeadless");
        Path classesDir = Path.of(
                BridgeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try (U8Bridge bridge = bridgeSuBuffer(capture);
             HeadlessModHost host = new HeadlessModHost(classesDir)) {
            host.loadMod("dev.starling.bridge.testmod.SampleMod");
            check(host.modCount() == 1, "SampleMod caricata");
            host.enableAll(bridge); // NATIVE_HUD
            host.tickAll(bridge);   // PLAY_SOUND
            bridge.flush();
        }
        byte[] atteso = {0x02, 0x00, 0x34, 0x12};
        check(Arrays.equals(capture.toByteArray(), atteso),
                "enable+tick della mod → byte corretti sul wire");
        check("true".equals(System.getProperty("java.awt.headless")),
                "java.awt.headless=true impostato");
    }

    private static void testBlocklistRendering() throws Exception {
        System.out.println("testBlocklistRendering");
        Path classesDir = Path.of(
                BridgeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (HeadlessModHost host = new HeadlessModHost(classesDir)) {
            String[] vietate = {"org.lwjgl.opengl.GL11", "java.awt.Graphics",
                    "com.mojang.blaze3d.vertex.PoseStack"};
            for (String name : vietate) {
                try {
                    host.probeClass(name);
                    check(false, name + " dovrebbe essere bloccata");
                } catch (ClassNotFoundException e) {
                    check(true, name + " bloccata (headless)");
                }
            }
            // Le classi di logica invece sono caricabili.
            host.probeClass("dev.starling.bridge.testmod.SampleMod");
            check(true, "classe di logica caricabile");
        }
    }

    private static void testRegistryScan() throws Exception {
        System.out.println("testRegistryScan");
        Path mods = Files.createTempDirectory("bridge-mods");
        Path lut = Files.createTempDirectory("bridge-lut");
        Files.createFile(mods.resolve("mod-a.jar"));
        Files.createFile(mods.resolve("mod-b.jar"));
        Files.createFile(lut.resolve("mod-a.mapping.bin")); // solo mod-a ha il mapping

        ModRegistry.ScanResult scan = ModRegistry.scan(mods, lut);
        check(scan.ready.size() == 1 && scan.ready.get(0).getFileName().toString().equals("mod-a.jar"),
                "mod-a pronta (mapping esistente)");
        check(scan.needsAnalysis.size() == 1
                        && scan.needsAnalysis.get(0).getFileName().toString().equals("mod-b.jar"),
                "mod-b in coda di analisi offline");
    }
}