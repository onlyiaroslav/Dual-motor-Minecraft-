package dev.starling.app

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Gestione delle mod installate.
 *
 * Le mod attive stanno in `mods/`, quelle disattivate in `mods/.disabled/`:
 * disattivare una mod la sposta, senza cancellarla. L'"aggiornamento" di una
 * mod passa dal convertitore nativo ([dev.starling.converter.AiConverterBridge]),
 * che rigenera `mapping.bin` e il log di conversione.
 */
object ModManager {

    data class ModEntry(val file: File, val enabled: Boolean) {
        val name: String get() = file.name
        val size: Long get() = file.length()
    }

    fun modsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "mods").apply { mkdirs() }

    fun disabledDir(ctx: Context): File =
        File(modsDir(ctx), ".disabled").apply { mkdirs() }

    fun outDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "conversion").apply { mkdirs() }

    fun libraryPath(ctx: Context): String {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val library = File(base, "mapping.bin")
        return if (library.exists()) library.absolutePath else ""
    }

    /** Elenco completo: prima le attive, poi le disattivate, in ordine alfabetico. */
    fun list(ctx: Context): List<ModEntry> {
        val active = jars(modsDir(ctx)).map { ModEntry(it, true) }
        val disabled = jars(disabledDir(ctx)).map { ModEntry(it, false) }
        return (active + disabled).sortedBy { it.name.lowercase() }
    }

    private fun jars(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.extension.equals("jar", true) }
            ?.toList() ?: emptyList()

    /** Attiva/disattiva una mod spostandola tra le due cartelle. */
    fun setEnabled(ctx: Context, entry: ModEntry, enabled: Boolean): Result<File> = runCatching {
        if (entry.enabled == enabled) return@runCatching entry.file
        val targetDir = if (enabled) modsDir(ctx) else disabledDir(ctx)
        val target = File(targetDir, entry.file.name)
        if (!entry.file.renameTo(target)) {
            entry.file.copyTo(target, overwrite = true)
            entry.file.delete()
        }
        target
    }

    fun delete(entry: ModEntry): Result<Unit> = runCatching {
        if (!entry.file.delete()) {
            throw IllegalStateException("Impossibile eliminare ${entry.file.name}")
        }
    }

    /** Copia un .jar scelto con il document picker nella cartella mods. */
    fun import(ctx: Context, uri: Uri): Result<File> = runCatching {
        val name = displayName(ctx, uri)
        if (!name.endsWith(".jar", ignoreCase = true)) {
            throw IllegalStateException("Il file selezionato non è un .jar")
        }
        val dest = File(modsDir(ctx), name)
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Impossibile leggere il file selezionato" }
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        val fromQuery = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return fromQuery ?: uri.lastPathSegment?.substringAfterLast('/') ?: "mod.jar"
    }
}
