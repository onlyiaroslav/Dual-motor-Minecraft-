package dev.starling.bridge;

/** Errore del bridge (formato mapping, LUT, protocollo). */
public class BridgeException extends Exception {
    private static final long serialVersionUID = 1L;

    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}