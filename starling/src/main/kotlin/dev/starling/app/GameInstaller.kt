package dev.starling.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// Stato del flusso "Gioca": verifica file → runtime Java → librerie/asset →
// avvio. La UI osserva questo stato e disegna la barra di avanzamento.
// ---------------------------------------------------------------------------
data class InstallState(
    val running: Boolean = false,
    val percent: Int = 0,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val phase: String = "",
    val detail: String = "",
    val error: String? = null,
    val ready: Boolean = false,
)

sealed interface InstallOutcome {
    data class Ready(val note: String, val runtimeLib: File) : InstallOutcome
    data class Failed(val message: String) : InstallOutcome
}

/**
 * Installer del gioco.
 *
 * Il launcher non spedisce i file di Minecraft nell'APK: al primo avvio
 * verifica l'installazione locale e, se è configurato un manifest di download,
 * scarica i file mancanti mostrando MB e percentuale reali.
 *
 * Manifest supportato (asset `game/manifest.json` oppure URL nelle
 * impostazioni, chiave `mc_manifest_url`):
 *
 * ```json
 * {
 *   "version": "26.3",
 *   "files": [
 *     { "path": "libraries/foo.jar", "url": "https://…/foo.jar", "size": 123456, "sha1": "…" }
 *   ]
 * }
 * ```
 *
 * Senza manifest l'installer lavora in modalità locale: verifica i file già
 * presenti sul dispositivo (runtime Java, bridge, librerie, asset) e prosegue
 * verso l'avvio.
 */
object GameInstaller {

    const val MC_VERSION = "26.3"

    private const val PREFS = "starling_settings"
    private const val KEY_MANIFEST_URL = "mc_manifest_url"
    private const val KEY_VERSION = "mc_version"
    private const val MANIFEST_ASSET = "game/manifest.json"
    private const val BUFFER = 64 * 1024

    private data class Entry(
        val path: String,
        val url: String,
        val size: Long,
        val sha1: String?,
    )

    private data class Manifest(val version: String, val files: List<Entry>)

