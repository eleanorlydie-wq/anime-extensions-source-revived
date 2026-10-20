package eu.kanade.tachiyomi.animeextension.all.hentaitorrent

import java.io.IOException
import java.util.Locale

/**
 * Reads a .torrent's file list (paths and sizes) without loading it into the torrent server, so
 * listings can tell what a post really contains before the user opens it.
 */
internal object TorrentContents {

    class Entry(val path: String, val size: Long) {
        val extension get() = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    fun parse(bytes: ByteArray): List<Entry> {
        val root = Reader(bytes).read() as? Map<*, *> ?: throw IOException("Not a torrent file")
        val info = root["info"] as? Map<*, *> ?: throw IOException("Torrent without info")
        val name = text(info["name.utf-8"] ?: info["name"]).orEmpty()
        val files = info["files"] as? List<*>
            ?: return listOf(Entry(name, info["length"] as? Long ?: 0L))
        return files.mapNotNull { file ->
            file as? Map<*, *> ?: return@mapNotNull null
            val parts = (file["path.utf-8"] ?: file["path"]) as? List<*> ?: return@mapNotNull null
            val path = parts.joinToString("/") { text(it).orEmpty() }
            // BEP 47 padding files only align pieces; they are not content.
            if (path.contains("_____padding_file") || path.startsWith(".pad/")) return@mapNotNull null
            Entry(path, file["length"] as? Long ?: 0L)
        }
    }

    private fun text(value: Any?) = (value as? ByteArray)?.toString(Charsets.UTF_8)

    /** Bencode: dictionaries become maps with string keys, byte strings stay [ByteArray]. */
    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun read(): Any = when (val c = peek()) {
            'i' -> {
                pos++
                number('e')
            }
            'l' -> {
                pos++
                val list = ArrayList<Any>()
                while (peek() != 'e') list += read()
                pos++
                list
            }
            'd' -> {
                pos++
                val map = HashMap<String, Any>()
                while (peek() != 'e') {
                    val key = (read() as? ByteArray ?: throw IOException("Bad torrent key")).toString(Charsets.UTF_8)
                    map[key] = read()
                }
                pos++
                map
            }
            in '0'..'9' -> {
                val length = number(':').toInt()
                if (length < 0 || pos + length > bytes.size) throw IOException("Truncated torrent")
                bytes.copyOfRange(pos, pos + length).also { pos += length }
            }
            else -> throw IOException("Bad torrent data at $pos: $c")
        }

        private fun peek(): Char {
            if (pos >= bytes.size) throw IOException("Truncated torrent")
            return bytes[pos].toInt().toChar()
        }

        private fun number(end: Char): Long {
            val start = pos
            while (peek() != end) pos++
            val value = String(bytes, start, pos - start, Charsets.US_ASCII).toLongOrNull()
                ?: throw IOException("Bad torrent number at $start")
            pos++
            return value
        }
    }
}
