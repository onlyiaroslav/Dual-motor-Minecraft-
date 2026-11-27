package dev.starling.app

import android.content.Context
import android.system.Os
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Runtime Java (JRE) nella **memoria privata** dell'app.
 *
 * Perché privato: da Android 10+ le librerie caricate con dlopen non possono
 * stare nello storage esterno (/storage/emulated/0/Android/data/...). Il JRE
 * viene quindi estratto in `context.filesDir/runtime/` e caricato da lì con
 * percorso assoluto.
 *
 * libjvm.so da solo non basta: servono libjli.so, libjava.so, libnet.so, ecc.
 * [prepareEnv] imposta JAVA_HOME / LD_LIBRARY_PATH e il native host precarica
 * le librerie sorelle per percorso assoluto prima di dlopen(libjvm.so).
 *
 * Archivi supportati: .zip, .tar.xz, .tar.gz (rilevati dai magic bytes).
 */
object JreInstaller {

    /**
     * JRE 17 arm64 di PojavLauncher: la build più testata per Minecraft Java
     * su Android. Link diretto all'asset .tar.xz (nessuna pagina di release).
     */
    const val DEFAULT_JRE_URL =
        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/" +
            "releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"

    const val DEFAULT_JRE_NAME = "jre17-arm64-20210825-release.tar.xz"

