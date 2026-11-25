package dev.starling.bridge;

import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mock Java per la simulazione del game loop (tests/game_loop_sim.cpp).
 *
 * <p>Produce 5 pacchetti per tick (20 TPS) nel ring condiviso; il "motore"
 * C++ fa polling a 60 FPS. I contatori permettono di verificare zero perdite
 * e zero scarti. Host-only: non viene incluso nell'APK.
 */
public final class GameLoopMock {
    private static RingBuffer ring;
    private static U8Bridge bridge;
    private static Thread producer;
    private static volatile boolean running;
    private static volatile long produced;
    private static final LongAdder RECEIVED = new LongAdder();
    private static boolean soLoaded;

    /** Carica la .so, crea ring + attach, avvia il thread produttore. */
    public static long start(String mappingPath, String soPath) throws Exception {
        if (!soLoaded) {
            System.load(soPath);
            soLoaded = true;
        }
        ring = RingBuffer.create(65_536);
        long handle = NativeTransport.nativeAttach(mappingPath, ring.buffer(),
                ids -> RECEIVED.add(ids.length));
        LookupTable lut = LookupTable.load(Path.of(mappingPath));
        bridge = new U8Bridge(lut, ring.asOutputStream());
        produced = 0;
        RECEIVED.reset(); // contatori per-sessione (stress attach/detach)
        running = true;
        producer = new Thread(() -> {
            while (running) {
                try {
                    for (int i = 0; i < 5; i++) {
                        bridge.send(NativeAction.PLAY_SOUND);
                    }
                    bridge.flush();
                    produced += 5;
                    Thread.sleep(50); // 20 TPS
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    t.printStackTrace();
                    running = false;
                }
            }
        }, "mock-mod-tick");
        producer.setDaemon(true);
        producer.start();
        return handle;
    }

    /** Ferma il produttore e flusha gli ultimi pacchetti (il drain è del motore). */
    public static void stopProducer() throws Exception {
        running = false;
        if (producer != null) {
            producer.join(2000);
            producer = null;
        }
        if (bridge != null) {
            bridge.flush();
        }
    }

    public static void detach(long handle) {
        if (ring != null) {
            lastDropped = ring.droppedBytes();
        }
        NativeTransport.nativeDetach(handle);
        bridge = null;
        ring = null;
    }

    public static long producedPackets() {
        return produced;
    }

    public static long receivedActions() {
        return RECEIVED.sum();
    }

    private static long lastDropped;

    public static long droppedBytes() {
        return ring != null ? ring.droppedBytes() : lastDropped;
    }
}