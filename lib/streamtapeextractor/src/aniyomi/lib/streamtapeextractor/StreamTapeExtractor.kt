package aniyomi.lib.streamtapeextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamTapeExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<Track> = emptyList()): Video? {
        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            // ["https", "", "<domain>", "<???>", "<id>", ...]
            val id = url.split("/").getOrNull(4) ?: return null
            baseUrl + id
        } else {
            url
        }

        // streamtape.com fronts /e/ with Cloudflare bot-management: a request with no
        // User-Agent (the extension client attaches none of its own) gets served a
        // "Just a moment..." JS challenge page instead of the real player markup, so
        // `document` below silently has no #robotlink script and every video is lost.
        // A plain desktop UA is enough to pass it (live-verified: no Accept/Accept-Language
        // headers needed on top of it).
        val playerHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .build()
        val document = client.newCall(GET(newUrl, playerHeaders)).execute().asJsoup()
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return null
        val videoUrl = "https:" + script.substringBefore("'") +
            script.substringAfter("+ ('xcd").substringBefore("'")

        return Video(videoUrl, quality, videoUrl, subtitleTracks = subtitleList)
    }

    fun videosFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<Track> = emptyList()): List<Video> = videoFromUrl(url, quality, subtitleList)?.let(::listOf).orEmpty()
}
