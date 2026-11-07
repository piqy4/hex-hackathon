package com.hex.stenographer.models

import android.content.Context
import android.content.res.AssetManager
import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

sealed class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The weights are neither bundled in the APK nor sideloaded. */
    class Missing(val name: String) : ModelException("Model $name is not available")

    /** The installed or sideloaded file does not match the checksum from the build. */
    class Corrupted(val name: String) : ModelException("Model $name failed checksum verification")

    class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) :
        ModelException("Need $requiredBytes bytes for models, $availableBytes available")

    class Io(name: String, cause: IOException) : ModelException("I/O error while installing $name", cause)
}

/**
 * Installs model weights into private storage and hands out plain file paths for native runtimes.
 *
 * Sources, in order:
 * 1. `filesDir/models/<name>` already installed and verified (checksum marker, no re-hash);
 * 2. `getExternalFilesDir("models")/<name>` sideloaded with `adb push` when weights ship separately
 *    from the APK (also how a non-bundled Whisper size is added for comparison runs);
 * 3. APK assets `models/<name>` (stored uncompressed).
 *
 * Every copy is streamed, hashed on the fly, written to a temp file and atomically renamed, so an
 * interrupted install never leaves a half-written model behind. Expected checksums (and sizes of
 * bundled files) come from `assets/models/manifest.properties`, generated from `models/SHA256SUMS`.
 */
class ModelStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val assets: AssetManager = appContext.assets
    private val installDir = File(appContext.filesDir, MODELS_DIR)
    private val mutex = Mutex()

    private val manifest: Map<String, Entry> by lazy { loadManifest() }

    /** Whether [names] are installed or can be installed from the APK or a sideloaded copy. */
    suspend fun isAvailable(vararg names: String): Boolean = withContext(dispatcher) {
        names.all { name ->
            val entry = manifest[name] ?: return@all false
            isInstalled(name, entry) || externalFile(name) != null || assetExists(name)
        }
    }

    /**
     * Ensures [names] are installed and returns their files in the same order.
     * [onProgress] receives the copied fraction of the bytes still to install.
     */
    suspend fun require(vararg names: String, onProgress: (Float) -> Unit = {}): List<File> =
        withContext(dispatcher) {
            mutex.withLock {
                installDir.mkdirs()
                val entries = names.map { name -> name to (manifest[name] ?: throw ModelException.Missing(name)) }
                val pending = entries.filterNot { (name, entry) -> isInstalled(name, entry) }
                if (pending.isNotEmpty()) install(pending, onProgress)
                entries.map { (name, _) -> File(installDir, name) }
            }
        }

    /** Removes installed files that the current build no longer references, plus stale temp files. */
    suspend fun prune() = withContext(dispatcher) {
        mutex.withLock {
            installDir.listFiles()?.forEach { file ->
                val model = file.name.removeSuffix(MARKER_SUFFIX)
                if (file.name.endsWith(TEMP_SUFFIX) || model !in manifest) file.delete()
            }
        }
    }

    private fun isInstalled(name: String, entry: Entry): Boolean {
        val file = File(installDir, name)
        val marker = File(installDir, name + MARKER_SUFFIX)
        if (!file.isFile || !marker.isFile || marker.readText() != entry.sha256) return false
        return entry.size == null || file.length() == entry.size
    }

    private suspend fun install(pending: List<Pair<String, Entry>>, onProgress: (Float) -> Unit) {
        val sources = ArrayList<Source>(pending.size)
        try {
            pending.mapTo(sources) { (name, _) -> openSource(name) }
            val total = sources.sumOf { it.length }
            val available = allocatableBytes()
            if (available < total + FREE_SPACE_MARGIN) {
                throw ModelException.InsufficientStorage(total + FREE_SPACE_MARGIN, available)
            }
            var copiedBefore = 0L
            pending.forEachIndexed { i, (name, entry) ->
                val source = sources[i]
                copyVerified(name, entry, source) { copied ->
                    if (total > 0) onProgress((copiedBefore + copied).toFloat() / total)
                }
                copiedBefore += source.length
            }
            onProgress(1f)
        } finally {
            sources.forEach { it.close() }
        }
    }

    private suspend fun copyVerified(name: String, entry: Entry, source: Source, onBytes: (Long) -> Unit) {
        val target = File(installDir, name)
        val marker = File(installDir, name + MARKER_SUFFIX)
        val temp = File(installDir, name + TEMP_SUFFIX)
        marker.delete()
        try {
            val actual = copyAndHash(source.stream, temp, onBytes)
            val sizeOk = entry.size == null || temp.length() == entry.size
            if (!sizeOk || actual != entry.sha256) throw ModelException.Corrupted(name)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Cannot move $temp to $target")
            }
            marker.writeText(entry.sha256)
        } catch (e: IOException) {
            throw ModelException.Io(name, e)
        } finally {
            temp.delete()
        }
    }

    /** Free space including cache the system can reclaim for us, unlike [File.getUsableSpace]. */
    private fun allocatableBytes(): Long = try {
        val storage = appContext.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(installDir))
    } catch (_: IOException) {
        installDir.usableSpace
    }

    private class Source(val stream: InputStream, val length: Long) : Closeable {
        override fun close() = stream.close()
    }

    private fun externalFile(name: String): File? =
        appContext.getExternalFilesDir(MODELS_DIR)?.resolve(name)?.takeIf { it.isFile }

    private fun assetExists(name: String): Boolean = assets.list(MODELS_DIR)?.contains(name) == true

    private fun openSource(name: String): Source {
        externalFile(name)?.let { return Source(it.inputStream(), it.length()) }
        return try {
            // Uncompressed assets expose their length; fall back to "unknown" for compressed ones.
            val length = try {
                assets.openFd("$MODELS_DIR/$name").use { it.length }
            } catch (_: IOException) {
                0L
            }
            Source(assets.open("$MODELS_DIR/$name", AssetManager.ACCESS_STREAMING), length)
        } catch (_: FileNotFoundException) {
            throw ModelException.Missing(name)
        }
    }

    private suspend fun copyAndHash(input: InputStream, dst: File, onBytes: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER)
        var copied = 0L
        dst.outputStream().use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                copied += n
                onBytes(copied)
            }
            out.fd.sync()
        }
        return digest.digest().toHex()
    }

    private fun loadManifest(): Map<String, Entry> = try {
        assets.open("$MODELS_DIR/$MANIFEST").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val name = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "")
                val size = value.substringBefore(':').toLongOrNull()
                val sha = value.substringAfter(':', "").trim().lowercase()
                if (name.isEmpty() || size == null || sha.length != 64) {
                    null
                } else {
                    name to Entry(size.takeIf { it >= 0 }, sha)
                }
            }.toMap()
        }
    } catch (_: FileNotFoundException) {
        emptyMap()
    }

    /** [size] is `null` for weights known by checksum only (not bundled into this APK). */
    private data class Entry(val size: Long?, val sha256: String)

    private companion object {
        const val MODELS_DIR = "models"
        const val MANIFEST = "manifest.properties"
        const val MARKER_SUFFIX = ".sha256"
        const val TEMP_SUFFIX = ".part"
        const val COPY_BUFFER = 256 * 1024
        const val FREE_SPACE_MARGIN = 50L * 1024 * 1024

        private val HEX = "0123456789abcdef".toCharArray()

        fun ByteArray.toHex(): String {
            val chars = CharArray(size * 2)
            for (i in indices) {
                val v = this[i].toInt() and 0xFF
                chars[2 * i] = HEX[v ushr 4]
                chars[2 * i + 1] = HEX[v and 0x0F]
            }
            return String(chars)
        }
    }
}
