package dev.starling.app

import android.content.Context
import java.io.File

/**
 * Avvio del server AI locale embedded (llama-server) usato dalla conversione
 * mod. Zero-configurazione: l'eseguibile e il modello vengono cercati prima
 * nello storage privato, poi negli asset `ai/`.
 *
 * Se il materiale non è presente l'avvio fallisce con un messaggio chiaro:
 * la conversione continua in fallback (sola libreria `mapping.bin`).
 */
object AiLocalServer {

    const val DEFAULT_SERVER_EXE = "llama-server"
    const val DEFAULT_MODEL = "smollm2-360m-instruct-q4_k_m.gguf"
    private const val AI_ASSET_DIR = "ai"

    fun startIfNeeded(ctx: Context, exePath: String, modelPath: String): Result<String> = runCatching {
        val exe = extractAssetIfNeeded(ctx, exePath, "$AI_ASSET_DIR/${fileName(exePath)}")
        val model = extractAssetIfNeeded(ctx, modelPath, "$AI_ASSET_DIR/${fileName(modelPath)}")

        if (!exe.exists()) {
            throw IllegalStateException("Eseguibile AI non trovato: ${exe.absolutePath}")
        }
        if (!model.exists()) {
            throw IllegalStateException("Modello AI non trovato: ${model.absolutePath}")
        }

        val process = ProcessBuilder(
            exe.absolutePath,
            "-m", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", "8080",
            "-c", "512",
        ).apply {
            directory(ctx.filesDir)
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
        }.start()

        "Server AI avviato (pid ${process.pid()}) su 127.0.0.1:8080"
    }

    private fun fileName(path: String): String =
        path.substringAfterLast('/').ifBlank { path }

    /** Copia un asset nello storage privato se il file di destinazione non esiste. */
    private fun extractAssetIfNeeded(ctx: Context, path: String, assetName: String): File {
        val dest = File(path)
        if (dest.exists()) return dest
        return try {
            ctx.assets.open(assetName).use { input ->
                dest.parentFile?.mkdirs()
                input.copyTo(dest.outputStream())
            }
            dest
        } catch (_: Exception) {
            dest // resta inesistente: il chiamante gestisce l'errore
        }
    }
}
