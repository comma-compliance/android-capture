package com.commacompliance.archiver.upload

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * In-memory [AttachmentSource] for host tests. Maps a part_uri to its bytes; a uri
 * with no entry returns null (the "part is gone" path). Counts opens so a test can
 * assert the bytes are re-streamed (hash pass + upload pass) and never fully
 * buffered when chunking.
 *
 * For the memory-bound assertion, [maxBufferedBytes] can wrap each returned stream
 * so the test fails loudly if any single read pulls more than the allowed window.
 */
class FakeAttachmentSource(
    private val parts: MutableMap<String, ByteArray> = mutableMapOf(),
    private val maxReadBytes: Int? = null,
) : AttachmentSource {
    var opens = 0
        private set

    fun put(partUri: String, bytes: ByteArray) {
        parts[partUri] = bytes
    }

    fun remove(partUri: String) {
        parts.remove(partUri)
    }

    override fun open(partUri: String): InputStream? {
        val bytes = parts[partUri] ?: return null
        opens++
        val base: InputStream = ByteArrayInputStream(bytes)
        return if (maxReadBytes == null) base else BoundedReadStream(base, maxReadBytes)
    }

    /**
     * Wraps a stream so any read asking for more than [limit] bytes in one call is
     * trimmed - proving the caller reads in bounded windows. (A buffer larger than
     * the file is fine; the point is no single materialization of the whole file.)
     */
    private class BoundedReadStream(private val delegate: InputStream, private val limit: Int) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, minOf(len, limit))

        override fun close() = delegate.close()
    }
}
