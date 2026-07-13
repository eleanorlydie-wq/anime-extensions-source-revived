package eu.kanade.tachiyomi.animeextension.pt.animesonlinevip

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesOnlineVip :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animes Online Vip"

    override val baseUrl = "https://animesonline.blue"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    // The site no longer has a dedicated "top 100" or "popular" listing (confirmed via
    // sitemap.xml: only /, /home, /animes, /dublado, /legendado, /generos, /letra/* exist).
    // /animes is the full catalog with real ?page= pagination, so it is used for both
    // popular and latest.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes?page=$page", headers)

    override fun popularAnimeSelector() = "a.block[href^=/anime/]"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h3")?.text()
            ?: element.selectFirst("img")?.attr("alt").orEmpty()
        thumbnail_url = element.selectFirst("img")?.attr("src")?.let(::extractNextImageUrl)
    }

    override fun popularAnimeNextPageSelector() = "a:contains(Próximo)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val searchQuery = if (url.pathSegments.size > 1) {
                "${url.pathSegments[0]}/${url.pathSegments[1]}"
            } else {
                url.pathSegments.getOrNull(0)?.takeIf(String::isNotBlank)
                    ?: throw Exception("Unsupported url")
            }
            return getSearchAnime(page, "${PREFIX_SEARCH}$searchQuery", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$path"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    // The site's search endpoint is /buscar?q=<term>; it doesn't appear to paginate
    // (page=2 returned byte-identical results to page=1 in testing), so `page` is unused.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/buscar".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val details = document.extractNextJs<AODetailsDto>(isAnimeDetailsPayload)

        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            if (details != null) {
                title = details.title
                thumbnail_url = details.cover
                description = details.synopsis
                genre = details.genres.joinToString(", ") { it.name }
            } else {
                title = document.selectFirst("h1")?.text().orEmpty()
            }
        }
    }

    // ============================== Episodes ==============================
    // The full episode list (including any per-episode title/thumbnail) is only available
    // one-at-a-time from /api/episodio/<id>, which the site itself only calls lazily when a
    // user opens an episode (confirmed in the compiled JS: `fetch(`/api/episodio/${e.id}`)`).
    // The anime details payload does embed the ordered list of episode ids upfront though, so
    // the episode list is built from that without needing 1 HTTP request per episode; the id
    // is encoded directly into the episode url so that the default videoListRequest
    // (GET(baseUrl + episode.url)) hits the API endpoint directly.
    override fun episodeListParse(response: Response): List<SEpisode> {
        val details = response.extractNextJs<AODetailsDto>(isAnimeDetailsPayload) ?: return emptyList()

        return details.episodeIds.mapIndexed { index, id ->
            SEpisode.create().apply {
                url = "/api/episodio/$id"
                episode_number = (index + 1).toFloat()
                name = "Episódio ${index + 1}"
            }
        }.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val episode = response.parseAs<AOEpisodeDto>()

        return listOfNotNull(episode.playerUrl, episode.embed)
            .filter(String::isNotBlank)
            .distinct()
            .parallelCatchingFlatMapBlocking { getVideosFromURL(it, episode.tipo) }
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private suspend fun getVideosFromURL(url: String, tipo: String?): List<Video> = when {
        "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers, suffix = tipo.orEmpty())
        else -> listOf(
            Video(url, "Default ${tipo.orEmpty()}".trim(), videoUrl = url, headers),
        )
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================
    // Anime/latest/search cards use next/image, e.g.
    // src="/_next/image?url=https%3A%2F%2Fanimesonline.blue%2Fwp-content%2Fuploads%2F...webp&w=3840&q=75"
    // Unwrap the proxy to get the direct image url.
    private fun extractNextImageUrl(src: String): String {
        val absolute = if (src.startsWith("http")) src else "$baseUrl$src"
        return runCatching { absolute.toHttpUrl().queryParameter("url") }.getOrNull() ?: absolute
    }

    // `episodeIds` (and every other field besides slug/title) carries a default value so that
    // a MissingFieldException never breaks parsing if the upstream payload drops a field, but
    // that also makes kotlinx.serialization treat it as "optional" - which would make the
    // reified/inferred extractNextJs predicate match on {slug, title} alone. That pair is too
    // generic (risk of matching an unrelated nested object), so match on `episodeIds` explicitly,
    // the field literally seen in the fetched payload: "...\"episodeIds\":[7803,7806,7809,...".
    private val isAnimeDetailsPayload: (JsonElement) -> Boolean = { it is JsonObject && "episodeIds" in it }

    @Serializable
    class AODetailsDto(
        val title: String = "",
        val cover: String? = null,
        val synopsis: String? = null,
        val genres: List<AOGenreDto> = emptyList(),
        val episodeIds: List<Long> = emptyList(),
    )

    @Serializable
    class AOGenreDto(
        val name: String,
    )

    @Serializable
    class AOEpisodeDto(
        val tipo: String? = null,
        val playerUrl: String? = null,
        val embed: String? = null,
    )

    companion object {
        const val PREFIX_SEARCH = "path:"
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("360p", "720p", "1080p")
    }
}
