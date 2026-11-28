package dev.starling.packs

/**
 * Action returned by the base-files gate.
 */
sealed class BaseAction {
    object Download : BaseAction()
    object Skip : BaseAction()
}

/**
 * Anti-loop gate for external packs (compilation stub).
 */
class PackGate {
    fun onExternalPackValid() { /* no-op stub */ }
    fun onExternalPackInvalid(error: String) { /* no-op stub */ }
    fun checkBaseFiles(baseFilesMissing: Boolean): BaseAction =
        if (baseFilesMissing) BaseAction.Download else BaseAction.Skip
}
