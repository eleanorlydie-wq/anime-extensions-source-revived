package aniyomi.lib.m3u8server

import java.io.ByteArrayOutputStream
import java.util.zip.InflaterOutputStream

/**
 * Some hosts hide playlists and segments inside a PNG file: after the usual signature and image
 * chunks there is a private `roUd` chunk whose first byte is a flag (bit 0 = deflate-compressed)
 * followed by the real payload.
 */
object PngContainer {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val PAYLOAD_CHUNK = 0x726F5564 // "roUd"

    fun isPng(data: ByteArray): Boolean = data.size > SIGNATURE.size && SIGNATURE.indices.all { data[it] == SIGNATURE[it] }

    /**
     * @return the hidden payload, or null if [data] isn't a PNG carrying a `roUd` chunk
     */
    fun unwrap(data: ByteArray): ByteArray? {
        if (!isPng(data)) return null

        var offset = SIGNATURE.size
        while (offset + 8 <= data.size) {
            val length = readInt(data, offset)
            val type = readInt(data, offset + 4)
            val start = offset + 8
            if (length < 0 || start + length > data.size) return null

            if (type == PAYLOAD_CHUNK && length >= 1) {
                val compressed = data[start].toInt() and 1 != 0
                return if (compressed) {
                    ByteArrayOutputStream().also { out ->
                        InflaterOutputStream(out).use { it.write(data, start + 1, length - 1) }
                    }.toByteArray()
                } else {
                    data.copyOfRange(start + 1, start + length)
                }
            }
            offset = start + length + 4 // skip CRC
        }
        return null
    }

    private fun readInt(data: ByteArray, at: Int): Int = ((data[at].toInt() and 0xFF) shl 24) or
        ((data[at + 1].toInt() and 0xFF) shl 16) or
        ((data[at + 2].toInt() and 0xFF) shl 8) or
        (data[at + 3].toInt() and 0xFF)
}
