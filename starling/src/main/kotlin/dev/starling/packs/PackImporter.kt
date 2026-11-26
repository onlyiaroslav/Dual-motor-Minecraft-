package dev.starling.packs

import java.io.InputStream
import java.nio.file.Path

/**
 * Risultato dell'importazione di un pack.
 */
data class Result(
    val ok: Boolean,
    val error: String? = null,
    val packDir: Path? = null
) {
    companion object {
        fun fail(error: String): Result = Result(ok = false, error = error)
    }
}

/**
 * Importatore core di texture pack (stub per compilazione).
 * La logica reale è da implementare nel modulo pack-importer.
 */
class PackImporter {
    fun importZip(input: InputStream, dest: Path, name: String, packFormat: Int): Result {
        return Result.fail("PackImporter non implementato (stub)")
    }
}
