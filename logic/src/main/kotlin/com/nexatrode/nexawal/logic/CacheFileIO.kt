package com.nexatrode.nexawal.logic

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-safe persistence and recoverable quarantine for WalletCore cache blobs. */
object CacheFileIO {
    /** Returns null only when absent; an existing unreadable file is an error. */
    @Throws(IOException::class)
    fun readTextIfPresent(target: File): String? {
        if (!target.exists()) return null
        if (!target.isFile) throw IOException("not a regular file: ${target.absolutePath}")
        return target.readText()
    }

    @Throws(IOException::class)
    fun writeAtomically(target: File, bytes: ByteArray) {
        writeAtomically(target) { output -> output.write(bytes) }
    }

    @Throws(IOException::class)
    internal fun writeAtomically(target: File, writer: (FileOutputStream) -> Unit) {
        val parent = target.absoluteFile.parentFile
            ?: throw IOException("cache file has no parent directory: ${target.absolutePath}")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("could not create cache directory: ${parent.absolutePath}")
        }

        val temporary = File.createTempFile(".${target.name}.", ".tmp", parent)
        var committed = false
        try {
            FileOutputStream(temporary).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            moveReplacing(temporary, target)
            committed = true
        } finally {
            if (!committed) temporary.delete()
        }
    }

    /** Move a rejected cache out of the active slot without deleting evidence. */
    @Throws(IOException::class)
    fun quarantineRejected(target: File, timestampMillis: Long = System.currentTimeMillis()): File? {
        val source = target.absoluteFile
        if (!source.exists()) return null
        val parent = source.parentFile
            ?: throw IOException("cache file has no parent directory: ${source.absolutePath}")

        var attempt = 0
        while (true) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = File(parent, "${source.name}.rejected-$timestampMillis$suffix")
            try {
                moveWithoutReplacing(source, candidate)
                return candidate
            } catch (_: FileAlreadyExistsException) {
                attempt += 1
            }
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun moveWithoutReplacing(source: File, target: File) {
        // No REPLACE_EXISTING: rejected evidence must never overwrite an earlier quarantine.
        // Source and destination live in the same directory, so this is a filesystem rename.
        Files.move(source.toPath(), target.toPath())
    }
}