    /** Directory privata del runtime: /data/data/<pkg>/files/runtime. */
    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "runtime")

    /** libjvm.so estratto, oppure null se il runtime non è installato. */
    fun findLibjvm(ctx: Context): File? {
        val root = runtimeDir(ctx)
        if (!root.isDirectory) return null
        val all = root.walkTopDown().filter { it.isFile && it.name == "libjvm.so" }.toList()
        // Layout JRE standard: <home>/lib/server/libjvm.so ha la precedenza.
        return all.firstOrNull { it.parentFile?.name == "server" } ?: all.firstOrNull()
    }

    fun isReady(ctx: Context): Boolean = findLibjvm(ctx)?.exists() == true

    /** java.home ricavato dalla posizione di libjvm.so (<home>/lib/server/). */
    fun javaHome(ctx: Context): File {
        val jvm = findLibjvm(ctx) ?: return runtimeDir(ctx)
        val serverDir = jvm.parentFile                 // .../lib/server
        val libDir = serverDir?.parentFile             // .../lib
        return libDir?.parentFile ?: runtimeDir(ctx)   // .../<home>
    }

    /**
     * Imposta l'ambiente PRIMA del dlopen e restituisce il percorso assoluto
     * della copia privata di libjvm.so.
     */
    fun prepareEnv(ctx: Context): Result<String> = runCatching {
        val jvm = findLibjvm(ctx)
            ?: throw IllegalStateException(
                "Runtime Java non installato: carica un archivio JRE (.tar.xz o .zip)"
            )
        val home = javaHome(ctx)
        val root = runtimeDir(ctx)

        val libPaths = listOf(
            File(home, "lib"),
            File(home, "lib/server"),
            File(home, "lib/jli"),
            File(root, "lib"),
            File(root, "lib/server"),
            File(root, "lib/jli"),
            jvm.parentFile,
        ).filter { it.isDirectory }.map { it.absolutePath }.distinct()

        val previous = System.getenv("LD_LIBRARY_PATH").orEmpty()
        val merged = (libPaths + previous.split(':').filter { it.isNotBlank() })
            .distinct().joinToString(":")

        Os.setenv("JAVA_HOME", home.absolutePath, true)
        Os.setenv("LD_LIBRARY_PATH", merged, true)
        // Usate dal native host per precaricare le librerie sorelle.
        Os.setenv("STARLING_JRE_HOME", home.absolutePath, true)
        Os.setenv("STARLING_JRE_LIB", jvm.parentFile?.absolutePath ?: home.absolutePath, true)

        jvm.absolutePath
    }

    /**
     * Estrae un archivio JRE nella memoria privata. Rileva il formato dai
     * magic bytes, quindi funziona anche se il file picker non dà un nome utile.
     */
    fun install(ctx: Context, input: InputStream, displayName: String): Result<File> = runCatching {
        val root = runtimeDir(ctx)
        root.mkdirs()

        // BufferedInputStream con mark supportato per leggere i magic bytes.
        val buffered = input.buffered(64 * 1024)
        buffered.mark(8)
        val magic = ByteArray(6)
        val read = buffered.read(magic)
        buffered.reset()
        if (read < 2) throw IllegalStateException("archivio vuoto o illeggibile")

        val isZip = magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        val isXz = magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
            magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte()
        val isGzip = magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()

        val lower = displayName.lowercase()
        when {
            isZip || lower.endsWith(".zip") -> extractZip(buffered, root)
            isXz || lower.endsWith(".tar.xz") || lower.endsWith(".txz") ->
                extractTar(XZInputStream(buffered), root)
            isGzip || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                extractTar(GZIPInputStream(buffered), root)
            lower.endsWith(".tar") -> extractTar(buffered, root)
            else -> throw IllegalStateException(
                "Formato non riconosciuto: serve un archivio .tar.xz, .tar.gz o .zip del JRE"
            )
        }

        val jvm = findLibjvm(ctx)
            ?: throw IllegalStateException(
                "Archivio estratto ma libjvm.so non trovato: non è un JRE valido"
            )
        jvm
    }

    /** Scarica un archivio JRE da URL e lo installa, con avanzamento. */
    fun downloadAndInstall(
        ctx: Context,
        url: String,
        displayName: String,
        onUpdate: (InstallState) -> Unit,
    ): Result<File> = runCatching {
        require(url.isNotBlank()) { "Inserisci l'URL del JRE" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code scaricando il JRE")
        }
        val total = conn.contentLengthLong.coerceAtLeast(0L)

        var done = 0L
        var lastEmit = 0L
        fun emit(running: Boolean) {
            onUpdate(
                InstallState(
                    running = running,
                    percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0,
                    downloaded = done,
                    total = total,
                    phase = if (running) "Download runtime Java…" else "Runtime Java pronto",
                    detail = displayName,
                    ready = !running,
                )
            )
        }

        fun maybeEmit() {
            if (done - lastEmit >= 256 * 1024) {
                lastEmit = done
                emit(true)
            }
        }

        emit(true)
        val counting = object : InputStream() {
            private val src = conn.inputStream
            override fun read(): Int {
                val v = src.read()
                if (v >= 0) { done++; maybeEmit() }
                return v
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = src.read(b, off, len)
                if (n > 0) { done += n; maybeEmit() }
                return n
            }
            override fun close() = src.close()
        }

        val result = counting.use { install(ctx, it, displayName) }
        conn.disconnect()
        done = total.coerceAtLeast(done)
        emit(false)
        result.getOrThrow()
    }

    // -----------------------------------------------------------------------
    // ZIP
    // -----------------------------------------------------------------------
    private fun extractZip(input: InputStream, root: File) {
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw IllegalStateException("archivio con troppe entry")
                val name = normalize(entry.name)
                if (name.isNotBlank() && !isJunk(name)) {
                    val dest = safeResolve(root, name)
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entries == 0) throw IllegalStateException("lo zip è vuoto")
    }

    // -----------------------------------------------------------------------
    // TAR (usato per .tar.xz e .tar.gz)
    // -----------------------------------------------------------------------
    private fun extractTar(input: InputStream, root: File) {
        val header = ByteArray(512)
        var entries = 0
        var longName: String? = null
        var paxPath: String? = null
        var zeroBlocks = 0

        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) {
                zeroBlocks++
                if (zeroBlocks >= 2) break
                continue
            }
            zeroBlocks = 0

            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val rawName = cString(header, 0, 100)
            val prefix = cString(header, 345, 155)
            val padded = ((size + 511) / 512) * 512

            when (type) {
                'L' -> { // GNU long name
                    val data = readBytes(input, size, padded)
                    longName = String(data).trimEnd('\u0000').trim()
                }
                'x', 'g' -> { // pax extended header
                    val data = readBytes(input, size, padded)
                    paxPath = parsePaxPath(String(data)) ?: paxPath
                }
                '5' -> {
                    val name = normalize(longName ?: paxPath ?: joinPrefix(prefix, rawName))
                    if (name.isNotBlank() && !isJunk(name)) safeResolve(root, name).mkdirs()
                    longName = null; paxPath = null
                }
                '0', '\u0000', '7' -> {
                    val name = normalize(longName ?: paxPath ?: joinPrefix(prefix, rawName))
                    longName = null; paxPath = null
                    if (name.isNotBlank() && !isJunk(name)) {
                        val dest = safeResolve(root, name)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out ->
                            var remaining = size
                            val buffer = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val want = minOf(buffer.size.toLong(), remaining).toInt()
                                val n = input.read(buffer, 0, want)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        skipFully(input, padded - size)
                    } else {
                        skipFully(input, padded)
                    }
                    entries++
                    if (entries > MAX_ENTRIES) throw IllegalStateException("archivio con troppe entry")
                }
                else -> skipFully(input, padded) // symlink, device, ecc.: ignorati
            }
        }
        if (entries == 0) throw IllegalStateException("l'archivio tar non contiene file")
    }

    private fun parsePaxPath(data: String): String? {
        // formato: "<len> <key>=<value>\n"
        for (line in data.split('\n')) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).substringAfterLast(' ').trim()
            if (key == "path") return line.substring(eq + 1)
        }
        return null
    }

    private fun joinPrefix(prefix: String, name: String): String =
        if (prefix.isBlank()) name else "$prefix/$name"

    private fun parseOctal(block: ByteArray, offset: Int, length: Int): Long {
        // GNU base-256: primo byte con bit alto set → valore binario.
        if (block[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in offset + 1 until offset + length) {
                value = (value shl 8) or (block[i].toLong() and 0xFF)
            }
            return value
        }
        val text = cString(block, offset, length).trim()
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: 0L
    }

    private fun cString(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long, padded: Long): ByteArray {
        val data = ByteArray(size.coerceAtMost(MAX_ENTRY_BYTES).toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n <= 0) break
            off += n
        }
        skipFully(input, padded - size)
        return data
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, want)
            if (n <= 0) break
            remaining -= n
        }
    }

    // -----------------------------------------------------------------------
    // Sicurezza percorsi
    // -----------------------------------------------------------------------
    private fun normalize(raw: String): String =
        raw.replace('\\', '/').removePrefix("/").removePrefix("./").trim()

    private fun isJunk(name: String): Boolean =
        name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")

    private fun safeResolve(root: File, name: String): File {
        val dest = File(root, name)
        val rootPath = root.canonicalPath
        if (!dest.canonicalPath.startsWith(rootPath + File.separator)) {
            throw IllegalStateException("entry fuori dalla cartella privata: $name")
        }
        return dest
    }

    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
}
