package eu.kanade.tachiyomi.animeextension.tr.hentaizm

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.hentaizm.extractors.VideaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class HentaiZM :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiZM"

    override val baseUrl = "https://www.hentaizm1.com"

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()
    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    // The site redesign moved the "most popular" list to the AJAX endpoint that
    // backs the "En Çok Favorilere Eklenenler" (most added to favorites) tab.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/api/top_favorites_list.php?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = json.decodeFromString<HentaiZMApiListDto>(response.body.string())
        val document = Jsoup.parseBodyFragment(data.html, baseUrl)
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }
        return AnimesPage(animes, data.page < data.totalPages)
    }

    override fun popularAnimeSelector() = "div.video-list-item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val titleLink = element.selectFirst("div.title > a")!!
        title = titleLink.ownText()
        setUrlWithoutDomain(titleLink.attr("href"))
        thumbnail_url = element.selectFirst("div.img img")?.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    // Backed by the "Son Eklenen Bölümler" (recently added episodes) AJAX endpoint.
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/api/episodes_list.php?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = json.decodeFromString<HentaiZMApiListDto>(response.body.string())
        val document = Jsoup.parseBodyFragment(data.html, baseUrl)
        val animes = document.select(latestUpdatesSelector())
            .map(::latestUpdatesFromElement)
            .distinctBy { it.url }
        return AnimesPage(animes, data.page < data.totalPages)
    }

    override fun latestUpdatesSelector() = "div.video-list-item"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val animeLink = element.selectFirst("div.details > a.video-name")!!
        setUrlWithoutDomain(animeLink.attr("href"))
        element.selectFirst("div.title > a")!!.text().also {
            title = it.substringBefore(". Bölüm").substringBeforeLast(" ")
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/anime/$id"))
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

    // The old full-text-search page (/page/N/?s=) no longer exists; the site now
    // exposes a single-shot AJAX suggestion endpoint used by the header/sidebar search box.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/api/sidebar_anime_list.php?q=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = json.decodeFromString<HentaiZMApiListDto>(response.body.string())
        val document = Jsoup.parseBodyFragment(data.html, baseUrl)
        val animes = document.select(searchAnimeSelector())
            .map(::searchAnimeFromElement)
            .distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector() = "a.sidebar-anime-item"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst("div.text")!!.text()
        setUrlWithoutDomain(element.attr("href"))
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("p.anime-detail-item:contains(Anime Adı:)")!!.ownText().trim()
        // Poster, genre and synopsis are only rendered for logged-in members on the
        // current site; anonymous requests get a "please log in" placeholder instead,
        // so these are left unset (null) rather than scraping that placeholder text.
        thumbnail_url = document.selectFirst("div.col-md-2 img")?.attr("abs:src")
        description = document.selectFirst("div.anime-synopsis p")
            ?.text()
            ?.takeIf { it.isNotBlank() && !it.contains("giriş yapmanız") }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.episode-list-container a.episode-list-item"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.selectFirst("div.text")!!.text().also {
            val num = it.substringBeforeLast(". Bölüm", "")
                .substringAfterLast(" ")
                .ifBlank { "1" }

            episode_number = num.toFloatOrNull() ?: 1F
            name = "$num. Bölüm"
        }
    }

    // ============================ Video Links =============================
    private val videaExtractor by lazy { VideaExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videaItem = doc.selectFirst("div.alternatif a:contains(Videa)")!!
        val path = videaItem.attr("onclick").substringAfter("../../").substringBefore("'")
        val req = client.newCall(GET("$baseUrl/$path", headers)).execute()
            .asJsoup()
        val videaUrl = req.selectFirst("iframe")!!.attr("abs:src")
        return videaExtractor.videosFromUrl(videaUrl)
    }

    private val qualityRegex by lazy { Regex("""(\d+)p""") }
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),

        ).reversed()
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}

@Serializable
private data class HentaiZMApiListDto(
    val success: Boolean = false,
    val html: String = "",
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
)
