package dev.starling.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Test JNI end-to-end IN-PROCESS: Java produce nel ring → Rust dispatcha via
 * LUT → callback batch al sink Java. Richiede libbridge_jni.so compilata per
 * l'host corrente.
 *
 * Esegui: java -cp build/classes dev.starling.bridge.JniRoundtripTest <percorso .so>
 */
public final class JniRoundtripTest {
    private static int passed;
    private static int failed;
    private static Path mappingFile;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("uso: JniRoundtripTest <percorso libbridge_jni.so>");
            System.exit(2);
        }
        System.load(args[0]);

        List<MappingFile.Entry> entries = new ArrayList<>();
        entries.add(new MappingFile.Entry(0x0001, NativeAction.NATIVE_TICK));
        entries.add(new MappingFile.Entry(0x0002, NativeAction.NATIVE_HUD));
        entries.add(new MappingFile.Entry(0x1234, NativeAction.PLAY_SOUND));
        entries.add(new MappingFile.Entry(0xBEEF, NativeAction.SET_BLOCK));
        mappingFile = Files.createTempFile("bridge-mapping", ".bin");
        MappingFile.write(Files.newOutputStream(mappingFile), entries);

        testAttachPollSink();
        testDispatchDiretto();
        testWrapStress();
        testBufferNonDirect();
        testMagicNonValido();
        testIdNonMappato();

        Files.deleteIfExists(mappingFile);
        System.out.printf("%nTest JNI: %d passati, %d falliti%n", passed, failed);
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

    private static LookupTable lut() throws Exception {
        return LookupTable.load(mappingFile);
    }

    private static void testAttachPollSink() throws Exception {
        System.out.println("testAttachPollSink");
        RingBuffer ring = RingBuffer.create(65_536);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());
        NativeTransport.NativeSink sink = ids -> {
            for (int id : ids) {
                received.add(id);
            }
        };
        long h = NativeTransport.nativeAttach(mappingFile.toString(), ring.buffer(), sink);
        check(h != 0, "attach riuscito (handle != 0)");

        U8Bridge bridge = new U8Bridge(lut(), ring.asOutputStream());
        bridge.send(NativeAction.NATIVE_TICK);
        bridge.send(NativeAction.NATIVE_HUD);
        bridge.send(NativeAction.PLAY_SOUND);
        bridge.send(NativeAction.SET_BLOCK);
        bridge.flush();

        int n = NativeTransport.nativePoll(h);
        check(n == 8, "poll consuma 8 byte");
        check(received.equals(Arrays.asList(1, 2, 5, 6)),
                "sink riceve [1, 2, 5, 6] in batch (azioni corrette, ordine corretto)");
        check(NativeTransport.nativePoll(h) == 0, "secondo poll: nulla da consumare");
        NativeTransport.nativeDetach(h);
    }

    private static void testDispatchDiretto() throws Exception {
        System.out.println("testDispatchDiretto");
        RingBuffer ring = RingBuffer.create(64);
        List<Integer> received = new ArrayList<>();
        long h = NativeTransport.nativeAttach(mappingFile.toString(), ring.buffer(),
                ids -> {
                    for (int id : ids) {
                        received.add(id);
                    }
                });
        int n = NativeTransport.nativeDispatch(h, new byte[]{0x34, 0x12}, 2);
        check(n == 2, "dispatch diretto di 2 byte");
        check(received.equals(Arrays.asList(5)), "sink riceve PlaySound (5)");
        NativeTransport.nativeDetach(h);
    }

    private static void testWrapStress() throws Exception {
        System.out.println("testWrapStress");
        RingBuffer ring = RingBuffer.create(16); // capacità minuscola: wrap continuo
        List<Integer> received = new ArrayList<>();
        long h = NativeTransport.nativeAttach(mappingFile.toString(), ring.buffer(),
                ids -> {
                    for (int id : ids) {
                        received.add(id);
                    }
                });
        boolean tuttoOk = true;
        for (int frame = 0; frame < 250; frame++) {
            for (int i = 0; i < 4; i++) {
                tuttoOk &= ring.trySendWire(0x1234); // 8 byte per "frame"
            }
            int n = NativeTransport.nativePoll(h);
            tuttoOk &= (n == 8);
        }
        check(tuttoOk, "1000 pacchetti attraverso ring da 16 byte (wrap continuo)");
        check(received.size() == 1000, "sink riceve 1000 azioni");
        check(ring.droppedBytes() == 0, "nessun byte scartato");
        NativeTransport.nativeDetach(h);
    }

    private static void testBufferNonDirect() {
        System.out.println("testBufferNonDirect");
        ByteBuffer heap = ByteBuffer.allocate(256);
        try {
            NativeTransport.nativeAttach(mappingFile.toString(), heap, ids -> { });
            check(false, "buffer heap deve essere rifiutato");
        } catch (IllegalArgumentException e) {
            check(true, "buffer heap rifiutato: " + e.getMessage());
        }
    }

    private static void testMagicNonValido() {
        System.out.println("testMagicNonValido");
        ByteBuffer b = ByteBuffer.allocateDirect(192).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0xDEADBEEF);
        try {
            NativeTransport.nativeAttach(mappingFile.toString(), b, ids -> { });
            check(false, "magic non valido deve essere rifiutato");
        } catch (IllegalArgumentException e) {
            check(true, "magic non valido rifiutato lato Rust");
        }
    }

    private static void testIdNonMappato() throws Exception {
        System.out.println("testIdNonMappato");
        RingBuffer ring = RingBuffer.create(64);
        long h = NativeTransport.nativeAttach(mappingFile.toString(), ring.buffer(),
                ids -> { });
        check(ring.trySendWire(0x9900), "pacchetto con ID non mappato inviato");
        try {
            NativeTransport.nativePoll(h);
            check(false, "ID non mappato deve sollevare BridgeException");
        } catch (BridgeException e) {
            check(true, "BridgeException propagata da Rust: " + e.getMessage());
        }
        check(NativeTransport.nativePoll(h) == 0, "read_index avanzato (stream compromesso)");
        NativeTransport.nativeDetach(h);
    }
}