package eu.kanade.tachiyomi.animeextension.en.rule34video
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class Rule34Video :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com"

    override val lang = "en"

    override val supportsLatest = false

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)) {
        val uploaderId = preferences.getString(PREF_UPLOADER_ID_KEY, "") ?: ""
        if (uploaderId.isNotBlank()) {
            val url = "$baseUrl/members/$uploaderId/videos/?mode=async&function=get_block&block_id=list_videos_uploaded_videos&sort_by=&from_videos=$page"
            Log.e("Rule34Video", "Loading popular videos from uploader ID: $uploaderId, page: $page, URL: $url")
            GET(url)
        } else {
            Log.e("Rule34Video", "Uploader filter enabled but ID is blank, loading latest updates.")
            GET("$baseUrl/latest-updates/$page/")
        }
    } else {
        GET("$baseUrl/latest-updates/$page/")
    }

    override fun popularAnimeSelector() = "div.item.thumb"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.th")!!.attr("href"))
        title = element.selectFirst("a.th div.thumb_title")!!.text()
        thumbnail_url = element.selectFirst("a.th div.img img")?.attr("abs:data-original")
    }

    override fun popularAnimeNextPageSelector() = "div.item.pager.next a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    private inline fun <reified R> AnimeFilterList.getUriPart() = (find { it is R } as? UriPartFilter)?.toUriPart() ?: ""

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val slug = url.pathSegments.getOrNull(2)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$slug", filters)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Deep-link slug search coming from the URL intent activity.
        if (query.startsWith(PREFIX_SEARCH)) {
            val newQuery = query.removePrefix(PREFIX_SEARCH).dropLastWhile { it.isDigit() }
            return GET("$baseUrl/search/$newQuery", headers)
        }

        val sortType = filters.getUriPart<OrderFilter>()
        val categoryFilter = filters.getUriPart<CategoryBy>()
        val tagIds = resolveTagIds(filters)
        val duration = filters.filterIsInstance<DurationFilter>().firstOrNull()

        // The search path holds the free-text query (spaces -> dashes); everything
        // else (tags, sort, category, duration) is passed as query parameters.
        val pathQuery = query.trim().takeIf { it.isNotEmpty() }
            ?.replace(Regex("\\s+"), "-")
            ?.let { "$it/" }
            .orEmpty()

        val urlBuilder = "$baseUrl/search/$pathQuery".toHttpUrl().newBuilder()
            .addQueryParameter("flag1", categoryFilter)
            .addQueryParameter("sort_by", sortType)
            .addQueryParameter("from_videos", page.toString())
            .addQueryParameter("tag_ids", buildTagIdsParam(tagIds))

        duration?.from?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("duration_from", it) }
        duration?.to?.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("duration_to", it) }

        return GET(urlBuilder.build().toString(), headers)
    }

    private fun buildTagIdsParam(tagIds: List<String>): String = if (tagIds.isEmpty()) "all" else "all," + tagIds.joinToString(",")

    // Tag dictionary powering the autocomplete suggestions and fast name->id
    // resolution. Loaded from the bundled snapshot (Rule34VideoTags) rather than
    // fetched lazily, because the app builds the filter list exactly once — before
    // any search runs — and never rebuilds it. A lazily-fetched list would always
    // arrive too late, leaving the AutoComplete field with no suggestions. Tags
    // missing from the snapshot still resolve via the network fallback in
    // lookupTagId(). Both maps are built on first access (in-memory, no network).
    private val tagDictionary: Map<String, String> by lazy {
        // lowercase name -> id
        Rule34VideoTags.pairs.associate { it.first.lowercase(Locale.US) to it.second }
    }

    private val tagSuggestions: List<String> by lazy {
        // display names, alphabetically ordered
        Rule34VideoTags.pairs.map { it.first }.distinct()
    }

    private fun parseTagItems(doc: Document): List<Pair<String, String>> = doc.select("div.item").mapNotNull { item ->
        val id = item.selectFirst("input")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val label = item.selectFirst("label")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        id to label
    }

    // Resolves the typed tag names into the numeric tag IDs the site expects.
    // Numeric input is passed through untouched so power users can paste IDs
    // directly. Multiple tags are AND-ed together by the site. Exclusion ("-")
    // is dropped because the site does not support excluding tags.
    private fun resolveTagIds(filters: AnimeFilterList): List<String> {
        val raw = (filters.find { it is TagFilter } as? TagFilter)?.state?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ';')
            .map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("-") }
            .distinct()
            .mapNotNull(::lookupTagId)
            .distinct()
    }

    private fun lookupTagId(nameOrId: String): String? {
        if (nameOrId.all { it.isDigit() }) return nameOrId
        // Fast path: the cached dictionary already covers most tags.
        tagDictionary[nameOrId.lowercase(Locale.US)]?.let { return it }
        // Fallback: query the autocomplete endpoint for tags not in the cache.
        return try {
            val url = "$baseUrl/search_ajax.php?tag=${nameOrId.replace(" ", "+")}"
            val items = parseTagItems(client.newCall(GET(url, headers)).execute().asJsoup())
            items.firstOrNull { it.second.equals(nameOrId, ignoreCase = true) }?.first
                ?: items.firstOrNull()?.first
        } catch (e: Exception) {
            Log.e("Rule34Video", "Failed to resolve tag \"$nameOrId\"", e)
            null
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.title_video")?.text().toString()

        val infoRow = document.selectFirst("div.info.row")
        val detailRows = document.select("div.row")

        val artistElement = detailRows.select("div.col:has(div.label:contains(Artist)) a.item span.name").firstOrNull()
        author = artistElement?.text().orEmpty()

        description = buildString {
            detailRows.select("div.row:has(div.label > em) > div.label > em").html()
                .replace("<br>", "\n") // Ensure single <br> tags are followed by a newline
                .let { text ->
                    append(text)
                }
            append("\n\n") // Add extra spacing

            infoRow?.selectFirst("div.item_info:nth-child(1) > span")?.text()?.let {
                append("Uploaded: $it\n")
            }

            val artist = detailRows.select("div.col:has(div.label:contains(Artist)) a.item span.name")
                .eachText()
                .joinToString()
            if (artist.isNotEmpty()) {
                append("Artists: $artist\n")
            }

            val categories = detailRows.select("div.col:has(div.label:contains(Categories)) a.item span")
                .eachText()
                .joinToString()
            if (categories.isNotEmpty()) {
                append("Categories: $categories\n")
            }

            val uploader = detailRows.select("div.col:has(div.label:contains(Uploaded by)) a.item").text()
            if (uploader.isNotEmpty()) {
                append("Uploader: $uploader\n")
            }

            infoRow?.select("div.item_info:nth-child(2) > span")?.text()?.let {
                val views = it.substringBefore(" ").replace(",", "")
                append("Views: $views\n")
            }
            infoRow?.select("div.item_info:nth-child(3) > span")?.text()?.let { append("Duration: $it\n") }
            document.select("div.row:has(div.label:contains(Download)) a.tag_item")
                .eachText()
                .joinToString { it.substringAfter(" ") }
                .also { append("Quality: $it") }
        }

        genre = document.select("div.row_spacer:has(div.label:contains(Tags)) a.tag_item:not(:contains(Suggest))")
            .eachText()
            .joinToString()

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Video"
        },
    )

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val headers = headersBuilder()
            .apply {
                val cookies = client.cookieJar.loadForRequest(response.request.url)
                    .filterNot { it.name in listOf("__ddgid_", "__ddgmark_") }
                    .map { "${it.name}=${it.value}" }
                    .joinToString("; ")
                val xsrfToken = cookies.split("XSRF-TOKEN=").getOrNull(1)?.substringBefore(";")?.replace("%3D", "=")
                xsrfToken?.let { add("X-XSRF-TOKEN", it) }
                add("Cookie", cookies)
                add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                add("Referer", response.request.url.toString())
                add("Accept-Language", "en-US,en;q=0.5")
            }.build()

        val document = response.asJsoup()

        return document.select("div.label:contains(Download) ~ a.tag_item")
            .mapNotNull { element ->
                val originalUrl = element.attr("href")
                // We need to do that because this url returns a http 403 error
                // if you try to connect using http/1.1, which is the protocol
                // that the player uses. OkHttp uses http/2 by default, so we
                // fetch the video url first via okhttp and then pass it for the player.
                val url = noRedirectClient.newCall(GET(originalUrl, headers)).execute()
                    .use { it.headers["location"] }
                    ?: return@mapNotNull null
                val quality = element.text().substringAfter(" ")
                Video(url, quality, url, headers)
            }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p") ?: return this
        return sortedWith(compareByDescending { it.quality == quality })
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_UPLOADER_FILTER_ENABLED_KEY
            title = "Filter by Uploader"
            summary = "Load videos only from the specified uploader ID."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_UPLOADER_ID_KEY
            title = "Uploader ID"
            summary = "Enter the ID of the uploader (e.g., 98965). Requires \"Filter by Uploader\" to be enabled."
            dialogTitle = "Enter Uploader ID"
            setOnPreferenceChangeListener { _, newValue ->
                newValue?.toString().isNullOrBlank().not()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = entries
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

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false) &&
        preferences.getString(PREF_UPLOADER_ID_KEY, "")?.isNotBlank() == true
    ) {
        AnimeFilterList() // If uploader filter is enabled and ID is set, show no other filters
    } else {
        AnimeFilterList(
            AnimeFilter.Header("Type tag names and pick from the suggestions; separate with , or ;"),
            AnimeFilter.Header("Videos must match all chosen tags. Numeric IDs also work."),
            AnimeFilter.Header("Exclusion (-) isn't supported."),
            TagFilter(tagSuggestions),
            AnimeFilter.Separator(),
            OrderFilter(),
            CategoryBy(),
            DurationFilter(),
        )
    }

    private class TagFilter(suggestions: List<String>) : AnimeFilter.AutoComplete("Tags", "e.g. futanari, blonde hair, big breasts", suggestions = suggestions)

    private class CategoryBy :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("All", ""),
                Pair("Straight", "2109"),
                Pair("Futa", "15"),
                Pair("Gay", "192"),
                Pair("Music", "4747"),
                Pair("Iwara", "1821"),
            ),
        )

    private class OrderFilter :
        UriPartFilter(
            "Sort by",
            arrayOf(
                Pair("Latest", "post_date"),
                Pair("Most Viewed", "video_viewed"),
                Pair("Top Rated", "rating"),
                Pair("Longest", "duration"),
                Pair("Random", "pseudo_rand"),
            ),
        )

    // Maps preset duration ranges to the site's duration_from / duration_to
    // query parameters (values are in seconds).
    private class DurationFilter :
        AnimeFilter.Select<String>(
            "Duration",
            arrayOf("Any", "Under 1 min", "1 - 5 min", "5 - 20 min", "20 - 60 min", "Over 1 hour"),
        ) {
        val from: String
            get() = when (state) {
                2 -> "60"
                3 -> "300"
                4 -> "1200"
                5 -> "3600"
                else -> ""
            }
        val to: String
            get() = when (state) {
                1 -> "60"
                2 -> "300"
                3 -> "1200"
                4 -> "3600"
                else -> ""
            }
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p", "360p")

        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
    }
}
