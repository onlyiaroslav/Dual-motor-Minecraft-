package dev.starling.bridge;

import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Bridge a 8 bit lato JVM: codifica solo ID numerici, mai stringhe o oggetti.
 *
 * <p>Ogni azione diventa un pacchetto di 2 byte (ID di wire {@code u16}
 * little-endian). I pacchetti sono accumulati in un buffer interno e inviati
 * con un unico {@link #flush()} per frame: una sola write sul trasporto
 * (pipe/socket/JNI) per frame, per restare sotto il budget di 5 ms a 60 FPS.
 *
 * <p>Il trasporto è un semplice {@link OutputStream}: su Android sarà la pipe
 * o il callback JNI verso Rust; nei test è un buffer in memoria.
 *
 * <p>Thread-safe: le mod possono chiamare {@link #send} da thread diversi.
 */
public final class U8Bridge implements Flushable, AutoCloseable {
    private static final int BUF_SIZE = 8 * 1024;

    private final LookupTable lut;
    private final OutputStream out;
    private final byte[] buf = new byte[BUF_SIZE];
    private int pos;
    private long packetsSent;
    private boolean closed;

    public U8Bridge(LookupTable lut, OutputStream out) {
        this.lut = lut;
        this.out = out;
    }

    /**
     * Invia un'azione nativa: risolve l'ID di wire dalla LUT e accoda 2 byte.
     * Non blocca mai sul trasporto (la write avviene solo al flush).
     */
    public synchronized void send(NativeAction action) throws IOException, BridgeException {
        sendWireId(lut.wireIdFor(action));
    }

    /** Invia un ID di wire già risolto (0..0xFFFF). */
    public synchronized void sendWireId(int wireId) throws IOException {
        if (closed) {
            throw new IOException("bridge chiuso");
        }
        if (wireId < 0 || wireId > 0xFFFF) {
            throw new IllegalArgumentException("wireId fuori range: " + wireId);
        }
        if (pos + 2 > buf.length) {
            flushBuffer();
        }
        buf[pos++] = (byte) (wireId & 0xFF);
        buf[pos++] = (byte) ((wireId >>> 8) & 0xFF);
        packetsSent++;
    }

    /** Invia tutti i pacchetti accumulati sul trasporto. Chiamare 1×/frame. */
    @Override
    public synchronized void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    private void flushBuffer() throws IOException {
        if (pos > 0) {
            out.write(buf, 0, pos);
            pos = 0;
        }
    }

    /** Pacchetti accodati dall'apertura (diagnostica, non sul wire). */
    public synchronized long packetsSent() {
        return packetsSent;
    }

    /** Byte in attesa nel buffer (diagnostica). */
    public synchronized int pendingBytes() {
        return pos;
    }

    /** Flush finale e chiusura del trasporto. */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            flush();
            closed = true;
            out.close();
        }
    }
}