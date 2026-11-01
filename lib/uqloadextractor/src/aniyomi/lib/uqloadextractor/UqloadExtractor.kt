package aniyomi.lib.uqloadextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {

    companion object {
        const val BASE_URL = "https://uqload.vc/"

        private val hostRegex by lazy { Regex("""https?://(?:www\.)?[^/]+/""") }
        private val sourceRegex by lazy { Regex("""sources:\s*\[\s*(?:\{\s*file:\s*)?"(http[^"]+)"""") }
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val fixedUrl = if (url.startsWith(BASE_URL, true)) url else url.replace(hostRegex, BASE_URL)
        val doc = client.newCall(GET(fixedUrl)).awaitSuccess().useAsJsoup()
        // The jwplayer setup moved into an eval-packed script; older pages have it in the clear.
        val script = doc.selectFirst("script:containsData(sources:)")?.data()
            ?: doc.selectFirst("script:containsData(eval):containsData(p,a,c,k,e,d)")?.data()
                ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val videoUrl = sourceRegex.find(script)?.groupValues?.get(1) ?: return emptyList()

        val referer = doc.location().toHttpUrl().let { "${it.scheme}://${it.host}/" }
        val videoHeaders = Headers.headersOf("Referer", referer)
        val quality = if (prefix.isNotBlank()) "${prefix.trim()} Uqload" else "Uqload"

        if (".m3u8" in videoUrl) {
            return PlaylistUtils(client).extractFromHls(
                videoUrl,
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { "$quality - $it" },
            )
        }

        return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
    }
}
