package dev.starling.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * LUT lato Java (direzione di invio): risolve {@link NativeAction} → ID di wire.
 *
 * <p>È l'inverso della LUT Rust: la JVM conosce l'azione che una mod vuole
 * eseguire e la traduce nell'ID numerico a 16 bit da inviare sul wire.
 * Il motore Rust risolverà poi l'ID nella propria {@code LookupTable}.
 *
 * <p>Caricata una sola volta all'avvio da {@code mapping.bin}; nessuna
 * allocazione nell'hot path di invio.
 */
public final class LookupTable {
    /** wireByAction[action.id] = ID di wire, oppure -1 se non mappato. */
    private final int[] wireByAction;
    private final int mappedActions;

    public LookupTable(List<MappingFile.Entry> entries) throws BridgeException {
        int maxId = 0;
        for (MappingFile.Entry e : entries) {
            maxId = Math.max(maxId, e.action.id);
        }
        wireByAction = new int[maxId + 1];
        Arrays.fill(wireByAction, -1);

        boolean[] seenWire = new boolean[65_536];
        int mapped = 0;
        for (MappingFile.Entry e : entries) {
            if (seenWire[e.wireId]) {
                throw new BridgeException(
                        String.format("ID di wire duplicato: 0x%04X", e.wireId));
            }
            seenWire[e.wireId] = true;
            // Se più ID di wire puntano alla stessa azione, vince il primo.
            if (wireByAction[e.action.id] == -1) {
                wireByAction[e.action.id] = e.wireId;
                mapped++;
            }
        }
        mappedActions = mapped;
    }

    /** Carica la LUT da un file {@code mapping.bin}. */
    public static LookupTable load(Path file) throws IOException, BridgeException {
        try (InputStream in = Files.newInputStream(file)) {
            return new LookupTable(MappingFile.read(in));
        }
    }

    /** ID di wire per l'azione; errore se l'azione non è nel mapping. */
    public int wireIdFor(NativeAction action) throws BridgeException {
        int wire = action.id < wireByAction.length ? wireByAction[action.id] : -1;
        if (wire < 0) {
            throw new BridgeException("azione non mappata: " + action);
        }
        return wire;
    }

    /** Numero di azioni distinte mappate. */
    public int mappedActions() {
        return mappedActions;
    }
}