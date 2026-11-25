package dev.starling.packs;

/**
 * Gate anti-loop per il launcher (FIX del bug di download infinito).
 *
 * <p>Regola ferrea: un pack ESTERNO non valido viene scartato con un messaggio
 * d'errore e NON riavvia mai il download dei file base. Il download base è
 * decided solo da {@link #checkBaseFiles(boolean)}, cioè dalla mancanza reale
 * dei file base, indipendentemente dallo stato dei pack esterni.
 *
 * <p>Flusso launcher corretto:
 * <pre>
 *   result = importer.importZip(...)
 *   if (!result.ok) {
 *       gate.onExternalPackInvalid(result.error);   // → BaseAction.NONE
 *       showError(result.error);
 *       return;                                     // NIENTE restart download
 *   }
 *   gate.onExternalPackValid();
 *   // ... altrove, solo se i file base mancano davvero:
 *   if (gate.checkBaseFiles(baseMissing) == DOWNLOAD) downloadBaseFiles();
 * </pre>
 */
public final class PackGate {

    public enum BaseAction { NONE, DOWNLOAD }

    private boolean externalRejected;
    private String lastRejectReason;

    /** Pack esterno rifiutato: registra il motivo, MAI download base. */
    public BaseAction onExternalPackInvalid(String reason) {
        externalRejected = true;
        lastRejectReason = reason;
        return BaseAction.NONE;
    }

    /** Pack esterno valido: reset dello stato di rifiuto. */
    public void onExternalPackValid() {
        externalRejected = false;
        lastRejectReason = null;
    }

    /**
     * Decisione sul download dei file base: dipende SOLO dalla loro mancanza
     * reale, mai dallo stato dei pack esterni.
     */
    public BaseAction checkBaseFiles(boolean baseFilesMissing) {
        return baseFilesMissing ? BaseAction.DOWNLOAD : BaseAction.NONE;
    }

    public boolean externalRejected() {
        return externalRejected;
    }

    public String lastRejectReason() {
        return lastRejectReason;
    }
}