package com.withesse.beacon.log

import java.io.File
import java.util.zip.Inflater

/**
 * Mars XLog binary decoder (no encryption).
 * Converts .xlog binary files to readable plain text.
 *
 * Mars XLog 二进制解码器（无加密）。
 * 将 .xlog 二进制文件转换为可读纯文本。
 *
 * Supports all known magic-byte variants:
 *   0x03/0x04 (v0, 2-byte length)
 *   0x05/0x06 (v1, 4-byte length)
 *   0x07/0x08 (no-crypt, 4-byte length)
 */
object XLogDecoder {

    // -- magic bytes ----------------------------------------------------------

    private const val MAGIC_NO_COMPRESS_START = 0x03
    private const val MAGIC_COMPRESS_START = 0x04
    private const val MAGIC_COMPRESS_START1 = 0x05
    private const val MAGIC_NO_COMPRESS_START1 = 0x06
    private const val MAGIC_COMPRESS_NO_CRYPT_START = 0x07
    private const val MAGIC_NO_COMPRESS_NO_CRYPT_START = 0x08
    private const val MAGIC_END = 0x00

    private val VALID_MAGIC = setOf(
        MAGIC_NO_COMPRESS_START, MAGIC_COMPRESS_START,
        MAGIC_COMPRESS_START1, MAGIC_NO_COMPRESS_START1,
        MAGIC_COMPRESS_NO_CRYPT_START, MAGIC_NO_COMPRESS_NO_CRYPT_START
    )

    private fun isCompressed(magic: Int): Boolean =
        magic == MAGIC_COMPRESS_START ||
        magic == MAGIC_COMPRESS_START1 ||
        magic == MAGIC_COMPRESS_NO_CRYPT_START

    /**
     * Header layout:
     *   v0 (0x03, 0x04): magic(1) + seq(2) + beginHour(1) + endHour(1) + length(2) = 7
     *   v1+  (0x05-0x08): magic(1) + seq(2) + beginHour(1) + endHour(1) + length(4) = 9
     */
    private fun headerSize(magic: Int): Int = when (magic) {
        MAGIC_NO_COMPRESS_START, MAGIC_COMPRESS_START -> 7
        else -> 9
    }

    private fun bodyLength(buffer: ByteArray, offset: Int, magic: Int): Int {
        val p = offset + 5 // after magic(1) + seq(2) + beginHour(1) + endHour(1)
        return when (magic) {
            MAGIC_NO_COMPRESS_START, MAGIC_COMPRESS_START -> {
                (buffer[p].toInt() and 0xFF) or
                ((buffer[p + 1].toInt() and 0xFF) shl 8)
            }
            else -> {
                (buffer[p].toInt() and 0xFF) or
                ((buffer[p + 1].toInt() and 0xFF) shl 8) or
                ((buffer[p + 2].toInt() and 0xFF) shl 16) or
                ((buffer[p + 3].toInt() and 0xFF) shl 24)
            }
        }
    }

    // -- public API -----------------------------------------------------------

    /**
     * Decode an xlog file to plain text.
     * @return decoded text, or null if the file is empty or decode failed entirely
     */
    fun decode(xlogFile: File): String? {
        if (!xlogFile.exists() || xlogFile.length() == 0L) return null

        val buffer = xlogFile.readBytes()
        val sb = StringBuilder()
        var offset = 0

        while (offset < buffer.size) {
            val magic = buffer[offset].toInt() and 0xFF

            if (magic !in VALID_MAGIC) {
                offset++
                continue
            }

            val hSize = headerSize(magic)
            if (offset + hSize >= buffer.size) break

            val bodyLen = bodyLength(buffer, offset, magic)
            if (bodyLen <= 0 || offset + hSize + bodyLen + 1 > buffer.size) {
                offset++
                continue
            }

            // verify end magic
            if ((buffer[offset + hSize + bodyLen].toInt() and 0xFF) != MAGIC_END) {
                offset++
                continue
            }

            val body = buffer.copyOfRange(offset + hSize, offset + hSize + bodyLen)

            try {
                val text = if (isCompressed(magic)) {
                    inflate(body)
                } else {
                    String(body, Charsets.UTF_8)
                }
                sb.append(text)
            } catch (_: Exception) {
                // skip corrupted block
            }

            offset += hSize + bodyLen + 1
        }

        return sb.toString().ifEmpty { null }
    }

    /**
     * Decode xlog file and write to output file.
     * @return true if decoded successfully with content
     */
    fun decode(xlogFile: File, outputFile: File): Boolean {
        val text = decode(xlogFile) ?: return false
        outputFile.writeText(text, Charsets.UTF_8)
        return true
    }

    // -- inflate --------------------------------------------------------------

    /** Mars XLog uses raw deflate; fall back to zlib-wrapped if that fails. */
    private fun inflate(data: ByteArray): String {
        return try {
            doInflate(data, nowrap = true)
        } catch (_: Exception) {
            doInflate(data, nowrap = false)
        }
    }

    private fun doInflate(data: ByteArray, nowrap: Boolean): String {
        val inflater = Inflater(nowrap)
        try {
            inflater.setInput(data)
            val buf = ByteArray(4096)
            val sb = StringBuilder()
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            return sb.toString()
        } finally {
            inflater.end()
        }
    }
}
