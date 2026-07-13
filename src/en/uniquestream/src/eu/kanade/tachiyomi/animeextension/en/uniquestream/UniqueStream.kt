package eu.kanade.tachiyomi.animeextension.en.uniquestream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * UniqueStream (uniquestream.net) migrated away from the DooPlay WordPress
 * theme to a fully custom "uniquestream" theme (different markup for every
 * listing type: movies/tvshows archives, genre archives, ratings widget) and
 * a custom nonce + REST powered video player. None of the shared DooPlay
 * selectors match anymore, so this source is implemented directly against
 * [ParsedAnimeHttpSource] instead of the dooplay multisrc theme.
 */
class UniqueStream :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "UniqueStream"

    override val lang = "en"

    override val baseUrl = "https://uniquestream.net"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")

    private fun pagePath(page: Int) = if (page <= 1) "" else "page/$page/"

    // ============================== Popular ================================
    // Default browse feed. The site no longer has a single combined listing,
    // so tv shows (verified: /tvshows/?orderby=views) is used as the main
    // feed, with movies reachable via the "Recent" filter below.

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tvshows/${pagePath(page)}?orderby=views", headers)

    override fun popularAnimeSelector() = "a.ts-poster-card"

    override fun popularAnimeFromElement(element: Element) = animeFromCardElement(element)

    override fun popularAnimeNextPageSelector() = "button[data-page][data-max]"

    override fun popularAnimeParse(response: Response): AnimesPage = response.asJsoup().toAnimesPage(popularAnimeSelector())

    // =============================== Latest =================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/tvshows/${pagePath(page)}?orderby=date", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = animeFromCardElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = response.asJsoup().toAnimesPage(latestUpdatesSelector())

    // =============================== Search ==================================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            val doc = client.newCall(GET(query, headers)).execute().asJsoup()
            val anime = animeDetailsParse(doc).apply {
                setUrlWithoutDomain(doc.location())
                initialized = true
            }
            return AnimesPage(listOf(anime), false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = filters.ifEmpty { getFilterList() }
        val genreFilter = filterList.filterIsInstance<GenreFilter>().first()
        val recentFilter = filterList.filterIsInstance<RecentFilter>().first()
        val yearFilter = filterList.filterIsInstance<YearFilter>().first()

        return when {
            query.isNotBlank() -> GET(
                "$baseUrl/wp-json/wp/v2/search?search=${java.net.URLEncoder.encode(query, "UTF-8")}&subtype=movies,tvshows&per_page=20&page=$page",
                headers,
            )
            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}/${pagePath(page)}", headers)
            recentFilter.state != 0 -> GET("$baseUrl/${recentFilter.toUriPart()}/${pagePath(page)}?orderby=date", headers)
            yearFilter.state != 0 -> GET("$baseUrl/tvshows/${pagePath(page)}?year_from=${yearFilter.toUriPart()}&year_to=${yearFilter.toUriPart()}", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector() = "a.ts-poster-card, a.genre-card"

    override fun searchAnimeFromElement(element: Element) = animeFromCardElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url
        if (url.encodedPath.startsWith("/wp-json/")) {
            val results = runCatching {
                json.decodeFromString<List<SearchResult>>(response.body.string())
            }.getOrDefault(emptyList())

            val animes = results.map { result ->
                SAnime.create().apply {
                    setUrlWithoutDomain(result.url)
                    title = result.title
                }
            }

            val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
            val currentPage = url.queryParameter("page")?.toIntOrNull() ?: 1
            return AnimesPage(animes, currentPage < totalPages)
        }

        val selector = if (url.encodedPath.contains("/genre/")) "a.genre-card" else "a.ts-poster-card"
        return response.asJsoup().toAnimesPage(selector)
    }

    @Serializable
    data class SearchResult(
        val title: String = "",
        val url: String = "",
    )

    // ============================== Filters ==================================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        RecentFilter(),
        YearFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Genres",
            arrayOf(
                Pair("<select>", ""),
                Pair("Action", "action"),
                Pair("Action & Adventure", "action-adventure"),
                Pair("Adventure", "adventure"),
                Pair("Animation", "animation"),
                Pair("Comedy", "comedy"),
                Pair("Crime", "crime"),
                Pair("Documentary", "documentary"),
                Pair("Drama", "drama"),
                Pair("Entertainment", "entertainment"),
                Pair("Family", "family"),
                Pair("Fantasy", "fantasy"),
                Pair("History", "history"),
                Pair("Horror", "horror"),
                Pair("Kids", "kids"),
                Pair("Music", "music"),
                Pair("Mystery", "mystery"),
                Pair("News", "news"),
                Pair("Reality", "reality"),
                Pair("Romance", "romance"),
                Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
                Pair("Science Fiction", "science-fiction"),
                Pair("Soap", "soap"),
                Pair("Talk", "talk"),
                Pair("Thriller", "thriller"),
                Pair("TV Movie", "tv-movie"),
                Pair("War", "war"),
                Pair("War & Politics", "war-politics"),
                Pair("Western", "western"),
            ),
        )

    private class RecentFilter :
        UriPartFilter(
            "Recent",
            arrayOf(
                Pair("<select>", ""),
                Pair("Recent TV Shows", "tvshows"),
                Pair("Recent Movies", "movies"),
            ),
        )

    private class YearFilter :
        UriPartFilter(
            "Release Year",
            arrayOf(Pair("<select>", "")) +
                (2026 downTo 1970).map { Pair(it.toString(), it.toString()) }.toTypedArray(),
        )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================= Anime list parsing =========================

    private fun animeFromCardElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")
        title = img?.attr("alt")?.ifBlank { null }
            ?: element.selectFirst("h3")?.text().orEmpty()
        thumbnail_url = img?.getImageUrl()
    }

    private fun Document.toAnimesPage(cardSelector: String): AnimesPage {
        val animes = select(cardSelector).map(::animeFromCardElement)
        val hasNextPage = selectFirst("button[data-page][data-max]")?.let { btn ->
            val page = btn.attr("data-page").toIntOrNull() ?: 0
            val max = btn.attr("data-max").toIntOrNull() ?: 0
            page in 1 until max
        } ?: false
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        val article = document.selectFirst("article.tvshow-content, article.movie-content")
        val isTv = article?.hasClass("tvshow-content") == true

        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())

            title = document.selectFirst("h1.entry-title, h1.movie-title")?.text().orEmpty()

            document.selectFirst("img.poster-image")?.let { thumbnail_url = it.getImageUrl() }

            genre = article?.classNames()
                ?.filter { it.startsWith("genres-") }
                ?.joinToString(", ") { cls ->
                    cls.removePrefix("genres-").split("-").joinToString(" ") { word ->
                        word.replaceFirstChar(Char::uppercase)
                    }
                }

            status = when {
                !isTv -> SAnime.COMPLETED
                document.selectFirst("span.meta-status")?.text()?.contains("Ended", true) == true -> SAnime.COMPLETED
                document.selectFirst("span.meta-status") != null -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }

            description = buildString {
                document.selectFirst("div.tvshow-synopsis p, div.movie-synopsis p")?.text()?.let {
                    append(it)
                    append("\n\n")
                }
                document.select("div.detail-row, div.meta-row").forEach { row ->
                    val label = row.selectFirst("span.detail-label, span.meta-label")?.text().orEmpty()
                    val value = row.selectFirst("span.detail-value, span.meta-value")?.text().orEmpty()
                    if (label.isNotBlank() && value.isNotBlank() && !label.startsWith("Genres")) {
                        append("$label $value\n")
                    }
                }
            }.trim()
        }
    }

    // ============================== Episodes ================================

    private val episodeDateFormat by lazy { SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH) }

    override fun episodeListSelector() = "a.ep-card"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val seasonPanels = document.select("div.season-carousel-panel")

        if (seasonPanels.isEmpty() || document.select(episodeListSelector()).isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    episode_number = 1F
                    name = "Movie"
                },
            )
        }

        return seasonPanels.flatMap { panel ->
            val seasonNumber = panel.attr("data-season-number").ifBlank { "1" }
            panel.select(episodeListSelector()).mapNotNull { element ->
                runCatching { episodeFromElement(element, seasonNumber) }
                    .onFailure { it.printStackTrace() }
                    .getOrNull()
            }
        }
    }

    private fun episodeFromElement(element: Element, seasonNumber: String): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epNum = element.selectFirst("span.ep-card-badge")?.text()?.filter { it.isDigit() }.orEmpty()
        val title = element.selectFirst("h3.ep-card-title")?.text().orEmpty()
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = buildString {
            append("Season $seasonNumber")
            if (epNum.isNotBlank()) append(" Ep. $epNum")
            if (title.isNotBlank()) append(" - $title")
        }
        date_upload = element.select("div.ep-card-meta span").last()?.text()?.let {
            runCatching { episodeDateFormat.parse(it.trim())?.time }.getOrNull()
        } ?: 0L
    }

    // ============================ Video Links =============================
    // Video extraction is done directly in [getVideoList] below, bypassing
    // the selector-based Parsed* pipeline entirely (same approach the old
    // DooPlay-based version used), but these are still abstract members of
    // ParsedAnimeHttpSource and must be implemented.

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val document = client.newCall(
            GET(baseUrl + episode.url, headers = headers),
        ).execute().asJsoup()

        val nonceScriptData = document.selectFirst("script#uniquestream-player-js-extra")?.data().orEmpty()
        val nonce = Regex("\"nonce\":\"([a-f0-9]+)\"").find(nonceScriptData)?.groupValues?.get(1).orEmpty()

        val videoList = document.select("button.server-btn").flatMap { server ->
            runCatching { extractServerVideos(server, episode, nonce) }
                .onFailure { it.printStackTrace() }
                .getOrDefault(emptyList())
        }

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList
    }

    private fun extractServerVideos(server: Element, episode: SEpisode, nonce: String): List<Video> {
        val post = server.attr("data-post")
        val type = server.attr("data-type")
        val num = server.attr("data-num")
        if (post.isBlank() || nonce.isBlank()) return emptyList()

        val postBody = "action=uniquestream_player_ajax&nonce=$nonce&post=$post&type=$type&nume=$num"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val postHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Referer", baseUrl + episode.url)
            .build()

        val embedResponse = client.newCall(
            POST("$baseUrl/wp-admin/admin-ajax.php", body = postBody, headers = postHeaders),
        ).execute()

        val embedFragment = json.decodeFromString<EmbedResponse>(embedResponse.body.string()).embed_url
        val iframeSrc = Jsoup.parseBodyFragment(embedFragment).selectFirst("iframe")?.attr("src")
            ?: return emptyList()
        val embedUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
        val embedHttpUrl = embedUrl.toHttpUrl()

        val embedHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()

        val embedDocument = client.newCall(
            GET(embedUrl, headers = embedHeaders),
        ).execute().asJsoup()

        val script = embedDocument.selectFirst("script:containsData(m3u8)")?.data() ?: return emptyList()
        val playlistUrl = script.substringAfter("let url = '").substringBefore("'")
        if (playlistUrl.isBlank()) return emptyList()
        val playlistHttpUrl = playlistUrl.toHttpUrl()

        val playlistHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .set("Referer", "${embedHttpUrl.scheme}://${embedHttpUrl.host}/")
            .build()

        val masterPlaylist = client.newCall(
            GET(playlistUrl, headers = playlistHeaders),
        ).execute().body.string()

        val audioList = mutableListOf<Track>()
        if (masterPlaylist.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
            val line = masterPlaylist.substringAfter("#EXT-X-MEDIA:TYPE=AUDIO").substringBefore("\n")
            val audioUri = line.substringAfter("URI=\"").substringBefore("\"")
            val audioUrl = playlistHttpUrl.resolve(audioUri)?.toString() ?: audioUri

            audioList.add(
                Track(
                    audioUrl,
                    line.substringAfter("NAME=\"").substringBefore("\""),
                ),
            )
        }

        // The HLS master playlist never carries #EXT-X-MEDIA:TYPE=SUBTITLES
        // entries on this site; subtitles only exist as a `_allSubs` JS array
        // baked into the embed page's player-init <script>, e.g.:
        //   { html: _subLabel('English'), url: encodeURI('https://sub.uniquestream.net/st02/user/Just to Meet You 2026 S01E01.eng.srt'), _label: 'English' },
        // `url` is a raw (unencoded) link wrapped in a JS encodeURI() call, so
        // spaces are percent-encoded here to mirror what encodeURI() would
        // produce in the browser.
        val subtitleList = subtitleTracksFromEmbedScript(script)

        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
            .mapNotNull { entry ->
                val quality = entry.substringAfter("RESOLUTION=").substringAfter("x")
                    .substringBefore("\n").substringBefore(",") + "p"
                val videoUri = entry.substringAfter("\n").substringBefore("\n")
                if (videoUri.isBlank()) return@mapNotNull null
                val videoUrl = playlistHttpUrl.resolve(videoUri)?.toString() ?: videoUri

                Video(
                    videoUrl,
                    quality,
                    videoUrl,
                    headers = playlistHeaders,
                    subtitleTracks = subtitleList,
                    audioTracks = audioList,
                )
            }
    }

    private val subtitleEntryRegex = Regex("""url:\s*encodeURI\('([^']*)'\)\s*,\s*_label:\s*'([^']*)'""")

    private fun subtitleTracksFromEmbedScript(script: String): List<Track> = subtitleEntryRegex.findAll(script).mapNotNull { match ->
        val url = match.groupValues[1].replace(" ", "%20")
        val label = match.groupValues[2]
        if (url.isBlank() || label.isBlank()) return@mapNotNull null
        Track(url, label.replaceFirstChar(Char::uppercase))
    }.toList()

    @Serializable
    data class EmbedResponse(
        val embed_url: String = "",
    )

    // ============================== Settings ==============================

    private val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    private val prefQualityDefault = "1080p"
    private val prefQualityKey = "preferred_quality"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = prefQualityKey
            title = "Preferred quality"
            entries = prefQualityValues
            entryValues = prefQualityValues
            setDefaultValue(prefQualityDefault)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================= Utilities ==============================

    private fun Element.getImageUrl(): String? = when {
        hasAttr("data-wpfc-original-src") -> attr("abs:data-wpfc-original-src")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }
}
