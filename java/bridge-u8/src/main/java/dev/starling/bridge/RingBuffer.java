package dev.starling.bridge;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Produttore Java del ring buffer SPSC condiviso con Rust (modalità in-process).
 *
 * <p>Layout identico a {@code crates/bridge-jni/src/ring.rs}: header 128 byte,
 * indici u64 atomici su cache line separate (write @16, read @64), dati @128.
 *
 * <p>Memoria condivisa:
 * <ul>
 *   <li>{@link #create(int)}: direct ByteBuffer in-process (zero copie JNI,
 *       Rust legge via GetDirectBufferAddress);</li>
 *   <li>{@link #mapped(Path, int)}: file mmap (anche cross-process).</li>
 * </ul>
 *
 * <p>Ordinamento memoria: VarHandle setRelease/getAcquire sugli indici,
 * accoppiati con AtomicU64 Release/Acquire lato Rust. I dati sono scritti
 * con put plain PRIMA della pubblicazione release dell'indice.
 *
 * <p>Overflow: {@link #trySendWire} scarta e conta (mai bloccare la JVM).
 * L'OutputStream derivato invece solleva IOException (flush di frame perso).
 *
 * <p>Requisiti: ByteBuffer DIRECT, little-endian, base allineata a 8 byte
 * (allocateDirect e mmap lo garantiscono; verificato anche lato Rust).
 * VarHandle richiede API 33+ su Android (target Android 15 = API 35: OK).
 */
public final class RingBuffer {
    public static final int HEADER_LEN = 128;
    public static final int MAGIC = 0x31475242; // "BRG1" LE
    public static final int VERSION = 1;

    private static final int WRITE_OFF = 16;
    private static final int DROPPED_OFF = 24;
    private static final int READ_OFF = 64;

    private static final VarHandle LONG_VIEW =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final ByteBuffer buf;
    private final int capacity;
    private final int mask;
    private long write; // indice locale del produttore (monotono)
    private long dropped;

    private RingBuffer(ByteBuffer buf, int capacity) {
        this.buf = buf;
        this.capacity = capacity;
        this.mask = capacity - 1;
    }

    /** Ring in-process su direct ByteBuffer. Capacità: potenza di 2, >= 16. */
    public static RingBuffer create(int dataCapacity) {
        checkCapacity(dataCapacity);
        ByteBuffer b = ByteBuffer.allocateDirect(HEADER_LEN + dataCapacity)
                .order(ByteOrder.LITTLE_ENDIAN);
        initHeader(b, dataCapacity);
        return new RingBuffer(b, dataCapacity);
    }

    /** Ring su file mmap (in-process o cross-process sullo stesso file). */
    public static RingBuffer mapped(Path file, int dataCapacity) throws IOException {
        checkCapacity(dataCapacity);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_WRITE, 0, HEADER_LEN + dataCapacity);
            b.order(ByteOrder.LITTLE_ENDIAN);
            initHeader(b, dataCapacity);
            return new RingBuffer(b, dataCapacity);
        }
    }

    private static void checkCapacity(int cap) {
        if (cap < 16 || Integer.bitCount(cap) != 1) {
            throw new IllegalArgumentException("capacità non valida (potenza di 2, >= 16): " + cap);
        }
    }

    private static void initHeader(ByteBuffer b, int cap) {
        b.putInt(0, MAGIC);
        b.putInt(4, VERSION);
        b.putInt(8, cap);
        b.putInt(12, 0);
        LONG_VIEW.set(b, WRITE_OFF, 0L);
        LONG_VIEW.set(b, DROPPED_OFF, 0L);
        LONG_VIEW.set(b, READ_OFF, 0L);
    }

    /** Il buffer da passare a {@code NativeTransport.nativeAttach}. */
    public ByteBuffer buffer() {
        return buf;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Accoda un pacchetto u16 LE. Ritorna false se il ring è pieno
     * (il byte viene scartato e contato in {@link #droppedBytes()}).
     */
    public boolean trySendWire(int wireId) {
        long read = (long) LONG_VIEW.getAcquire(buf, READ_OFF);
        if (capacity - (write - read) < 2) {
            dropped += 2;
            LONG_VIEW.set(buf, DROPPED_OFF, dropped);
            return false;
        }
        int pos = (int) (write & mask);
        byte lo = (byte) wireId;
        byte hi = (byte) (wireId >>> 8);
        if (pos + 2 <= capacity) {
            buf.put(HEADER_LEN + pos, lo);
            buf.put(HEADER_LEN + pos + 1, hi);
        } else {
            buf.put(HEADER_LEN + pos, lo);
            buf.put(HEADER_LEN, hi); // wrap
        }
        write += 2;
        LONG_VIEW.setRelease(buf, WRITE_OFF, write);
        return true;
    }

    /** Byte scartati per overflow (diagnostica per il desync/log del motore). */
    public long droppedBytes() {
        return dropped;
    }

    /** Indice di scrittura pubblicato (diagnostica). */
    public long writeIndex() {
        return write;
    }

    /**
     * Adattatore OutputStream: permette di riusare {@link U8Bridge} senza
     * modifiche. Pubblica l'indice una volta per write (cioè una volta per
     * frame, dato che U8Bridge bufferizza). Ring pieno → IOException.
     */
    public OutputStream asOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] src, int off, int len) throws IOException {
                long read = (long) LONG_VIEW.getAcquire(buf, READ_OFF);
                if (capacity - (write - read) < len) {
                    dropped += len;
                    LONG_VIEW.set(buf, DROPPED_OFF, dropped);
                    throw new IOException("ring pieno: " + len + " byte scartati");
                }
                int pos = (int) (write & mask);
                int first = Math.min(len, capacity - pos);
                // Copia bulk nella regione dati (offset HEADER_LEN).
                for (int i = 0; i < first; i++) {
                    buf.put(HEADER_LEN + pos + i, src[off + i]);
                }
                for (int i = first; i < len; i++) {
                    buf.put(HEADER_LEN + (i - first), src[off + i]);
                }
                write += len;
                LONG_VIEW.setRelease(buf, WRITE_OFF, write);
            }
        };
    }
}