    fun gameDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "minecraft")

    fun runtimeLib(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, "libjvm.so")

    /**
     * Runtime Java effettivo: quello impacchettato in jniLibs ha priorità,
     * altrimenti si usa quello eventualmente scaricato dal manifest in
     * `minecraft/runtime/libjvm.so`.
     */
    fun effectiveRuntimeLib(ctx: Context): File {
        val bundled = runtimeLib(ctx)
        if (bundled.exists()) return bundled
        return File(gameDir(ctx), "runtime/libjvm.so")
    }

    fun bridgeJar(ctx: Context): File =
        File(ctx.filesDir, "bridge/bridge-u8.jar")

    /** Versione di Minecraft selezionata nella Home (default: [MC_VERSION]). */
    fun selectedVersion(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VERSION, MC_VERSION) ?: MC_VERSION

    fun setSelectedVersion(ctx: Context, version: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VERSION, version).apply()
    }

    /** Versioni disponibili: quella di default + le cartelle in minecraft/versions. */
    fun availableVersions(ctx: Context): List<String> {
        val found = File(gameDir(ctx), "versions")
            .listFiles { file -> file.isDirectory }
            ?.map { it.name }
            ?: emptyList()
        return (listOf(MC_VERSION) + found).distinct().sorted()
    }

    fun manifestUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST_URL, "").orEmpty()

    fun setManifestUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MANIFEST_URL, url.trim()).apply()
    }

    /**
     * Esegue l'intera procedura. Bloccante: va chiamata su Dispatchers.IO.
     * [onUpdate] riceve ogni cambio di stato (thread di lavoro).
     */
    fun ensureInstalled(ctx: Context, onUpdate: (InstallState) -> Unit): InstallOutcome {
        val root = gameDir(ctx).apply { mkdirs() }
        val libsDir = File(root, "libraries").apply { mkdirs() }
        val assetsDir = File(root, "assets").apply { mkdirs() }
        val runtimeDir = File(root, "runtime").apply { mkdirs() }
        val version = selectedVersion(ctx)
        val versionDir = File(root, "versions/$version").apply { mkdirs() }
        val jre = effectiveRuntimeLib(ctx)

        fun emit(
            percent: Int,
            phase: String,
            detail: String,
            downloaded: Long = 0L,
            total: Long = 0L,
        ) {
            onUpdate(
                InstallState(
                    running = true,
                    percent = percent.coerceIn(0, 100),
                    downloaded = downloaded,
                    total = total,
                    phase = phase,
                    detail = detail,
                )
            )
        }

        return try {
            // --- 1. Verifica iniziale ------------------------------------
            emit(3, "Verifica file…", "Preparazione cartelle di gioco")
            beat()
            emit(
                8,
                "Runtime Java…",
                if (jre.exists()) {
                    "Runtime Java presente (${formatMb(jre.length())})"
                } else {
                    "Runtime Java non trovato: aggiungi libjvm.so in jniLibs/arm64-v8a"
                }
            )
            beat()

            val manifest = loadManifest(ctx)
            if (manifest != null) {
                downloadManifest(ctx, root, manifest, ::emit)
            } else {
                verifyLocal(ctx, root, libsDir, assetsDir, runtimeDir, versionDir, jre, version, ::emit)
            }

            // --- 2. Controllo finale -------------------------------------
            emit(97, "Verifica file…", "Controllo integrità completato")
            beat()
            emit(100, "Pronto", "Minecraft $version pronto all'avvio")

            InstallOutcome.Ready(
                note = if (manifest != null) {
                    "Manifest ${manifest.version} · ${manifest.files.size} file"
                } else {
                    "Installazione locale verificata"
                },
                runtimeLib = effectiveRuntimeLib(ctx),
            )
        } catch (t: Throwable) {
            InstallOutcome.Failed(t.message ?: "Errore durante la preparazione dei file")
        }
    }

    // -----------------------------------------------------------------------
    // Modalità manifest: download reale con progress in byte
    // -----------------------------------------------------------------------
    private fun downloadManifest(
        ctx: Context,
        root: File,
        manifest: Manifest,
        emit: (Int, String, String, Long, Long) -> Unit,
    ) {
        val total = manifest.files.sumOf { it.size }.coerceAtLeast(1L)
        var done = 0L

        emit(12, "Verifica file…", "Manifest ${manifest.version} · ${manifest.files.size} file", 0, total)

        manifest.files.forEachIndexed { index, entry ->
            val dest = File(root, entry.path)
            dest.parentFile?.mkdirs()

            val alreadyThere = dest.exists() &&
                (entry.size <= 0L || dest.length() == entry.size)
            if (alreadyThere) {
                done += dest.length()
                emit(
                    percentFor(done, total),
                    "Verifica file…",
                    "Già presente: ${dest.name} (${index + 1}/${manifest.files.size})",
                    done, total,
                )
                return@forEachIndexed
            }

            val phase = when {
                entry.path.startsWith("libraries") ->
                    "Scaricamento librerie e asset Minecraft ${manifest.version}…"
                entry.path.startsWith("assets") ->
                    "Scaricamento asset Minecraft ${manifest.version}…"
                entry.path.startsWith("runtime") ->
                    "Download runtime Java…"
                else -> "Download file di gioco…"
            }

            emit(
                percentFor(done, total),
                phase,
                "${index + 1}/${manifest.files.size} · ${dest.name}",
                done, total,
            )

            download(entry.url, dest) { chunk ->
                done += chunk
                emit(
                    percentFor(done, total),
                    phase,
                    "${index + 1}/${manifest.files.size} · ${dest.name}",
                    done, total,
                )
            }

            if (!entry.sha1.isNullOrBlank()) {
                val actual = sha1(dest)
                if (!actual.equals(entry.sha1, ignoreCase = true)) {
                    dest.delete()
                    throw IllegalStateException(
                        "Checksum non valido per ${dest.name}"
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Modalità locale: verifica dei file già presenti
    // -----------------------------------------------------------------------
    private fun verifyLocal(
        ctx: Context,
        root: File,
        libsDir: File,
        assetsDir: File,
        runtimeDir: File,
        versionDir: File,
        jre: File,
        version: String,
        emit: (Int, String, String, Long, Long) -> Unit,
    ) {
        val checks = listOf(
            Triple("Runtime Java", jre, "Download runtime Java…"),
            Triple("Bridge Java", bridgeJar(ctx), "Verifica bridge…"),
            Triple("Librerie", libsDir, "Scaricamento librerie Minecraft $version…"),
            Triple("Asset", assetsDir, "Scaricamento asset Minecraft $version…"),
            Triple("Versione $version", versionDir, "Verifica versione…"),
        )

        val total = checks.sumOf { (_, file, _) -> sizeOf(file) }.coerceAtLeast(1L)
        var done = 0L

        checks.forEachIndexed { index, (label, file, phase) ->
            val size = sizeOf(file)
            done += size
            val detail = if (file.exists()) {
                "$label: presente (${formatMb(size)})"
            } else {
                "$label: mancante — verrà creato all'avvio"
            }
            emit(
                12 + (78 * (index + 1) / checks.size),
                phase,
                detail,
                done, total,
            )
            beat()
        }

        // Directory di lavoro sempre disponibili.
        libsDir.mkdirs()
        assetsDir.mkdirs()
        runtimeDir.mkdirs()
        versionDir.mkdirs()
        File(root, "mods").mkdirs()
    }

    // -----------------------------------------------------------------------
    // Manifest
    // -----------------------------------------------------------------------
    private fun loadManifest(ctx: Context): Manifest? {
        val url = manifestUrl(ctx)
        val json = if (url.isNotBlank()) {
            runCatching { readUrl(url) }.getOrNull()
        } else {
            runCatching { ctx.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() } }
                .getOrNull()
        } ?: return null

        return runCatching {
            val obj = JSONObject(json)
            val filesArray = obj.optJSONArray("files") ?: return null
            val files = buildList {
                for (i in 0 until filesArray.length()) {
                    val item = filesArray.optJSONObject(i) ?: continue
                    val path = item.optString("path")
                    val fileUrl = item.optString("url")
                    if (path.isBlank() || fileUrl.isBlank()) continue
                    add(
                        Entry(
                            path = path,
                            url = fileUrl,
                            size = item.optLong("size", 0L),
                            sha1 = item.optString("sha1").ifBlank { null },
                        )
                    )
                }
            }
            Manifest(obj.optString("version", MC_VERSION), files)
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // HTTP / IO
    // -----------------------------------------------------------------------
    private fun download(url: String, dest: File, onChunk: (Long) -> Unit) {
        val parent = dest.parentFile ?: dest.absoluteFile.parentFile
        requireNotNull(parent) { "Percorso di destinazione senza cartella: ${dest.path}" }
        parent.mkdirs()
        val tmp = File(parent, dest.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code su ${dest.name}")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        onChunk(read.toLong())
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun readUrl(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} sul manifest")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else -> file.length()
    }

    private fun percentFor(done: Long, total: Long): Int =
        12 + ((done.toDouble() / total.toDouble()) * 78.0).toInt().coerceIn(0, 78)

    /** Piccola pausa per rendere leggibili le fasi istantanee (UX). */
    private fun beat() {
        try {
            Thread.sleep(90)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
