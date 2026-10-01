package eu.kanade.tachiyomi.animeextension.pt.pifansubs

import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors.BlembedExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PiFansubs :
    DooPlay(
        "pt-BR",
        "Pi Fansubs",
        "https://pifansubs.club",
    ) {

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override val prefQualityValues = arrayOf("360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("div.source-box:not(#source-player-trailer) iframe")
        return players.map(::getPlayerUrl).flatMap(::getPlayerVideos)
    }

    private fun getPlayerUrl(player: Element): String = player.attr("data-src").ifEmpty { player.attr("src") }.let {
        when {
            !it.startsWith("http") -> "https:" + it
            else -> it
        }
    }

    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val blembedExtractor by lazy { BlembedExtractor(client, headers) }

    private fun getPlayerVideos(url: String): List<Video> = when {
        "https://vidhide" in url -> runBlocking { vidHideExtractor.videosFromUrl(url) }
        "https://blembed" in url -> blembedExtractor.videosFromUrl(url)
        "fsst.online" in url || "incvideo" in url -> incVideoVideos(url)
        else -> emptyList<Video>()
    }

    private fun incVideoVideos(url: String): List<Video> = runCatching {
        val page = client.newCall(GET(url, headers)).execute().use { it.body.string() }
        val file = INC_FILE_REGEX.find(page)?.groupValues?.get(1) ?: return emptyList()
        val playerHeaders = headers.newBuilder().set("Referer", "https://incvideo1.online/").build()
        file.split(",[").map { it.removePrefix("[") }.mapNotNull { entry ->
            val quality = entry.substringBefore("]")
            val videoUrl = entry.substringAfter("]").trimEnd('/', ',')
            if (!videoUrl.startsWith("http")) return@mapNotNull null
            Video(videoUrl, "IncVideo - $quality", videoUrl, playerHeaders)
        }
    }.getOrDefault(emptyList())

    // =========================== Anime Details ============================
    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .eachText()
        .joinToString("\n\n") + "\n"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episodios/page/$page", headers)

    companion object {
        private val INC_FILE_REGEX = Regex("""file:\s*"(\[[^"]+)"""")
    }
}
