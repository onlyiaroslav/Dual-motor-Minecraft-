package dev.starling.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test del ring buffer produttore Java (senza JNI: il consumatore è simulato). */
public final class RingBufferTest {
    private static int passed;
    private static int failed;

    private static final VarHandle LONG_VIEW =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    public static void main(String[] args) throws Exception {
        testHeader();
        testPacchetto2Byte();
        testWrapEOverflow();
        testOutputStreamConU8Bridge();
        testOverflowOutputStream();
        testMapped();
        testCapacitaNonValida();
        System.out.printf("%nTest RingBuffer: %d passati, %d falliti%n", passed, failed);
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

    private static List<MappingFile.Entry> sampleEntries() {
        List<MappingFile.Entry> entries = new ArrayList<>();
        entries.add(new MappingFile.Entry(0x0001, NativeAction.NATIVE_TICK));
        entries.add(new MappingFile.Entry(0x0002, NativeAction.NATIVE_HUD));
        entries.add(new MappingFile.Entry(0x1234, NativeAction.PLAY_SOUND));
        entries.add(new MappingFile.Entry(0xBEEF, NativeAction.SET_BLOCK));
        return entries;
    }

    private static void testHeader() {
        System.out.println("testHeader");
        RingBuffer ring = RingBuffer.create(64);
        ByteBuffer b = ring.buffer();
        check(b.isDirect(), "buffer direct");
        check(b.getInt(0) == RingBuffer.MAGIC, "magic BRG1");
        check(b.getInt(4) == 1, "versione 1");
        check(b.getInt(8) == 64, "capacità 64");
        check((long) LONG_VIEW.get(b, 16) == 0, "write_index iniziale 0");
        check((long) LONG_VIEW.get(b, 64) == 0, "read_index iniziale 0");
    }

    private static void testPacchetto2Byte() {
        System.out.println("testPacchetto2Byte");
        RingBuffer ring = RingBuffer.create(64);
        check(ring.trySendWire(0x1234), "send riuscito");
        ByteBuffer b = ring.buffer();
        check(b.get(128) == 0x34 && b.get(129) == 0x12, "dati [0x34, 0x12] a offset 128");
        check(ring.writeIndex() == 2, "write_index pubblicato = 2");
    }

    private static void testWrapEOverflow() {
        System.out.println("testWrapEOverflow");
        RingBuffer ring = RingBuffer.create(16);
        for (int i = 0; i < 8; i++) {
            check(ring.trySendWire(0x1234), "pacchetto " + i + " dentro la capacità");
        }
        check(!ring.trySendWire(0x1234), "ring pieno: send scartato");
        check(ring.droppedBytes() == 2, "2 byte scartati contati");

        // Simula il consumatore Rust che pubblica read_index (release).
        LONG_VIEW.setRelease(ring.buffer(), 64, 16L);
        check(ring.trySendWire(0xBEEF), "dopo il consume, send riesce (wrap)");
        ByteBuffer b = ring.buffer();
        check(b.get(128) == (byte) 0xEF && b.get(129) == (byte) 0xBE,
                "pacchetto wrappato scritto a inizio regione dati");
    }

    private static void testOutputStreamConU8Bridge() throws Exception {
        System.out.println("testOutputStreamConU8Bridge");
        RingBuffer ring = RingBuffer.create(1024);
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        U8Bridge bridge = new U8Bridge(new LookupTable(sampleEntries()), ring.asOutputStream());
        bridge.send(NativeAction.NATIVE_TICK);
        bridge.send(NativeAction.NATIVE_HUD);
        bridge.send(NativeAction.PLAY_SOUND);
        bridge.send(NativeAction.SET_BLOCK);
        bridge.flush();
        ByteBuffer b = ring.buffer();
        byte[] atteso = {0x01, 0x00, 0x02, 0x00, 0x34, 0x12, (byte) 0xEF, (byte) 0xBE};
        boolean ok = true;
        for (int i = 0; i < atteso.length; i++) {
            ok &= b.get(128 + i) == atteso[i];
        }
        check(ok, "U8Bridge → ring: 8 byte identici al flusso diretto");
        check(ring.writeIndex() == 8, "una pubblicazione per flush");
        check(ignored.size() == 0, "nessun altro trasporto usato");
    }

    private static void testOverflowOutputStream() throws Exception {
        System.out.println("testOverflowOutputStream");
        RingBuffer ring = RingBuffer.create(16);
        U8Bridge bridge = new U8Bridge(new LookupTable(sampleEntries()), ring.asOutputStream());
        for (int i = 0; i < 4; i++) {
            bridge.send(NativeAction.PLAY_SOUND);
        }
        bridge.flush(); // 8 byte: OK
        for (int i = 0; i < 4; i++) {
            bridge.send(NativeAction.PLAY_SOUND);
        }
        bridge.flush(); // 16 byte totali: il ring è ora esattamente pieno
        for (int i = 0; i < 4; i++) {
            bridge.send(NativeAction.PLAY_SOUND);
        }
        try {
            bridge.flush(); // terzo batch: ring pieno (nessun consumatore)
            check(false, "flush su ring pieno deve fallire");
        } catch (IOException e) {
            check(true, "IOException su ring pieno: " + e.getMessage());
        }
    }

    private static void testMapped() throws Exception {
        System.out.println("testMapped");
        Path file = Files.createTempFile("bridge-ring", ".bin");
        RingBuffer ring = RingBuffer.mapped(file, 64);
        check(ring.buffer().isDirect(), "mmap è direct");
        check(ring.trySendWire(0x1234), "send su mmap");
        check(ring.buffer().get(128) == 0x34, "dato visibile nel file mappato");
        check(Files.size(file) == 128 + 64, "file della dimensione attesa");
        Files.deleteIfExists(file);
    }

    private static void testCapacitaNonValida() {
        System.out.println("testCapacitaNonValida");
        int[] invalidi = {8, 100, 0, -16};
        for (int cap : invalidi) {
            try {
                RingBuffer.create(cap);
                check(false, "capacità " + cap + " deve essere rifiutata");
            } catch (IllegalArgumentException e) {
                check(true, "capacità " + cap + " rifiutata");
            }
        }
    }
}