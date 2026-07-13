package eu.kanade.tachiyomi.animeextension.tr.animeler

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SourceUrlDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Animeler :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animeler"

    override val baseUrl = "https://animeler.pw"

    override val lang = "tr"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/filter?sort=popular&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("a.anime-card-modern").map(::parseAnimeElement)
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseAnimeElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("div.anime-title-strip")?.text()
            ?: element.attr("title")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filter?sort=updated&page=$page")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(0)?.takeIf { it.isNotBlank() }
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
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun getFilterList() = AnimelerFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimelerFilters.getSearchParameters(filters)
        val url = "$baseUrl/filter".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", params.sort)
            if (query.isNotBlank()) addQueryParameter("search", query)
            params.category.forEach { addQueryParameter("category[]", it) }
            params.type.forEach { addQueryParameter("type[]", it) }
            params.genre.forEach { addQueryParameter("genre[]", it) }
            params.year.forEach { addQueryParameter("year[]", it) }
            params.season.forEach { addQueryParameter("season[]", it) }
            params.status.forEach { addQueryParameter("status[]", it) }
        }.build()

        return GET(url.toString())
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst("h1.adp-hero__title")?.text()
                ?.removeSuffix(" İzle")?.trim()
                ?: document.title()
            thumbnail_url = document.selectFirst("div.adp-hero__poster img")?.attr("src")

            val genreRow = kvRow(document, "Türler")
            genre = genreRow?.select("span.adp-hero__kv-pill")?.joinToString { it.text() }
            artist = kvValue(document, "Stüdyo")

            status = when (kvValue(document, "Durum")) {
                "Tamamlandı" -> SAnime.COMPLETED
                "Yayında" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }

            description = buildString {
                val synopsis = document.selectFirst("div#overviewFull")?.text()?.takeIf { it.isNotBlank() }
                    ?: document.selectFirst("p.adp-synopsis__text")?.text()
                synopsis?.let { append(it) }
                kvValue(document, "İngilizce")?.takeIf { it.isNotBlank() }?.let { append("\n\nİngilizce: $it") }
                kvValue(document, "Japonca")?.takeIf { it.isNotBlank() }?.let { append("\nJaponca: $it") }
                kvValue(document, "Alternatif isimler")?.takeIf { it.isNotBlank() }
                    ?.let { append("\nAlternatif isimler: $it") }
            }
        }
    }

    private fun kvRow(document: Document, label: String): Element? = document.select("div.adp-hero__kv-row").firstOrNull {
        it.selectFirst("span.adp-hero__kv-label")?.text() == label
    }

    private fun kvValue(document: Document, label: String): String? = kvRow(document, label)?.selectFirst("span.adp-hero__kv-value")?.text()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        return document.select("a.ep-list-item[data-ep-id]")
            .distinctBy { it.attr("data-ep-id") }
            .map {
                val number = it.attr("data-ep-num")
                SEpisode.create().apply {
                    setUrlWithoutDomain(it.attr("href"))
                    episode_number = number.toFloatOrNull() ?: 1F
                    name = "Bölüm $number"
                    date_upload = it.selectFirst("span.ep-list-date")?.text()?.let(::toDate) ?: 0L
                }
            }
    }

    // ============================ Video Links =============================
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: return emptyList()
        val refererUrl = response.request.url.toString()

        val chosenHosts = preferences.getStringSet(PREF_HOSTS_SELECTION_KEY, SUPPORTED_PLAYERS)!!

        val sourceIds = document.select("button.video-source-btn[data-source-id]")
            .mapNotNull { button ->
                val id = button.attr("data-source-id").toIntOrNull() ?: return@mapNotNull null
                val sourceName = button.selectFirst("span.source-name")?.text().orEmpty()
                id to sourceName
            }
            .distinctBy { it.first }
            .filter { (_, sourceName) -> chosenHosts.any { sourceName.contains(it, ignoreCase = true) } }
            .map { it.first }

        return sourceIds.parallelCatchingFlatMapBlocking { sourceId ->
            val body = FormBody.Builder()
                .add("_token", csrfToken)
                .add("source_id", sourceId.toString())
                .build()

            val ajaxHeaders = headers.newBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Accept", "application/json")
                .add("Referer", refererUrl)
                .build()

            val result = client.newCall(POST("$baseUrl/ajax/get-source-url", ajaxHeaders, body))
                .awaitSuccess()
                .parseAs<SourceUrlDto>()

            val embedUrl = result.url
            if (!result.success || embedUrl.isNullOrBlank()) {
                emptyList()
            } else {
                val embedDoc = client.newCall(GET(embedUrl, headers)).awaitSuccess().asJsoup()
                val playerUrl = embedDoc.selectFirst("iframe#innerPlayer")?.attr("src")
                if (playerUrl.isNullOrBlank()) emptyList() else videosFromUrl(playerUrl)
            }
        }
    }

    private suspend fun videosFromUrl(url: String): List<Video> = when {
        "dood" in url -> doodExtractor.videosFromUrl(url)

        "drive.google" in url -> {
            val newUrl = "https://gdriveplayer.to/embed2.php?link=$url"
            gdrivePlayerExtractor.videosFromUrl(newUrl, "GdrivePlayer", headers)
        }

        "filemoon." in url -> filemoonExtractor.videosFromUrl(url)

        "ok.ru" in url || "odnoklassniki.ru" in url -> okruExtractor.videosFromUrl(url)

        "streamtape" in url -> streamtapeExtractor.videoFromUrl(url)?.let(::listOf)

        "sibnet" in url -> sibnetExtractor.videosFromUrl(url)

        "streamlare" in url -> streamlareExtractor.videosFromUrl(url)

        "uqload" in url -> uqloadExtractor.videosFromUrl(url)

        "voe." in url -> voeExtractor.videosFromUrl(url)

        "vudeo." in url -> vudeoExtractor.videosFromUrl(url)

        else -> null
    } ?: emptyList()

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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTS_SELECTION_KEY
            title = PREF_HOSTS_SELECTION_TITLE
            entries = PREF_HOSTS_SELECTION_ENTRIES
            entryValues = PREF_HOSTS_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTS_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun toDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr.trim())?.time }
        .getOrNull() ?: 0L

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

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "id:"

        private val SUPPORTED_PLAYERS = setOf(
            "doodstream.com",
            "G.Drive",
            "Moon",
            "ok.ru",
            "S.Tape",
            "Sibnet",
            "Streamlare",
            "UQload",
            "Voe",
            "vudeo",
        )

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_HOSTS_SELECTION_KEY = "pref_hosts_selection"
        private const val PREF_HOSTS_SELECTION_TITLE = "Disable/enable video hosts"
        private val PREF_HOSTS_SELECTION_ENTRIES = SUPPORTED_PLAYERS.toTypedArray()
        private val PREF_HOSTS_SELECTION_DEFAULT = SUPPORTED_PLAYERS
    }
}
