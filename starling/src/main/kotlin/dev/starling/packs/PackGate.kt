package dev.starling.packs

/**
 * Azione restituita dal gate di controllo file base.
 */
sealed class BaseAction {
    object Download : BaseAction()
    object Skip : BaseAction()
}

/**
 * Gate anti-loop per pack esterni (stub per compilazione).
 */
class PackGate {
    fun onExternalPackValid() { /* no-op stub */ }
    fun onExternalPackInvalid(error: String) { /* no-op stub */ }
    fun checkBaseFiles(baseFilesMissing: Boolean): BaseAction =
        if (baseFilesMissing) BaseAction.Download else BaseAction.Skip
}
