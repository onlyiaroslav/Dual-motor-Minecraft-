package dev.starling.converter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test JNI end-to-end del convertitore offline con AI locale (Fase 7).
 * Richiede {@code libmod_converter.so} in {@code java.library.path}.
 *
 * <p>Verifica il toggle "AI Locale per Conversione Mod", il fallback quando il
 * modello è assente, e la generazione di {@code mapping.bin} +
 * {@code conversion_log.json} con il vincolo "zero codice copiato".
 */
public final class AiConverterBridgeTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testCreateDefaultDisarmed();
        testToggleOffNonUsaAi();
        testToggleOnModelloAssente();
        testToggleOnModelloPresente();
        testConvertJarAiDisattivata();
        testConvertJarConLibreria();
        testChiusura();
        System.out.printf("%nTest AiConverterBridge: %d passati, %d falliti%n", passed, failed);
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

    // --- Helper: JAR di test con una classe minimale (2 metodi) -------------

    private static void writeUtf8(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(1); // CONSTANT_Utf8
        out.writeShort(b.length);
        out.write(b);
    }

    private static byte[] testClassBytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos); // big-endian
        out.writeInt(0xCAFEBABE);
        out.writeShort(0); // minor
        out.writeShort(52); // major
        out.writeShort(6); // cp_count (indici 1..5)
        writeUtf8(out, "Test"); // 1
        writeUtf8(out, "a"); // 2
        writeUtf8(out, "()V"); // 3
        writeUtf8(out, "b"); // 4
        writeUtf8(out, "(Ljava/lang/String;)Z"); // 5
        out.writeShort(9); // access
        out.writeShort(1); // this_class
        out.writeShort(0); // super_class
        out.writeShort(0); // interfaces
        out.writeShort(0); // fields
        out.writeShort(2); // methods
        out.writeShort(9); out.writeShort(2); out.writeShort(3); out.writeShort(0); // m1
        out.writeShort(9); out.writeShort(4); out.writeShort(5); out.writeShort(0); // m2
        out.flush();
        return bos.toByteArray();
    }

    private static Path buildTestJar() throws IOException {
        Path dir = Files.createTempDirectory("starling-conv-jar");
        Path jar = dir.resolve("test.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("com/example/Test.class"));
            zos.write(testClassBytes());
            zos.closeEntry();
        }
        return jar;
    }

    private static Path tempModel() throws IOException {
        Path p = Files.createTempDirectory("starling-conv-model").resolve("model.gguf");
        Files.write(p, new byte[]{'G', 'G', 'U', 'F'});
        return p;
    }

    // --- Test ---------------------------------------------------------------

    private static void testCreateDefaultDisarmed() {
        System.out.println("testCreateDefaultDisarmed");
        try (AiConverterBridge conv = new AiConverterBridge("", "")) {
            check("disarmed".equals(conv.aiState()), "AI parte disattivata (default privacy)");
        }
    }

    private static void testToggleOffNonUsaAi() {
        System.out.println("testToggleOffNonUsaAi");
        try (AiConverterBridge conv = new AiConverterBridge("", "")) {
            String state = conv.setAiEnabled(false);
            check("disarmed".equals(state), "toggle OFF -> disarmed");
            check(conv.translateFragment("com.example.Test.a()V") == -1,
                    "con AI spenta nessuna azione (-1)");
        }
    }

    private static void testToggleOnModelloAssente() {
        System.out.println("testToggleOnModelloAssente");
        try (AiConverterBridge conv = new AiConverterBridge("", "")) {
            String state = conv.setAiEnabled(true);
            check("not_ready".equals(state), "modello assente -> not_ready (fallback)");
            check(conv.translateFragment("com.example.Test.a()V") == -1,
                    "fallback: nessuna azione senza modello");
        }
    }

    private static void testToggleOnModelloPresente() throws Exception {
        System.out.println("testToggleOnModelloPresente");
        Path model = tempModel();
        try (AiConverterBridge conv = new AiConverterBridge(model.toString(), "")) {
            String state = conv.setAiEnabled(true);
            check("ready".equals(state), "modello presente -> ready");
            // v0: runtime llama.cpp non collegato -> inferenza non disponibile -> fallback.
            check(conv.translateFragment("com.example.Test.a()V") == -1,
                    "ready ma inference placeholder -> fallback (-1)");
        } finally {
            Files.deleteIfExists(model);
        }
    }

    private static void testConvertJarAiDisattivata() throws Exception {
        System.out.println("testConvertJarAiDisattivata");
        Path jar = buildTestJar();
        Path out = Files.createTempDirectory("starling-conv-out");
        try (AiConverterBridge conv = new AiConverterBridge("", "")) {
            conv.setAiEnabled(false);
            String summary = conv.convertJar(jar.toString(), out.toString());
            check(summary.contains("discarded=2"), "2 firme scartate con AI spenta: " + summary);
            check(summary.contains("mapped=0"), "nessuna entry mappata senza AI/libreria");
            check(summary.contains("ai_ready=false"), "AI non pronta");

            Path mapping = out.resolve("mapping.bin");
            Path log = out.resolve("conversion_log.json");
            check(Files.exists(mapping), "mapping.bin generato");
            check(Files.exists(log), "conversion_log.json generato");

            byte[] mb = Files.readAllBytes(mapping);
            check(mb.length >= 4 && mb[0] == 'B' && mb[1] == 'U' && mb[2] == '8' && mb[3] == 'M',
                    "mapping.bin con magic BU8M");

            String json = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            check(json.contains("AI disattivata"), "log contiene 'AI disattivata'");
            check(json.contains("com.example.Test.a()V"), "log contiene la firma (solo firma)");
            check(!json.contains("CAFEBABE"), "zero codice copiato: nessun bytecode nel log");
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static void testConvertJarConLibreria() throws Exception {
        System.out.println("testConvertJarConLibreria");
        // Anche con AI spenta, una libreria mapping.bin esistente viene usata.
        // Qui la libreria è vuota: verifica solo che il percorso non crashi e
        // che il riepilogo riporti library=0.
        Path jar = buildTestJar();
        Path out = Files.createTempDirectory("starling-conv-lib");
        try (AiConverterBridge conv = new AiConverterBridge("", "")) {
            conv.setAiEnabled(false);
            String summary = conv.convertJar(jar.toString(), out.toString());
            check(summary.contains("library=0"), "libreria vuota: library=0");
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    private static void testChiusura() {
        System.out.println("testChiusura");
        AiConverterBridge conv = new AiConverterBridge("", "");
        conv.close();
        boolean threw = false;
        try {
            conv.aiState();
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "dopo close() le chiamate sollevano IllegalStateException");
    }
}
