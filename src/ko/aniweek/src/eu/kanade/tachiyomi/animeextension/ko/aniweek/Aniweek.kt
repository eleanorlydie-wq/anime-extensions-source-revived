package eu.kanade.tachiyomi.animeextension.ko.aniweek

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Aniweek :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Aniweek"

    override val baseUrl = "https://aniweek.com"

    override val lang = "ko"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = boardRequest(BOARD_ENDED, page)

    override fun popularAnimeParse(response: Response): AnimesPage = boardParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = boardRequest(BOARD_AIRING, page)

    override fun latestUpdatesParse(response: Response): AnimesPage = boardParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val board = filters.filterIsInstance<BoardFilter>().firstOrNull()?.selected ?: BOARD_ENDED
        return boardRequest(board, page, query.trim())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = boardParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("검색은 선택한 분류 안에서만 동작합니다"),
        BoardFilter(),
    )

    private class BoardFilter : AnimeFilter.Select<String>("분류", BOARDS) {
        val selected get() = BOARDS[state]
    }

    private fun boardRequest(board: String, page: Int, keyword: String = ""): Request {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment(board).apply {
            if (keyword.isNotEmpty()) addQueryParameter("keyword", keyword)
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    private fun boardParse(response: Response): AnimesPage {
        val props = response.asJsoup().pageProps()
        // Paginated boards expose "comics"; the airing board exposes a plain "items" list.
        val comics = props["comics"]?.jsonObject
        val items = comics?.get("data")?.jsonArray ?: props["items"]?.jsonArray ?: JsonArray(emptyList())
        val animes = items.map { item ->
            val series = item.jsonObject
            SAnime.create().apply {
                url = "/c/" + series.string("slug").orEmpty().encodeSegment()
                title = series.string("title").orEmpty()
                thumbnail_url = series.string("thumbnail")?.let { "$baseUrl/storage/$it" }
            }
        }
        val current = comics?.get("current_page")?.jsonPrimitive?.intOrNull ?: 1
        val last = comics?.get("last_page")?.jsonPrimitive?.intOrNull ?: 1
        return AnimesPage(animes, current < last)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val series = response.asJsoup().pageProps()["comic"]!!.jsonObject
        return SAnime.create().apply {
            title = series.string("title").orEmpty()
            thumbnail_url = series.string("thumbnail")?.let { "$baseUrl/storage/$it" }
            author = series.string("author")
            artist = series.string("director")
            description = series.string("description")?.replace(HTML_BREAK, "\n")?.trim()
            status = when (series.string("category")) {
                BOARD_AIRING -> SAnime.ONGOING
                BOARD_ENDED, BOARD_MOVIES -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val firstPage = response.asJsoup().pageProps()["episodes"]!!.jsonObject
        val lastPage = firstPage["last_page"]?.jsonPrimitive?.intOrNull ?: 1
        val pages = (2..lastPage).map { page ->
            val url = response.request.url.newBuilder().setQueryParameter("page", page.toString()).build()
            client.newCall(GET(url, headers)).execute().asJsoup().pageProps()["episodes"]!!.jsonObject
        }
        return (listOf(firstPage) + pages).flatMap { it["data"]!!.jsonArray }.map { item ->
            val episode = item.jsonObject
            SEpisode.create().apply {
                url = "/e/" + episode.string("slug").orEmpty().encodeSegment()
                name = episode.string("title").orEmpty()
                episode_number = name.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0F
                date_upload = episode.string("published_at")?.let(::parseDate) ?: 0L
            }
        }
    }

    private fun Document.pageProps(): JsonObject {
        val data = selectFirst("script[data-page=app]")?.data() ?: error("Page data not found")
        return json.parseToJsonElement(data).jsonObject["props"]!!.jsonObject
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonElement)?.jsonPrimitive?.contentOrNull

    private fun String.encodeSegment(): String = "https://aniweek.com/".toHttpUrl().newBuilder()
        .addPathSegment(this)
        .build()
        .encodedPath
        .removePrefix("/")

    // ============================ Video Links =============================

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val playing = response.asJsoup().pageProps()["playing"]!!.jsonObject
        val iframeUrl = playing["body"]!!.jsonObject.string("url") ?: error("Failed to extract iframe")

        val iframeHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", iframeUrl.toHttpUrl().host)
            .add("Referer", "$baseUrl/")
            .add("Sec-Fetch-Dest", "iframe")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "cross-site")
            .add("Upgrade-Insecure-Requests", "1")
            .build()

        val iframeResponse = client.newCall(
            GET(iframeUrl, headers = iframeHeaders),
        ).execute()

        val subtitleList = mutableListOf<Track>()
        val scriptElement = iframeResponse.asJsoup().selectFirst("script:containsData(playerjsSubtitle)")
        if (scriptElement != null) {
            val string = scriptElement.data().substringAfter("var playerjsSubtitle = \"").substringBefore("\"")
            if (string.isNotEmpty()) {
                subtitleList.add(
                    Track(
                        "https:" + string.substringAfter("https:"),
                        string.substringBefore("https:"),
                    ),
                )
            }
        }

        val hash = if (iframeUrl.contains("/video/")) {
            iframeUrl.substringAfter("/video/")
        } else {
            iframeUrl.substringAfter("data=")
        }

        val videoPostHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            add("Origin", "https://${iframeUrl.toHttpUrl().host}")
            add("Referer", iframeUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val videoPostBody = "hash=$hash&r=${java.net.URLEncoder.encode("$baseUrl/", "utf-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val postResponse = client.newCall(
            POST("https://${iframeUrl.toHttpUrl().host}/player/index.php?data=$hash&do=getVideo", body = videoPostBody, headers = videoPostHeaders),
        ).execute().body.string()

        val parsed = json.decodeFromString<IframeResponse>(postResponse)

        return if (parsed.hls) {
            val masterHeaders = headers.newBuilder().apply {
                add("Accept", "*/*")
                add("Host", iframeUrl.toHttpUrl().host)
                add("Referer", iframeUrl)
                add("Sec-Fetch-Dest", "empty")
                add("Sec-Fetch-Mode", "cors")
                add("Sec-Fetch-Site", "same-origin")
                add("TE", "trailers")
            }.build()

            fun genVideoHeaders(baseHeaders: Headers, referer: String, videoUrl: String): Headers = baseHeaders.newBuilder().apply {
                add("Accept", "*/*")
                add("Origin", "https://${iframeUrl.toHttpUrl().host}")
                add("Referer", "https://${iframeUrl.toHttpUrl().host}/")
            }.build()

            playlistUtils.extractFromHls(
                parsed.videoSource,
                masterHeadersGen = { _, _ -> masterHeaders },
                videoHeadersGen = ::genVideoHeaders,
            )
        } else {
            emptyList()
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.substringBefore("p").toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    @Serializable
    data class IframeResponse(
        val hls: Boolean,
        val videoSource: String,
    )

    private fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr.substringBefore("T"))?.time }
        .getOrNull() ?: 0L

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
        }

        private const val BOARD_AIRING = "방영중"
        private const val BOARD_ENDED = "종영"
        private const val BOARD_MOVIES = "극장판"
        private val BOARDS = arrayOf(BOARD_ENDED, BOARD_AIRING, BOARD_MOVIES)
        private val HTML_BREAK = Regex("""<br\s*/?>""")

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
