package eu.kanade.tachiyomi.animeextension.en.animeparadise

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimeParadise :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeParadise"

    override val baseUrl = "https://www.animeparadise.moe"

    private val apiUrl = "https://api.animeparadise.moe"

    private val streamUrl = "https://stream.animeparadise.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Host", apiUrl.toHttpUrl().host)
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    private val docHeaders = headers.newBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Host", baseUrl.toHttpUrl().host)
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/search?sort={\"rate\": -1}&page=$page", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<AnimeListResponse>()
        return AnimesPage(data.data.map { it.toSAnime(json) }, data.pagination?.hasNext == true)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/search?sort={\"startDate\": -1 }&type=TV&page=$page", apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        val url = when {
            genreFilter.state != 0 -> apiUrl + genreFilter.toUriPart()
            else -> "$apiUrl/search?title=$query&page=$page"
        }

        return GET(url, headers = apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filters are going to be ignored if using search text"),
        GenreFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("<select>", ""),
                Pair("Comedy", "/search?genre=\"Comedy\""),
                Pair("Drama", "/search?genre=\"Drama\""),
                Pair("Action", "/search?genre=\"Action\""),
                Pair("Fantasy", "/search?genre=\"Fantasy\""),
                Pair("Supernatural", "/search?genre=\"Supernatural\""),
                Pair("Latest Movie", "/search?sort={\"startDate\": -1 }&type=MOVIE"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)
        return GET("$apiUrl/anime/${data.slug}", headers = apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime = response.parseAs<AnimeDetails>().data.toSAnime()

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime/${json.decodeFromString<LinkData>(anime.url).slug}"

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)
        return GET("$apiUrl/anime/${data.id}/episode", headers = apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<EpisodeListResponse>()
        return data.data.map { it.toSEpisode() }.reversed()
    }

    // ============================ Video Links =============================

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    /** Next.js server-action id; it changes with every site deploy, so it is read from the page's scripts. */
    @Volatile
    private var episodeActionId: String? = null

    private fun fetchEpisodeActionId(episodeUrl: String): String {
        episodeActionId?.let { return it }

        val document = client.newCall(GET(baseUrl + episodeUrl, docHeaders)).execute().asJsoup()
        val chunks = document.select("script[src*=/_next/static/chunks/]").map { it.absUrl("src") }.distinct()
        val id = chunks.firstNotNullOfOrNull { chunk ->
            val script = client.newCall(GET(chunk, docHeaders)).execute().use { it.body.string() }
            EPISODE_ACTION_REGEX.find(script)?.groupValues?.get(1)
        } ?: throw Exception("Could not find the episode action on the site, it may have changed")

        episodeActionId = id
        return id
    }

    private fun fetchEpisode(episodeUrl: String, retry: Boolean = true): EpisodeActionResult {
        val url = (baseUrl + episodeUrl).toHttpUrl()
        val uid = url.pathSegments.last()
        val origin = url.queryParameter("origin") ?: throw Exception("Episode link is missing its origin, refresh the episode list")

        val actionHeaders = headers.newBuilder().apply {
            add("Accept", "text/x-component")
            add("Next-Action", fetchEpisodeActionId(episodeUrl))
            add("Origin", baseUrl)
            add("Referer", baseUrl + episodeUrl)
        }.build()
        val body = """["$uid","$origin"]""".toRequestBody("text/plain;charset=UTF-8".toMediaType())

        client.newCall(POST(baseUrl + episodeUrl, actionHeaders, body)).execute().use { response ->
            // An old action id is rejected after a deploy: look it up again once.
            val payload = if (response.isSuccessful) {
                response.body.string().lineSequence().firstOrNull { it.startsWith("1:{") }?.removePrefix("1:")
            } else {
                null
            }
            if (payload != null) return json.decodeFromString<EpisodeActionResult>(payload)
        }

        if (!retry) throw Exception("The site did not return this episode")
        episodeActionId = null
        return fetchEpisode(episodeUrl, retry = false)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val data = fetchEpisode(episode.url).episode
        val streamLink = data.streamLink?.takeIf(String::isNotBlank)
            ?: throw Exception("No stream available for this episode")

        val subtitles = data.subData.orEmpty()
            .filter { it.type == "vtt" && it.src.startsWith("http") }
            .map { Track(it.src, it.label) }

        return playlistUtils.extractFromHls(
            "$streamUrl/m3u8?url=$streamLink",
            referer = "$baseUrl/",
            subtitleList = subtitles,
        ).ifEmpty { throw Exception("Failed to fetch videos") }
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val EPISODE_ACTION_REGEX = Regex("""createServerReference\)\("([0-9a-f]+)"[^)]*"getEpisode"\)""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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
}
