package dev.starling.bridge;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo roundtrip cross-linguaggio (Fase 1).
 *
 * <p>La JVM headless produce {@code mapping.bin} (LUT, come farebbe il tool di
 * analisi offline) e {@code stream.bin} (i pacchetti u16 LE di un frame).
 * L'esempio Rust {@code java_roundtrip} li legge e li dispatcha: se le azioni
 * stampate da Rust corrispondono, il contratto di wire è verificato byte per
 * byte tra i due linguaggi.
 *
 * <p>Esegui: java -cp build/classes dev.starling.bridge.RoundtripDemo [dir]
 */
public final class RoundtripDemo {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "/tmp/bridge-u8-java");
        Files.createDirectories(dir);

        // LUT condivisa (in produzione: generata offline, una volta per mod).
        List<MappingFile.Entry> entries = new ArrayList<>();
        entries.add(new MappingFile.Entry(0x0001, NativeAction.NATIVE_TICK));
        entries.add(new MappingFile.Entry(0x0002, NativeAction.NATIVE_HUD));
        entries.add(new MappingFile.Entry(0x1234, NativeAction.PLAY_SOUND));
        entries.add(new MappingFile.Entry(0xBEEF, NativeAction.SET_BLOCK));
        try (OutputStream out = Files.newOutputStream(dir.resolve("mapping.bin"))) {
            MappingFile.write(out, entries);
        }

        // Un "frame" di logica mod: 4 azioni → 8 byte sul wire.
        LookupTable lut = new LookupTable(entries);
        long start = System.nanoTime();
        try (U8Bridge bridge = new U8Bridge(lut, Files.newOutputStream(dir.resolve("stream.bin")))) {
            bridge.send(NativeAction.NATIVE_TICK);
            bridge.send(NativeAction.NATIVE_HUD);
            bridge.send(NativeAction.PLAY_SOUND);
            bridge.send(NativeAction.SET_BLOCK);
        } // close → flush finale
        long elapsedNs = System.nanoTime() - start;

        System.out.println("mapping.bin + stream.bin scritti in " + dir);
        System.out.println("4 pacchetti (8 byte) codificati in " + elapsedNs / 1000 + " µs");
        System.out.println("atteso in Rust: [NativeTick, NativeHud, PlaySound, SetBlock]");
    }
}