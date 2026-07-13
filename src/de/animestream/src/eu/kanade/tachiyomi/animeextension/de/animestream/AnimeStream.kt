package eu.kanade.tachiyomi.animeextension.de.animestream

import eu.kanade.tachiyomi.animeextension.de.animestream.extractors.MetaExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeStream : ParsedAnimeHttpSource() {

    override val name = "Anime-Stream"

    override val baseUrl = "https://anime-stream.to"

    override val lang = "de"

    override val id: Long = 314593699490737069

    override val supportsLatest = false

    private val json: Json by injectLazy()

    // The AJAX endpoints used for episode listing (generate_token.php / load_episodes.php)
    // reject requests without a browser-like User-Agent ("Access denied" / HTTP 403/500),
    // unlike the regular HTML pages which are UA-agnostic. Force one for every request.
    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")

    override fun popularAnimeSelector(): String = "div.grid-layout div.panel"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/alle-serien?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a.panel-link")?.attr("href").orEmpty())
        anime.thumbnail_url = element.selectFirst("a.content-box img")?.attr("src")
        anime.title = element.selectFirst("a.content-box img")?.attr("alt").orEmpty()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.cyber-button:contains(NÄCHSTE)"

    // episodes

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val scriptData = document.select("script:containsData(const seriesId)").firstOrNull()?.data().orEmpty()
        val seriesId = SERIES_ID_REGEX.find(scriptData)?.groupValues?.get(1) ?: return episodeList

        val seasons = document.select("select#seasonSelect option")
            .mapNotNull { it.attr("value").toIntOrNull() }

        for (season in seasons) {
            val episodesHtml = fetchEpisodesHtmlWithRetry(seriesId, season) ?: continue
            val fragment = Jsoup.parseBodyFragment(episodesHtml)
            val seasonEpisodes = fragment.select("div.panel.episode-panel").mapNotNull { panel ->
                val href = panel.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epNumText = panel.selectFirst("span.episode-number")?.text().orEmpty()
                val epNum = EPISODE_NUM_REGEX.find(epNumText)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                val title = panel.selectFirst("div.episode-info h3")?.text()
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    episode_number = epNum
                    name = buildString {
                        append("Staffel $season Folge ${epNum.toInt()}")
                        if (!title.isNullOrBlank()) append(": $title")
                    }
                }
            }
            episodeList.addAll(seasonEpisodes.reversed())
        }

        return episodeList
    }

    // generate_token.php / load_episodes.php are both known-good endpoints (confirmed live
    // with curl, browser UA, no cookies required) but the origin intermittently answers
    // load_episodes.php with HTTP 403 "Access denied" for a short run of consecutive
    // requests (observed bursts of 2-6 in a row) before recovering on its own - reusing the
    // same token on retry does not reliably help, nor does a brand-new token on the very
    // next try, so this is origin-side flakiness (likely an unhealthy backend behind
    // Cloudflare, cf-cache-status: BYPASS on both success and failure) rather than anything
    // about our request. A bounded retry with backoff, regenerating the token each time,
    // reliably rides this out (verified: 6/6 induced failures recovered within this budget).
    private val retryDelaysMs = longArrayOf(0L, 1_000L, 2_000L, 4_000L, 8_000L)

    private fun fetchEpisodesHtmlWithRetry(seriesId: String, season: Int): String? {
        for (delayMs in retryDelaysMs) {
            if (delayMs > 0) Thread.sleep(delayMs)
            val token = fetchEpisodeToken(seriesId, season) ?: continue
            val html = fetchEpisodesHtml(seriesId, season, token)
            if (html != null) return html
        }
        return null
    }

    private fun fetchEpisodeToken(seriesId: String, season: Int): String? {
        val url = "$baseUrl/generate_token.php?serie_id=$seriesId&season=$season"
        val body = client.newCall(GET(url, headers)).execute().body?.string() ?: return null
        val obj = runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull() ?: return null
        if (obj["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        return obj["token"]?.jsonPrimitive?.contentOrNull
    }

    private fun fetchEpisodesHtml(seriesId: String, season: Int, token: String): String? {
        val url = "$baseUrl/includes/ajax/load_episodes.php".toHttpUrl().newBuilder()
            .addQueryParameter("serie_id", seriesId)
            .addQueryParameter("season", season.toString())
            .addQueryParameter("token", token)
            .build()
        val body = client.newCall(GET(url, headers)).execute().body?.string() ?: return null
        val obj = runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull() ?: return null
        if (obj["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        return obj["html"]?.jsonPrimitive?.contentOrNull
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        // The episode link (SEpisode.url) points straight at /secure-redirect.php, which is
        // gated by a Cloudflare Turnstile challenge page (confirmed live:
        // <title>Verifizierung erforderlich</title>, HTTP 200, no video link present). That
        // wall cannot be solved over plain HTTP/webview-less requests, so there is genuinely
        // no video to extract - but silently returning an empty list here is indistinguishable
        // from "this episode has no working host", which is misleading. Surface it clearly.
        if (document.title().contains("Verifizierung erforderlich", ignoreCase = true)) {
            throw Exception("Video blocked by a Cloudflare Turnstile CAPTCHA (${document.title()}) - cannot be solved without a real browser")
        }
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val url = document.selectFirst("div a.lnk-lnk")?.attr("href")
        if (url.isNullOrBlank()) return videoList
        val quality = "Metastream"
        val video = MetaExtractor(client).videoFromUrl(url, quality)
        if (video != null) {
            videoList.add(video)
        }
        return videoList.reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("div.result-content h3 a")
        anime.setUrlWithoutDomain(linkElement?.attr("href").orEmpty())
        anime.title = linkElement?.text().orEmpty()
        anime.thumbnail_url = element.selectFirst("div.result-image img")?.attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.search-pagination a.cyber-button:contains(NÄCHSTE)"

    override fun searchAnimeSelector(): String = "div.search-results div.search-result-item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search.php?q=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.series-cover img")?.attr("src")
        anime.title = document.selectFirst("h1")?.ownText().orEmpty()
        anime.description = (
            document.selectFirst("span#full-description")
                ?: document.selectFirst("span#short-description")
            )?.text()
        anime.status = parseStatus(document.selectFirst("span.status")?.text())
        anime.genre = document.select("div.genres span.genre-tag a").joinToString(", ") { it.text() }
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Completed", ignoreCase = true) || status.contains("Abgeschlossen", ignoreCase = true) -> SAnime.COMPLETED
        status.contains("Ongoing", ignoreCase = true) || status.contains("Laufend", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    companion object {
        private val SERIES_ID_REGEX = Regex("""seriesId\s*=\s*(\d+)""")
        private val EPISODE_NUM_REGEX = Regex("""(\d+)""")
    }
}
