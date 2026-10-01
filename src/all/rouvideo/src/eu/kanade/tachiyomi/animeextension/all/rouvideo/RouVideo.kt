package eu.kanade.tachiyomi.animeextension.all.rouvideo

import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.PngContainer
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoDto.toAnimePage
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.ALL_VIDEOS
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.FEATURED
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LATEST_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LIKE_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.WATCHING
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.categories
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.i18n.Intl
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.util.Locale

class RouVideo(
    override val lang: String = "all",
) : AnimeHttpSource() {

    override val name = "肉視頻"

    override val baseUrl = "https://rou.video/home"
    private val videoUrl = "https://rou.video"

    private val apiUrl = "https://rou.video/api"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val intl = Intl(
        language = Locale.getDefault().language.takeIf { lang == "all" } ?: lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "zh", "vi"),
        classLoader = this::class.java.classLoader!!,
    )

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Host", apiUrl.toHttpUrl().host)
        add("Origin", videoUrl)
        add("Referer", "$videoUrl/")
    }.build()

    private val docHeaders = headers.newBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Host", videoUrl.toHttpUrl().host)
    }.build()

    // No Host header here: the playlist request is redirected to the CDN
    private val playlistHeaders by lazy {
        headers.newBuilder().apply {
            add("Referer", "$videoUrl/")
        }.build()
    }

    private val m3u8Server by lazy { M3u8ServerManager(client) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        fetchTagsListOnce()
        if (page == 0) updateHotSearch()

        return GET(
            videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("v")
                addQueryParameter("order", SORT_LIKE_KEY)
                addQueryParameter("page", page.toString())
            }.build(),
            docHeaders,
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        return AnimesPage(
            document.parseCards(),
            document.selectFirst("nav a[rel=next]") != null,
        )
    }

    /**
     * The site is server-rendered now (no more `__NEXT_DATA__`), so listings are scraped from the
     * video cards: `<a href="/v/{id}"><img/><img alt="{title}"/><span>720P</span>...<h3>{title}</h3>`.
     */
    private fun Document.parseCards(): List<SAnime> = select("a[href^=/v/]").mapNotNull { card ->
        val id = card.attr("href").removePrefix("/v/").substringBefore('?').substringBefore('/')
            .takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val title = card.selectFirst("h3")?.text()?.takeIf(String::isNotBlank)
            ?: card.selectFirst("img:not([alt=\"\"])")?.attr("alt")?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val resolution = card.select("span").firstNotNullOfOrNull { CARD_RESOLUTION_REGEX.matchEntire(it.text().trim()) }
            ?.groupValues?.get(1)

        SAnime.create().apply {
            url = id
            this.title = title
            thumbnail_url = card.select("img").lastOrNull()?.attr("abs:src")
            resolution?.let { description = resolutionDesc(it) }
        }
    }.distinctBy { it.url }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = coroutineScope {
        listOf(
            async {
                client.newCall(relatedAnimeListRequest(anime))
                    .execute()
                    .let { response ->
                        relatedAnimeListParse(response)
                    }
            },
            async {
                runCatching {
                    handleSearchAnime(watchingURL, apiHeaders) {
                        json.decodeFromString<List<RouVideoDto.Video>>(body.string()).toAnimePage()
                    }
                }
                    .getOrNull()
                    ?.animes ?: emptyList()
            },
        ).awaitAll()
            .flatten()
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val currentId = response.request.url.pathSegments.lastOrNull()

        return response.asJsoup().parseCards().filterNot { it.url == currentId }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        fetchTagsListOnce()
        if (page == 0) updateHotSearch()

        return GET(
            videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("v")
                addQueryParameter("order", SORT_LATEST_KEY)
                addQueryParameter("page", page.toString())
            }.build(),
            docHeaders,
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val type = url.pathSegments.getOrNull(0)
                ?: throw Exception("Unsupported url")
            val item = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$type:$item", filters)
        }

        // Handle direct ID search (no need for tag/hot search fetching)
        if (query.startsWith(PREFIX_ID)) {
            val id = query.removePrefix(PREFIX_ID)
            return handleSearchAnime(animeUrl(id), docHeaders) {
                AnimesPage(listOf(parseAnimeDetails(this)), false)
            }
        }

        // Handle direct Tag search from query (no need for tag/hot search fetching)
        if (query.startsWith(PREFIX_TAG)) {
            val tagValue = query.removePrefix(PREFIX_TAG)
            val url = videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegments("$CATEGORY_SLUG/$tagValue")
                addQueryParameter("page", page.toString())
            }.build()
            return handleSearchAnime(url.toString(), docHeaders, ::popularAnimeParse)
        }

        // For other search/browse types, ensure tags and hot searches are fetched
        fetchTagsListOnce()
        if (page == 0) updateHotSearch()

        val categoryFilter = filters.filterIsInstance<RouVideoFilter.CategoryFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<RouVideoFilter.SortFilter>().firstOrNull()
        val tagFilter = filters.filterIsInstance<RouVideoFilter.TagFilter>().firstOrNull()
        val hotSearchFilter = filters.filterIsInstance<RouVideoFilter.HotSearchFilter>().firstOrNull()

        val categoryUriPart = categoryFilter?.toUriPart()

        if (query.isBlank() || categoryUriPart == FEATURED) {
            // Browsing scenarios (no text query)
            return when (categoryUriPart) {
                WATCHING -> {
                    handleSearchAnime(watchingURL, apiHeaders) {
                        json.decodeFromString<List<RouVideoDto.Video>>(body.string()).toAnimePage()
                    }
                }

                FEATURED, null, "" -> { // "Featured", "No category", or "All Categories" -> Show featured content
                    handleSearchAnime(featuredURL, docHeaders) {
                        asJsoup().parseFeaturedPage()
                    }
                }

                else -> {
                    // Specific category (e.g., "asian") or "All Videos" (ALL_VIDEOS)
                    val url = buildBrowseUrl(page, categoryUriPart, sortFilter, tagFilter, hotSearchFilter)
                    handleSearchAnime(url, docHeaders, ::popularAnimeParse)
                }
            }
        } else {
            // Text search scenario
            val url = videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("search")
                addQueryParameter("q", query)

                // Add category to search query if it's a specific one (not null or empty string)
                if (!categoryUriPart.isNullOrEmpty()) {
                    addQueryParameter(CATEGORY_SLUG, categoryUriPart)
                }
                addQueryParameter("page", page.toString())
                // Sort filter is not applied for text search
            }.build()
            return handleSearchAnime(url.toString(), docHeaders, ::popularAnimeParse)
        }
    }

    // The home page has no sortable metadata anymore, so the sort filter doesn't apply to it.
    private fun Document.parseFeaturedPage(): AnimesPage = AnimesPage(parseCards(), false)

    private fun buildBrowseUrl(
        page: Int,
        categoryUri: String?, // Expects specific category (e.g. "asian") or ALL_VIDEOS.
        sortFilter: RouVideoFilter.SortFilter?,
        tagFilter: RouVideoFilter.TagFilter?,
        hotSearchFilter: RouVideoFilter.HotSearchFilter?,
    ): String = videoUrl.toHttpUrl().newBuilder().apply {
        when {
            // Specific category (e.g., "asian") is provided
            categoryUri != null && categoryUri != ALL_VIDEOS -> {
                addPathSegments("$CATEGORY_SLUG/$categoryUri")
            }

            // Tag filter is active
            tagFilter?.isEmpty() == false -> {
                if (hotSearchFilter?.isEmpty() == false) {
                    // Hot search filter is active => search within the tag
                    addPathSegment("search")
                    addQueryParameter("q", hotSearchFilter.toUriPart())
                    addQueryParameter(CATEGORY_SLUG, tagFilter.toUriPart())
                } else {
                    // Only tag filter is active => browse by tag
                    addPathSegments("$CATEGORY_SLUG/${tagFilter.toUriPart()}")
                }
            }

            else -> {
                // Default to browsing all videos
                addPathSegment(VIDEO_SLUG)
            }
        }

        // Add sorting and pagination parameters
        sortFilter?.let { addQueryParameter("order", it.toUriPart()) }
        addQueryParameter("page", page.toString())
    }.build().toString()

    private suspend fun handleSearchAnime(url: String, headers: Headers, parse: Response.() -> AnimesPage): AnimesPage = client.newCall(GET(url, headers))
        .awaitSuccess()
        .use(parse)

    private val featuredURL = baseUrl
    private val watchingURL by lazy { "$apiUrl/$VIDEO_SLUG/watching" }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        RouVideoFilter.SortFilter(intl),
        AnimeFilter.Header(intl["sort_filter_note"]),
        RouVideoFilter.CategoryFilter(intl),
        AnimeFilter.Separator(),
        RouVideoFilter.TagFilter(
            intl,
            if (!this::tagsArray.isInitialized && savedTags.isEmpty()) {
                arrayOf(Tag(intl["reset_filter_to_load"], ""))
            } else {
                setOf(Tag(intl["set_video_all_to_filter_tag"], ""))
                    .plus(if (this::tagsArray.isInitialized) tagsArray.toSet() else emptySet())
                    .plus(savedTags.minus(categories(intl)))
                    .toTypedArray()
            },
        ),
        RouVideoFilter.HotSearchFilter(
            intl,
            if (!this::hotSearch.isInitialized || hotSearch.isEmpty()) {
                setOf(Pair(intl["reset_filter_to_load"], ""))
            } else {
                setOf(Pair(intl["set_video_all_to_filter_tag"], ""))
                    .plus(hotSearch.map { it to it })
            },
        ),
    )

    /**
     * Automatically fetched tags from the source to be used in the filters.
     */
    private lateinit var tagsArray: Tags

    /**
     * The request to the page that have the tags list.
     */
    private fun tagsListRequest() = GET("$videoUrl/cat", docHeaders)

    /**
     * Fetch the genres from the source to be used in the filters.
     */
    private fun fetchTagsListOnce() {
        if (!this::tagsArray.isInitialized) {
            runCatching {
                client.newCall(tagsListRequest())
                    .execute()
                    .asJsoup()
                    .let(::tagsListParse)
                    .let { tags ->
                        if (tags.isNotEmpty()) {
                            tagsArray = tags
                        }
                    }
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * Get the genres from the document.
     */
    private fun tagsListParse(document: Document): Tags = document.select("a[href^=/t/]")
        .map { it.attr("href").removePrefix("/t/").substringBefore('?') }
        .filter(String::isNotEmpty)
        .distinct()
        .map { URLDecoder.decode(it, "UTF-8") }
        .map { Tag(it, it) }
        .toTypedArray()

    private var savedTags: Set<Tag> = loadTagListFromPreferences()
        set(value) {
            preferences.edit().putStringSet(
                TAG_LIST_PREF,
                value.map { it.first }.toSet(),
            ).apply()
            field = value
        }

    private fun loadTagListFromPreferences(): Set<Tag> = preferences.getStringSet(TAG_LIST_PREF, emptySet())
        ?.mapNotNull { Tag(it, it) }
        ?.toSet()
        ?: emptySet()

    private lateinit var hotSearch: Set<String>

    private fun hotSearchRequest() = GET("$videoUrl/search", docHeaders)

    private fun updateHotSearch() {
        runCatching {
            client.newCall(hotSearchRequest())
                .execute()
                .asJsoup()
                .let(::hotSearchParse)
                .let {
                    hotSearch = if (!this::hotSearch.isInitialized) {
                        it
                    } else {
                        hotSearch.plus(it)
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    private fun hotSearchParse(document: Document): Set<String> = document.select("a[href^=/search?q=]")
        .mapNotNull { "$videoUrl${it.attr("href")}".toHttpUrlOrNull()?.queryParameter("q") }
        .filter(String::isNotBlank)
        .toSet()

    // =========================== Anime Details ============================

    private fun animeUrl(id: String) = "$videoUrl/$VIDEO_SLUG/$id"
    override fun getAnimeUrl(anime: SAnime) = animeUrl(anime.url)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val resolution = anime.description?.let { resolutionRegex.find(it) }
            ?.groupValues?.get(1)
        return client.newCall(animeDetailsRequest(anime))
            .execute()
            .let { response ->
                parseAnimeDetails(response, resolution)
            }
    }

    override fun animeDetailsRequest(anime: SAnime) = GET(getAnimeUrl(anime), docHeaders)

    override fun animeDetailsParse(response: Response): SAnime = parseAnimeDetails(response)

    private fun parseAnimeDetails(response: Response, resolution: String? = null): SAnime {
        val document = response.asJsoup()
        val page = document.parseVideoPage() ?: return SAnime.create()

        savedTags = savedTags.plus(page.tags.map { Tag(it, it) })

        return SAnime.create().apply {
            url = page.id
            title = page.name
            thumbnail_url = page.cover
            artist = page.tags.firstOrNull()
            author = page.tags.firstOrNull()
            genre = (listOfNotNull(page.code) + page.tags).joinToString()
            status = SAnime.COMPLETED
            description = buildString {
                resolution?.takeIf(String::isNotBlank)?.let { append("${resolutionDesc(it)}\n") }
                page.duration?.let { append("Duration: ${RouVideoDto.formatDuration(it)}\n") }
                append("View: ${page.viewCount}")
                page.likeCount?.let { append(" - Like: $it") }
                page.ref?.let { append("\nRef: $it") }
                page.description?.let { append("\n\n$it") }
            }
            initialized = true
        }
    }

    private class VideoPage(
        val id: String,
        val code: String?,
        val name: String,
        val description: String?,
        val ref: String?,
        val tags: List<String>,
        val cover: String?,
        val uploadDate: String?,
        val duration: Int?,
        val viewCount: Int,
        val likeCount: Int?,
    )

    /**
     * Detail pages are server-rendered: the JSON-LD `VideoObject` carries the title, cover, tags,
     * duration and views, while the inline hydration script still has the catalogue code (`vid`),
     * the `ref` link and the like count.
     */
    private fun Document.parseVideoPage(): VideoPage? {
        val ld = select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { json.parseToJsonElement(script.data()).jsonObject }.getOrNull()
                ?.takeIf { it["@type"]?.jsonPrimitive?.contentOrNull == "VideoObject" }
        } ?: return null

        val id = ld["url"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')?.takeIf(String::isNotEmpty)
            ?: return null

        // Only the part describing the current video, before the related videos list
        val state = select("script").map { it.data() }.firstOrNull { "relatedVideos" in it }
            ?.substringBefore("relatedVideos")
            .orEmpty()

        return VideoPage(
            id = id,
            code = VIDEO_CODE_REGEX.find(state)?.groupValues?.get(1)?.takeIf(String::isNotBlank),
            name = ld["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            description = ld["description"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it != ld["name"]?.jsonPrimitive?.contentOrNull },
            ref = VIDEO_REF_REGEX.find(state)?.groupValues?.get(1)?.takeIf(String::isNotBlank),
            tags = (ld["genre"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            cover = (ld["thumbnailUrl"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: ld["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
            uploadDate = ld["uploadDate"]?.jsonPrimitive?.contentOrNull,
            duration = ld["duration"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoDuration),
            viewCount = ld["interactionStatistic"]?.jsonObject?.get("userInteractionCount")
                ?.jsonPrimitive?.intOrNull ?: 0,
            likeCount = VIDEO_LIKE_REGEX.find(state)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    private fun parseIsoDuration(value: String): Int? {
        val match = ISO_DURATION_REGEX.matchEntire(value) ?: return null
        val (h, m, s) = match.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toDoubleOrNull()?.toInt() ?: 0)
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$videoUrl/$VIDEO_SLUG/${anime.url}", docHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val page = response.asJsoup().parseVideoPage() ?: return emptyList()

        return listOf(
            SEpisode.create().apply {
                name = page.id
                url = page.id
                date_upload = page.uploadDate?.let(RouVideoDto::parseDate) ?: 0L
                episode_number = 1f
            },
        )
    }

    override fun getEpisodeUrl(episode: SEpisode) = "$videoUrl/$VIDEO_SLUG/${episode.url}"

    // ============================ Video Links =============================

    // The HLS link lives in the detail page's inline hydration state (`ev`), not in the /api endpoint.
    override fun videoListRequest(episode: SEpisode) = GET("$videoUrl/$VIDEO_SLUG/${episode.url}", docHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val state = response.asJsoup().select("script").map { it.data() }.firstOrNull { "ev:" in it }
            ?: throw Exception("Failed to load video data")

        val ev = EV_REGEX.find(state)?.destructured
            ?.let { (d, k) -> RouVideoDto.Ev(d, k.toInt()) }
            ?: throw Exception("No available videos")

        val playInfo = json.decodeFromString<RouVideoDto.PlayInfo>(ev.decodeToJson())
        val playlistUrl = videoUrl.toHttpUrl().resolve(playInfo.videoUrl)?.toString()
            ?: throw Exception("Invalid video url")

        // Playlists and segments are hidden inside PNG files, which the player can't read.
        // A local server unwraps them on the fly.
        val (finalUrl, playlist) = client.newCall(GET(playlistUrl, playlistHeaders)).execute().use { res ->
            if (!res.isSuccessful) throw Exception("Playlist error: HTTP ${res.code}")
            val bytes = res.body.bytes()
            res.request.url to String(PngContainer.unwrap(bytes) ?: bytes, Charsets.UTF_8)
        }
        if (!playlist.startsWith("#EXTM3U")) throw Exception("No available videos")

        if (!m3u8Server.isRunning()) m3u8Server.startServer()

        val variants = playlist.lines().zipWithNext()
            .filter { (tag, _) -> tag.startsWith("#EXT-X-STREAM-INF") }
            .mapNotNull { (tag, uri) ->
                val height = RESOLUTION_REGEX.find(tag)?.groupValues?.get(1)
                finalUrl.resolve(uri.trim())?.toString()?.let { it to height }
            }

        if (variants.isEmpty()) {
            val quality = QUALITY_REGEX.find(finalUrl.encodedPath)?.groupValues?.get(1)
            return listOf(createVideo(finalUrl.toString(), quality))
        }

        return variants.map { (url, height) -> createVideo(url, height) }
    }

    private fun createVideo(playlistUrl: String, height: String?): Video {
        val localUrl = m3u8Server.processM3u8Url(playlistUrl) ?: throw Exception("Local server not running")
        return Video(localUrl, height?.let { "${it}p" } ?: "HLS", localUrl)
    }

    // Sorts by quality
    override fun List<Video>.sort(): List<Video> = sortedByDescending { it.quality }

    // ============================= Utilities ==============================

    private val resolutionRegex = Regex("""Resolution: (\d+)p""")
    companion object {
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
        private val QUALITY_REGEX = Regex("""-(\d{3,4})/[^/]*$""")
        private val CARD_RESOLUTION_REGEX = Regex("""(\d{3,4})[pP]""")
        private val VIDEO_CODE_REGEX = Regex("""\bvid:"([^"]*)"""")
        private val VIDEO_REF_REGEX = Regex("""\bref:"([^"]*)"""")
        private val VIDEO_LIKE_REGEX = Regex("""\blikeCount:(\d+)""")
        private val ISO_DURATION_REGEX = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""")
        private val EV_REGEX = Regex("""\bev:\${'$'}R\[\d+]=\{d:"([^"]+)",k:(\d+)}""")

        internal fun resolutionDesc(resolution: String) = "Resolution: ${resolution}p"

        private const val VIDEO_SLUG = "v"
        private const val CATEGORY_SLUG = "t"

        private const val TAG_LIST_PREF = "TAG_LIST"

        const val PREFIX_ID = "$VIDEO_SLUG:"
        const val PREFIX_TAG = "$CATEGORY_SLUG:"
    }
}
