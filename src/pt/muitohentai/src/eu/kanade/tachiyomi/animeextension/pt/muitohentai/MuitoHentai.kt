package eu.kanade.tachiyomi.animeextension.pt.muitohentai

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat

class MuitoHentai : ParsedAnimeHttpSource() {
    override val name = "Muito Hentai"

    override val baseUrl = "https://www.muitohentai.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ranking-hentais/?paginacao=$page")

    override fun popularAnimeSelector() = "ul.ul_sidebar > li"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("div.zeroleft > a > img")?.attr("src")
        element.selectFirst("div.lefthentais > div > b:gt(0) > a.series")!!.run {
            setUrlWithoutDomain(attr("href"))
            title = text()
        }
    }

    override fun popularAnimeNextPageSelector() = "div.paginacao > a:contains(»)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/")

    override fun latestUpdatesSelector() = "div.animation-2 > article:contains(Episódio)"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val slug = element.selectFirst("a")!!.attr("href")
            .substringAfter("/episodios/")
            .substringBefore("-episodio")
        url = "/info/$slug"
        val img = element.selectFirst("img")!!
        title = img.attr("alt")
        thumbnail_url = img.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/buscar/$query")

    override fun searchAnimeSelector() = "div#archive-content > article > div.poster"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        val img = element.selectFirst("img")!!
        title = img.attr("alt")
        thumbnail_url = img.attr("src")
    }

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val data = document.selectFirst("div.sheader > div.data")!!
        title = data.selectFirst("h1")!!.text()
        genre = data.selectFirst("div.sgeneros")!!.children()
            .filterNot { it.text().contains(title) }
            .joinToString { it.text() }
        description = data.selectFirst("div#info1 > div.wp-content > p")!!.text()
        thumbnail_url = document.selectFirst("div.sheader > div.poster > img")?.attr("src")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "article.item"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val data = element.selectFirst("div.data")!!
        setUrlWithoutDomain(element.selectFirst("div.poster > div.season_m > a")!!.attr("href"))
        name = data.selectFirst("h3")!!.text().trim()
        date_upload = data.selectFirst("span")?.text()?.parseDate() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "div.playex iframe"

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        return doc.select(videoListSelector()).mapNotNull { it.attr("abs:src").ifBlank { null } }.distinct().flatMap { frame ->
            runCatching {
                when {
                    "/players/p2/" in frame -> p2Videos(frame)
                    else -> hdVideos(frame)
                }
            }.getOrDefault(emptyList())
        }
    }

    // hd.php wraps a blogger video
    private fun hdVideos(url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).execute().use { it.body.string() }
        val blogger = Regex("""src=["'](https://www\.blogger\.com/video\.g[^"']+)""").find(body)?.groupValues?.get(1)
            ?: return emptyList()
        return runBlocking { bloggerExtractor.videosFromUrl(blogger, headers, " - HD") }
    }

    // p2 player points at a php endpoint that redirects to the mp4
    private fun p2Videos(url: String): List<Video> {
        val body = client.newCall(GET(url, headers)).execute().use { it.body.string() }
        val php = Regex("""<source src=["']([^"']+)""").find(body)?.groupValues?.get(1) ?: return emptyList()
        val noRedirect = client.newBuilder().followRedirects(false).build()
        val location = noRedirect.newCall(GET(php, headers)).execute().use { it.header("Location") } ?: php
        val videoUrl = location.toHttpUrlOrNull()?.toString() ?: return emptyList()
        return listOf(Video(videoUrl, "SD", videoUrl, headers))
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun String.parseDate(): Long = runCatching { DATE_FORMATTER.parse(this)?.time }
        .getOrNull() ?: 0L
    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd")
        }
    }
}
