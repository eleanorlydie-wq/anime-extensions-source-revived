package aniyomi.lib.mixdropextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.unpacker.SubstringExtractor
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLDecoder

class MixDropExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ): List<Video> {
        val headers = Headers.headersOf(
            "Referer",
            referer,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        )
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let(::unpackMixDrop)
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val videoUrl = "https:" + unpacked.substringAfter("Core.wurl=\"")
            .substringBefore("\"")

        val subs = unpacked.substringAfter("Core.remotesub=\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.let { listOf(Track(URLDecoder.decode(it, "utf-8"), "sub")) }
            ?: emptyList()

        val quality = buildString {
            append("${prefix}MixDrop")
            if (lang.isNotBlank()) append("($lang)")
        }

        return listOf(Video(videoUrl, quality, videoUrl, headers = headers, subtitleTracks = subs + externalSubs))
    }

    fun videosFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ) = videoFromUrl(url, lang, prefix, externalSubs, referer)
}

private const val DEFAULT_REFERER = "https://mixdrop.co/"

/**
 * Unpacks MixDrop's eval-packed player script, e.g.:
 *   eval(function(p,a,c,k,e,d){...}('1.31="//2-3.8.7/15/6.13";...',10,32,'|MDCore|a|...'.split('|'),0,{}))
 *
 * This mirrors [keiyoushi.lib.unpacker.Unpacker], but reads the packer's actual radix
 * (the `a` argument, e.g. `10` above) from the script instead of assuming base 62. As of
 * 2026-07 MixDrop emits base-10 symbol indices (plain multi-digit decimal numbers like
 * "11", "31"); decoding those as base62 yields out-of-range indices, so every multi-digit
 * token (e.g. the one for "wurl") silently fails to substitute and `Core.wurl="` never
 * appears in the "unpacked" output.
 */
private fun unpackMixDrop(script: String): String {
    val packed = SubstringExtractor(script)
        .substringBetween("}('", ".split('|'),0,{}))")
        .replace("\\'", "\"")
    if (packed.isEmpty()) return ""

    val parser = SubstringExtractor(packed)
    val data = parser.substringBefore("',")
    if (data.isEmpty()) return ""

    // `a` (radix) immediately follows the data string: 'DATA',10,32,'DICT'.split('|')
    val radix = parser.substringBefore(",").trim().toIntOrNull() ?: 62
    parser.substringBefore(",") // `c` (symbol count) - dictionary.size below is authoritative

    val dictionary = parser.substringBetween("'", "'").split("|")
    val size = dictionary.size

    return wordRegex.replace(data) {
        val key = it.value
        val index = parseRadix(key, radix)
        if (index >= size) return@replace key
        dictionary.getOrNull(index)?.ifEmpty { key } ?: key
    }
}

private val wordRegex = Regex("""\w+""")

private fun parseRadix(str: String, radix: Int): Int {
    var result = 0
    for (ch in str) {
        val digit = when {
            ch.code <= '9'.code -> ch.code - '0'.code // 0-9
            ch.code >= 'a'.code -> ch.code - ('a'.code - 10) // a-z
            else -> ch.code - ('A'.code - 36) // A-Z
        }
        result = result * radix + digit
    }
    return result
}
