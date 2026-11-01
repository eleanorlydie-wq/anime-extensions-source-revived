package eu.kanade.tachiyomi.animeextension.id.nimegami

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class NimeGami : AnimeHttpSource() {

    override val name = "NimeGami"

    override val baseUrl = "https://nimegami.id"

    override val lang = "id"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(catalogUrl(page), headers)

    override fun popularAnimeParse(response: Response) = catalogParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(catalogUrl(page, "sort" to "updated"), headers)

    override fun latestUpdatesParse(response: Response) = catalogParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.firstOrNull()?.takeIf(String::isNotBlank)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(catalogUrl(page, "q" to query), headers)

    // The server only renders the list when page=1 is left out of the query.
    private fun catalogUrl(page: Int, param: Pair<String, String>? = null) = "$baseUrl/anime/".toHttpUrl().newBuilder().apply {
        param?.let { addQueryParameter(it.first, it.second) }
        if (page > 1) addQueryParameter("page", page.toString())
    }.build()

    override fun searchAnimeParse(response: Response) = catalogParse(response)

    private fun catalogParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val animes = document.select("article.anime-card").mapNotNull(::animeFromCard)
        val hasNext = document.selectFirst("a[href*=page=${page + 1}]") != null
        return AnimesPage(animes, hasNext)
    }

    private fun animeFromCard(card: Element): SAnime? {
        val link = card.selectFirst("a.poster-link") ?: return null
        val image = card.selectFirst("img") ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain(link.attr("abs:href"))
            title = image.attr("alt")
            thumbnail_url = image.attr("abs:src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = animeDetailsParse(response.asJsoup())

    private fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
        description = document.selectFirst("meta[name=description]")?.attr("content")
        genre = document.select("a[href*=/genre/]").eachText().distinct().joinToString()
        status = with(document.title()) {
            when {
                contains("(End)") || contains("Movie") -> SAnime.COMPLETED
                contains("On-Going", ignoreCase = true) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val path = response.request.url.encodedPath
        return streamsFrom(response.body.string())
            .map { it.episode }
            .distinct()
            .map { number ->
                SEpisode.create().apply {
                    url = "$path#$number"
                    name = "Episode $number"
                    episode_number = number.toFloatOrNull() ?: 0F
                }
            }
            .sortedByDescending { it.episode_number }
    }

    /** Episode links sit in the Next.js flight data embedded in the page, not in the markup. */
    private fun streamsFrom(html: String): List<Stream> {
        val flightData = html.replace("\\\"", "\"")
        return STREAM_REGEX.findAll(flightData).map { match ->
            Stream(
                playUrl = match.groupValues[1],
                episode = match.groupValues[2],
                quality = match.groupValues[3],
                provider = match.groupValues[4],
            )
        }.distinctBy { it.playUrl }.toList()
    }

    private class Stream(val playUrl: String, val episode: String, val quality: String, val provider: String)

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val path = episode.url.substringBefore("#")
        val number = episode.url.substringAfter("#")
        val html = client.newCall(GET(baseUrl + path, headers)).awaitSuccess().use { it.body.string() }

        return streamsFrom(html)
            .filter { it.episode == number }
            .parallelCatchingFlatMap { stream ->
                val player = client.newCall(GET(baseUrl + stream.playUrl, headers)).awaitSuccess().use { it.asJsoup() }
                val source = player.selectFirst("main#player")?.attr("data-src").orEmpty()
                if (source.isBlank()) {
                    emptyList()
                } else {
                    listOf(Video(source, "${stream.provider} - ${stream.quality}", source, headers))
                }
            }
            .sortedByDescending { it.quality.substringAfter(" - ").removeSuffix("p").toIntOrNull() ?: 0 }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val STREAM_REGEX = Regex(
            """"playUrl":"(/[^"]+/streaming/([\d.]+)/([^/"]+)/[^"]+)","mode":"embed","provider":"([^"]*)"""",
        )
    }
}
