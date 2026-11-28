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
 * Texture pack core importer (compilation stub).
 * The real logic will be implemented in the pack-importer module.
 */
class PackImporter {
    fun importZip(input: InputStream, dest: Path, name: String, packFormat: Int): Result {
        return Result.fail("PackImporter not implemented (stub)")
    }
}
