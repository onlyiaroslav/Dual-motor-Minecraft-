package dev.starling.bridge;

/**
 * Azioni native del motore Rust (Starling/Horizon).
 *
 * <p>Gli ID sono STABILI e permanenti e devono corrispondere esattamente
 * all'enum {@code NativeAction} del crate Rust {@code bridge-u8}
 * (crates/bridge-u8/src/action.rs). Non riutilizzare mai un ID dismesso.
 *
 * <p>La JVM non invia mai il nome dell'azione sul wire: invia solo l'ID di
 * wire a 16 bit risolto dalla LUT ({@link LookupTable}).
 */
public enum NativeAction {
    NOOP(0),
    NATIVE_TICK(1),
    NATIVE_HUD(2),
    NATIVE_KEY(3),
    SPAWN_PARTICLE(4),
    PLAY_SOUND(5),
    SET_BLOCK(6),
    BREAK_BLOCK(7),
    SEND_CHAT(8),
    APPLY_VELOCITY(9),
    SET_WORLD_TIME(10),
    OPEN_SCREEN(11);

    /** ID numerico stabile (identico al crate Rust). */
    public final int id;

    private static final NativeAction[] BY_ID;

    static {
        int max = 0;
        for (NativeAction a : values()) {
            max = Math.max(max, a.id);
        }
        BY_ID = new NativeAction[max + 1];
        for (NativeAction a : values()) {
            BY_ID[a.id] = a;
        }
    }

    NativeAction(int id) {
        this.id = id;
    }

    /** Risolve un ID azione; {@code null} se sconosciuto. */
    public static NativeAction fromId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            return null;
        }
        return BY_ID[id];
    }
}