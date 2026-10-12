package com.walnutgeek.stsloop.core.models

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Why [ModelStore.install] stopped. Every reason leaves the store consistent: run it again to carry on. */
class ModelInstallException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** No response, an HTTP error, or a dropped connection. The partial download is kept. */
        NETWORK,

        /** A download or an extracted file did not match its pinned size or SHA-256. It is deleted. */
        INTEGRITY,

        /** Too little free space to start, or a write failed. */
        STORAGE,

        /** The caller asked to stop. The partial download is kept. */
        CANCELLED,
    }
}

data class InstallProgress(val phase: Phase, val done: Long, val total: Long) {
    enum class Phase { DOWNLOADING, VERIFYING, UNPACKING }
}

/**
 * The models of a [ModelManifest], installed under [root], a directory of
 * their own: anything else in it is deleted.
 *
 * A model file is installed when it exists at its path with its pinned size.
 * Only a file whose SHA-256 matched its pin is ever moved there, by an atomic
 * rename, so a crash or a kill leaves a file either whole or absent, and the
 * check at every start is cheap. Downloads go to `.downloads/<name>.part` and
 * resume from its length with an HTTP Range request; a verified download is
 * renamed to `.downloads/<name>` and deleted once everything it yields is
 * installed.
 */
class ModelStore(
    val root: File,
    val manifest: ModelManifest,
    private val http: RangeHttp = UrlConnectionHttp(),
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    private val downloads = File(root, DOWNLOADS)
    private val staging = File(downloads, STAGING)

    fun fileOf(f: ModelFile) = File(root, f.path)

    fun isInstalled(f: ModelFile): Boolean = fileOf(f).let { it.isFile && it.length() == f.size }

    fun missing(): List<ModelFile> = manifest.files.filterNot(::isInstalled)

    val isReady: Boolean get() = missing().isEmpty()

    /** Bytes still to fetch: a partial download counts what it already has. */
    fun pendingDownloadBytes(): Long = pending().sumOf { it.size - have(it) }

    /** The free space [install] asks for: what is left to fetch, plus room to unpack archives. */
    fun bytesNeeded(): Long = pendingDownloadBytes() + pending().filterIsInstance<ModelDownload.TarBz2>()
        .sumOf { d -> d.files.filterNot(::isInstalled).sumOf { it.size } }

    /**
     * Fetch, verify and install whatever is missing; a no-op when [isReady].
     * Blocking: run it off the main thread. Throws [ModelInstallException].
     * [cancelled] is polled between reads.
     */
    fun install(onProgress: (InstallProgress) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        if (!root.isDirectory && !root.mkdirs()) throw storage("cannot create $root")
        prune()
        val pending = pending()
        if (pending.isEmpty()) return
        val need = bytesNeeded()
        val free = freeBytes(root)
        if (need > free) {
            throw ModelInstallException(
                ModelInstallException.Reason.STORAGE,
                "Not enough storage: the speech models need ${mb(need)} free, and ${mb(free)} is.",
            )
        }
        val total = pending.sumOf { it.size }
        var before = 0L
        for (d in pending) {
            if (!fetched(d).isFile) {
                fetch(d, cancelled) { n -> onProgress(InstallProgress(InstallProgress.Phase.DOWNLOADING, before + n, total)) }
                onProgress(InstallProgress(InstallProgress.Phase.VERIFYING, 0, d.size))
                verifyDownload(d)
            }
            before += d.size
            onProgress(InstallProgress(InstallProgress.Phase.DOWNLOADING, before, total))
            when (d) {
                is ModelDownload.Single -> move(fetched(d), fileOf(d.file))
                is ModelDownload.TarBz2 -> unpack(d, onProgress, cancelled)
            }
            if (d.files.all(::isInstalled)) fetched(d).delete()
        }
        prune()
    }

    /**
     * Delete whatever under [root] this manifest does not account for: older
     * models, stray staging, and downloads whose files are all installed.
     */
    fun prune() {
        if (!root.isDirectory) return
        val keep = manifest.files.map(::fileOf).toMutableSet()
        for (d in pending()) {
            keep += fetched(d)
            keep += part(d)
        }
        val kept = keep.map { it.absoluteFile }.toSet()
        for (f in root.walkBottomUp()) {
            if (f == root) continue
            when {
                f.isDirectory -> if (f.list().isNullOrEmpty()) f.delete()
                f.absoluteFile !in kept -> f.delete()
            }
        }
    }

    private fun pending() = manifest.downloads.filter { d -> d.files.any { !isInstalled(it) } }

    private fun fetched(d: ModelDownload) = File(downloads, d.name)
    private fun part(d: ModelDownload) = File(downloads, "${d.name}.part")

    /** Bytes of [d] already on disk: all of them once verified, else the usable part of the partial download. */
    private fun have(d: ModelDownload): Long {
        if (fetched(d).isFile) return d.size
        val n = part(d).takeIf { it.isFile }?.length() ?: 0
        return if (n <= d.size) n else 0
    }

    /** Download [d] into its `.part` until it has [ModelDownload.size] bytes, resuming what is there. */
    private fun fetch(d: ModelDownload, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        if (!downloads.isDirectory && !downloads.mkdirs()) throw storage("cannot create $downloads")
        val part = part(d)
        var restarts = 0
        while (true) {
            var have = have(d)
            if (have == 0L) part.delete()
            if (have == d.size) return
            val response = try {
                http.get(d.url, have)
            } catch (e: IOException) {
                throw network("cannot reach ${host(d.url)}: ${e.message}", e)
            }
            response.use { r ->
                when {
                    r.status == 206 && r.rangeStart == have -> Unit
                    r.status == 200 -> have = 0 // the server sent it all again: start over
                    // 416: the server's file is not what we have half of. A 206 from elsewhere
                    // than asked (or not saying where): its bytes cannot be appended. Start over, once.
                    (r.status == 416 || r.status == 206) && restarts++ == 0 -> {
                        part.delete()
                        return@use
                    }
                    else -> throw network("HTTP ${r.status} for ${d.url}")
                }
                FileOutputStream(part, have > 0).use { out ->
                    have = copy(r.body, out, have, d, cancelled, progress)
                }
            }
            if (part.length() == d.size) return
            if (part.isFile) throw network("download of ${d.name} stopped at ${part.length()} of ${d.size} bytes")
        }
    }

    private fun copy(
        body: InputStream,
        out: FileOutputStream,
        start: Long,
        d: ModelDownload,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
    ): Long {
        val buf = ByteArray(BUFFER)
        var have = start
        progress(have)
        while (true) {
            if (cancelled()) throw ModelInstallException(ModelInstallException.Reason.CANCELLED, "download of ${d.name} cancelled")
            val n = try {
                body.read(buf)
            } catch (e: IOException) {
                throw network("download of ${d.name} interrupted at $have of ${d.size} bytes", e)
            }
            if (n < 0) return have
            if (have + n > d.size) {
                out.close()
                part(d).delete()
                throw integrity("${d.url} is longer than the pinned ${d.size} bytes")
            }
            try {
                out.write(buf, 0, n)
            } catch (e: IOException) {
                throw storage("cannot write ${d.name}: ${e.message}", e)
            }
            have += n
            progress(have)
        }
    }

    private fun verifyDownload(d: ModelDownload) {
        val part = part(d)
        val actual = sha256(part)
        if (actual != d.sha256) {
            part.delete()
            throw integrity("${d.name} does not match its pinned SHA-256 (got $actual); deleted")
        }
        move(part, fetched(d))
    }

    /** Extract the missing pinned members of [d], each verified in staging before it is moved into place. */
    private fun unpack(d: ModelDownload.TarBz2, onProgress: (InstallProgress) -> Unit, cancelled: () -> Boolean) {
        val wanted = d.files.filterNot(::isInstalled).associateBy { it.path }.toMutableMap()
        val total = wanted.values.sumOf { it.size }
        var done = 0L
        onProgress(InstallProgress(InstallProgress.Phase.UNPACKING, 0, total))
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(fetched(d).inputStream(), BUFFER))).use { tar ->
                while (wanted.isNotEmpty()) {
                    val entry = tar.nextEntry ?: break
                    val f = wanted[entry.name.removePrefix("./")] ?: continue
                    if (!entry.isFile) continue
                    val staged = File(staging, f.path)
                    staged.parentFile.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(BUFFER)
                    var n = 0L
                    FileOutputStream(staged).use { out ->
                        while (true) {
                            if (cancelled()) throw ModelInstallException(ModelInstallException.Reason.CANCELLED, "unpacking cancelled")
                            val r = tar.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            digest.update(buf, 0, r)
                            n += r
                            onProgress(InstallProgress(InstallProgress.Phase.UNPACKING, done + n, total))
                        }
                    }
                    val sha = hex(digest.digest())
                    if (n != f.size || sha != f.sha256) {
                        staged.delete()
                        throw integrity("${f.path} in ${d.name} does not match its pins ($n bytes, SHA-256 $sha)")
                    }
                    move(staged, fileOf(f))
                    wanted.remove(f.path)
                    done += n
                }
            }
        } catch (e: ModelInstallException) {
            throw e
        } catch (e: IOException) {
            throw storage("cannot unpack ${d.name}: ${e.message}", e)
        } finally {
            staging.deleteRecursively()
        }
        if (wanted.isNotEmpty()) throw integrity("${d.name} has no ${wanted.keys.joinToString()}")
    }

    private fun move(from: File, to: File) {
        try {
            to.parentFile.mkdirs()
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw storage("cannot move $from to $to: ${e.message}", e)
        }
    }

    companion object {
        const val DOWNLOADS = ".downloads"
        private const val STAGING = "staging"
        private const val BUFFER = 64 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** Megabytes, as the UI says them. */
        fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"

        private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: url
        private fun network(message: String, cause: Throwable? = null) =
            ModelInstallException(ModelInstallException.Reason.NETWORK, message, cause)
        private fun integrity(message: String) = ModelInstallException(ModelInstallException.Reason.INTEGRITY, message)
        private fun storage(message: String, cause: Throwable? = null) =
            ModelInstallException(ModelInstallException.Reason.STORAGE, message, cause)
    }
}
