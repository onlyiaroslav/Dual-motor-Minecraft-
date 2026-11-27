package dev.starling.packs

import org.json.JSONObject
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

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
 * Importatore core di texture pack.
 *
 * Estrae lo zip nella sandbox privata con protezioni:
 *  - zip-slip: ogni entry deve restare dentro la cartella di destinazione;
 *  - limite di dimensione (singolo file e totale) per evitare zip bomb;
 *  - `pack.mcmeta` generato se assente, corretto se manca `pack_format`.
 *
 * Se lo zip contiene una sola cartella radice (caso comune), quella diventa
 * la directory del pack.
 *
 * Solo API stabili: java.nio.file.Files + java.util.zip (niente kotlin.io.path
 * sperimentali, che richiederebbero opt-in e romperebbero la build).
 */
class PackImporter {

    companion object {
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024   // 64 MB per file
        private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024  // 512 MB totali
        private const val MAX_ENTRIES = 20_000
    }

    fun importZip(input: InputStream, dest: Path, name: String, packFormat: Int): Result {
        val packName = sanitizeName(name)
        if (packName.isBlank()) return Result.fail("nome del pack non valido")

        val destNorm = dest.normalize()
        val target = destNorm.resolve(packName).normalize()
        if (!target.startsWith(destNorm)) {
            return Result.fail("percorso di destinazione non valido")
        }

        return try {
            Files.createDirectories(destNorm)
            if (Files.exists(target)) target.toFile().deleteRecursively()
            Files.createDirectories(target)

            var total = 0L
            var entries = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) {
                        throw IllegalStateException("zip con troppe entry (> $MAX_ENTRIES)")
                    }

                    val entryPath = target.resolve(entry.name).normalize()
                    // zip-slip: l'entry non deve uscire dalla cartella del pack
                    if (!entryPath.startsWith(target)) {
                        throw IllegalStateException("entry zip fuori percorso: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        Files.createDirectories(entryPath)
                    } else {
                        entryPath.parent?.let { Files.createDirectories(it) }
                        var written = 0L
                        Files.newOutputStream(entryPath).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                written += read
                                total += read
                                if (written > MAX_ENTRY_BYTES) {
                                    throw IllegalStateException("file troppo grande: ${entry.name}")
                                }
                                if (total > MAX_TOTAL_BYTES) {
                                    throw IllegalStateException("pack troppo grande (> 512 MB)")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            if (entries == 0) return Result.fail("lo zip è vuoto")

            // Cartella radice unica: se contiene pack.mcmeta o assets, è il pack.
            val packRoot = resolvePackRoot(target)
            ensureMcmeta(packRoot, packName, packFormat)

            Result(ok = true, packDir = packRoot)
        } catch (e: Exception) {
            runCatching { if (Files.exists(target)) target.toFile().deleteRecursively() }
            Result.fail(e.message ?: "estrazione fallita")
        }
    }

    /** Nome sicuro per la cartella del pack (niente path traversal). */
    private fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".zip").removeSuffix(".ZIP")
        return base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(64)
    }

    /** Se c'è una sola sottocartella con pack.mcmeta/assets, usa quella. */
    private fun resolvePackRoot(target: Path): Path {
        if (Files.exists(target.resolve("pack.mcmeta")) || Files.exists(target.resolve("assets"))) {
            return target
        }
        val children = ArrayList<Path>()
        Files.newDirectoryStream(target).use { stream ->
            for (child in stream) {
                if (Files.isDirectory(child)) children.add(child)
            }
        }
        if (children.size == 1) {
            val child = children[0]
            if (Files.exists(child.resolve("pack.mcmeta")) || Files.exists(child.resolve("assets"))) {
                return child
            }
        }
        return target
    }

    /** Genera pack.mcmeta se assente; corregge pack_format se mancante. */
    private fun ensureMcmeta(packRoot: Path, packName: String, packFormat: Int) {
        val mcmeta = packRoot.resolve("pack.mcmeta")

        fun writeFresh() {
            val pack = JSONObject()
            pack.put("pack_format", packFormat)
            pack.put("description", packName)
            val json = JSONObject()
            json.put("pack", pack)
            Files.write(mcmeta, json.toString(2).toByteArray())
        }

        if (!Files.exists(mcmeta)) {
            writeFresh()
            return
        }

        val current = runCatching {
            JSONObject(String(Files.readAllBytes(mcmeta)))
        }.getOrNull()

        if (current == null) {
            // File corrotto: rigenerato, il pack resta utilizzabile.
            writeFresh()
            return
        }

        val pack = current.optJSONObject("pack") ?: JSONObject().also { current.put("pack", it) }
        if (!pack.has("pack_format")) pack.put("pack_format", packFormat)
        if (!pack.has("description")) pack.put("description", packName)
        Files.write(mcmeta, current.toString(2).toByteArray())
    }
}
