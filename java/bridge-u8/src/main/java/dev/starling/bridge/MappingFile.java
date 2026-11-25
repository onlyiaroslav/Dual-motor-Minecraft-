package dev.starling.bridge;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lettura/scrittura del formato binario {@code mapping.bin}, identico al crate
 * Rust {@code bridge-u8} (crates/bridge-u8/src/mapping.rs).
 *
 * <p>Layout (little-endian):
 * <pre>
 * offset 0  : magic "BU8M"        (4 byte)
 * offset 4  : versione u16 = 1
 * offset 6  : numero entry u32    (max 65536)
 * offset 10 : record × N          (wire_id u16 + action_id u16)
 * </pre>
 *
 * <p>Il file è generato OFFLINE (fase di build/analisi) e caricato all'avvio.
 * Zero AI a runtime.
 */
public final class MappingFile {
    public static final byte[] MAGIC = {'B', 'U', '8', 'M'};
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 65_536;

    /** Una entry della LUT: ID di wire → azione nativa. */
    public static final class Entry {
        public final int wireId;
        public final NativeAction action;

        public Entry(int wireId, NativeAction action) {
            if (wireId < 0 || wireId > 0xFFFF) {
                throw new IllegalArgumentException("wireId fuori range: " + wireId);
            }
            this.wireId = wireId;
            this.action = action;
        }
    }

    private MappingFile() {
    }

    /** Legge e valida un mapping. Solleva {@link BridgeException} se malformato. */
    public static List<Entry> read(InputStream raw) throws IOException, BridgeException {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = new byte[4];
        try {
            in.readFully(magic);
        } catch (EOFException e) {
            throw new BridgeException("mapping troncato (header)", e);
        }
        for (int i = 0; i < 4; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new BridgeException("magic non valido (atteso BU8M)");
            }
        }

        int version = readU16(in);
        if (version != VERSION) {
            throw new BridgeException("versione mapping non supportata: " + version);
        }
        long count = readU32(in);
        if (count > MAX_ENTRIES) {
            throw new BridgeException("troppe entry nel mapping: " + count);
        }

        List<Entry> entries = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            int wireId = readU16(in);
            int actionId = readU16(in);
            NativeAction action = NativeAction.fromId(actionId);
            if (action == null) {
                throw new BridgeException("ID azione sconosciuto nel file: " + actionId);
            }
            entries.add(new Entry(wireId, action));
        }
        return entries;
    }

    /** Scrive un mapping nel formato binario (tool di analisi offline). */
    public static void write(OutputStream out, List<Entry> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("troppe entry: " + entries.size());
        }
        out.write(MAGIC);
        writeU16(out, VERSION);
        writeU32(out, entries.size());
        for (Entry e : entries) {
            writeU16(out, e.wireId);
            writeU16(out, e.action.id);
        }
        out.flush();
    }

    private static int readU16(DataInputStream in) throws IOException {
        int lo = in.read();
        int hi = in.read();
        if ((lo | hi) < 0) {
            throw new EOFException("mapping troncato");
        }
        return lo | (hi << 8);
    }

    private static long readU32(DataInputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("mapping troncato");
            }
            v |= ((long) b) << (8 * i);
        }
        return v;
    }

    private static void writeU16(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void writeU32(OutputStream out, long v) throws IOException {
        for (int i = 0; i < 4; i++) {
            out.write((int) ((v >>> (8 * i)) & 0xFF));
        }
    }
}