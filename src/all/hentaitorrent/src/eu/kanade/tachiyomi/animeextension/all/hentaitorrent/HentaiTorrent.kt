package eu.kanade.tachiyomi.animeextension.all.hentaitorrent

import android.annotation.SuppressLint
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.SocketTimeoutException
import java.util.Locale

class HentaiTorrent :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Hentai Torrent (Torrent)"

    override val baseUrl = "https://www.hentaitorrents.com"

    override val lang = "all"

    private val preferences by getPreferencesLazy()

    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page")

    override fun popularAnimeSelector(): String = "div.image-container div.image-wrapper"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.overlay").attr("href"))
        anime.title = element.select("a.overlay").text().trim()
        anime.thumbnail_url = element.select("img").attr("src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a:contains(Next)"

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchListing(page, ::popularAnimeRequest)

    // The tracker mixes videos with games, artwork and manga; a torrent without video files has
    // no episodes, so those entries are dropped from listings (unless the user opts out).
    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select(popularAnimeSelector())
        val videoOnly = preferences.getBoolean(VIDEO_ONLY_KEY, VIDEO_ONLY_DEFAULT)
        val animes = cards
            .filterNot { videoOnly && it.isNonVideoEntry() }
            .map(::popularAnimeFromElement)
        // Catalog pages render an empty pagination block, so a full page is the only "more" signal.
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null || cards.size >= PAGE_SIZE
        return AnimesPage(animes, hasNextPage)
    }

    private fun Element.isNonVideoEntry(): Boolean {
        val catalog = selectFirst("div.catalog")?.text().orEmpty()
        if (NON_VIDEO_CATALOGS.any { catalog.contains(it, ignoreCase = true) }) return true
        // Catalog labels are unreliable (image sets get filed under "3D video Hentai"), but the
        // title carries a format tag such as [JPG] or [ART].
        return NON_VIDEO_TAG.containsMatchIn(selectFirst("a.overlay")?.text().orEmpty())
    }

    // An empty page reads as "no results" in the browse screen, so skip ahead (bounded) past
    // pages whose entries were all filtered out.
    private suspend fun fetchListing(page: Int, request: (Int) -> Request): AnimesPage {
        var current = page
        while (true) {
            val result = client.newCall(request(current)).awaitSuccess().use(::parseListing)
            if (result.animes.isNotEmpty() || !result.hasNextPage || current - page >= MAX_PAGE_SKIP) {
                return result
            }
            current++
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        // Open a shared/deep-linked detail URL directly. Detail pages are top-level
        // slugs (e.g. /ptcen-kiya-shii-22505.html), so the page IS the anime.
        val url = query.removePrefix(PREFIX_SEARCH)
        if (url.startsWith("https://")) {
            if (url.toHttpUrl().host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            return client.newCall(GET(url))
                .awaitSuccess()
                .use(::searchAnimeByUrlParse)
        }
        return fetchListing(page) { searchAnimeRequest(it, query, filters) }
    }

    private fun searchAnimeByUrlParse(response: Response): AnimesPage {
        val anime = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
        }
        return AnimesPage(listOf(anime), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.replace(" ", "+")
        val cat = filters.filterIsInstance<CategoriesList>().firstOrNull()
            ?.let { getCategory()[it.state].id }

        return when {
            query.isNotEmpty() -> GET("$baseUrl/s.php?search=$encodedQuery&page=$page")
            // "Home" has no catalog page (/catalog/0 is empty); it's the front page listing.
            cat == null || cat == "0" -> popularAnimeRequest(page)
            else -> GET("$baseUrl/catalog/$cat/page/$page")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // Set only when found so the browse flow's title/thumbnail (from the listing) are never blanked.
        document.selectFirst("h1")?.text()?.trim()?.let { anime.title = it }
        document.selectFirst("img[src*=pic.hentaitorrents.com]")?.attr("abs:src")?.let { anime.thumbnail_url = it }
        anime.description = document.select("div.article-content").html().replace(Regex("<(?!br\\s*/?)[^>]+>"), "").replace("<br>", "\n").replace("<br/>", "\n")
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.download-container div.download-button"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val downloadButtonUrl = document.selectFirst("div.download-container a.download-button")?.attr("abs:href")
            .takeUnless { it.isNullOrEmpty() } ?: throw Exception("No Torrent Found!")
        val downloadPage = client.newCall(GET(downloadButtonUrl, headers)).execute().use { it.asJsoup() }
        val torrentFileUrl = downloadPage.selectFirst("a.download-button")?.attr("abs:href")

        if (torrentFileUrl.isNullOrEmpty()) throw Exception("No Torrent Found!")

        return try {
            val torrent = TorrentUtils.getTorrentInfo(torrentFileUrl, "torrent")
            val videoFiles = torrent.files
                .filter { it.path.substringAfterLast('.').lowercase(Locale.ROOT) in validVideoExtensions }
            if (videoFiles.isEmpty()) throw Exception(noVideoMessage(torrent.files.map { it.path }))

            val torrentMagnetLink = "magnet:?xt=urn:btih:${torrent.hash}&dn=${torrent.hash}"
            val trackers = (fetchTrackers() + torrent.trackers).filter { it.isNotBlank() }.distinct()
            val torrentTrackers = trackers.joinToString("") { "&tr=$it" }
            var episodeNumber = 1F
            videoFiles
                .map { file ->
                    SEpisode.create().apply {
                        name = if (preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)) {
                            file.path.split('/').last().trim()
                        } else {
                            file.path.trim().replace("[", "(").replace("]", ")").replace("/", "\uD83D\uDCC2 ")
                        }
                        url = "$torrentMagnetLink$torrentTrackers&index=${file.indexFile}"
                        episode_number = episodeNumber++
                        scanlator = convertBytesToReadable(file.size)
                    }
                }.reversed()
        } catch (_: SocketTimeoutException) {
            throw Exception("Dead Torrent \uD83D\uDE35")
        }
    }

    private fun noVideoMessage(paths: List<String>): String {
        val kinds = paths.map { it.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT) }
            .filter { it.length in 1..4 }
            .distinct()
            .take(5)
        val contents = if (kinds.isEmpty()) "" else " (it contains ${kinds.joinToString()} files)"
        return "This torrent has no video files$contents. It's probably a game, artwork or manga."
    }

    private val validVideoExtensions = setOf("mp4", "mov", "avi", "wmv", "mkv", "flv", "webm", "mpeg", "mpg", "mts", "vob", "ts")

    @SuppressLint("DefaultLocale")
    private fun convertBytesToReadable(bytes: Long): String {
        val kilobytes = bytes / 1024.0
        val megabytes = kilobytes / 1024.0
        val gigabytes = megabytes / 1024.0

        return when {
            gigabytes >= 1 -> String.format("%.2f GB", gigabytes)
            megabytes >= 1 -> String.format("%.2f MB", megabytes)
            else -> String.format("%.2f KB", kilobytes)
        }
    }

    // Extra public trackers help find peers; the torrent's own trackers are enough without them.
    private fun fetchTrackers(): List<String> = runCatching {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/ngosang/trackerslist/refs/heads/master/trackers_all_http.txt")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body.string().lines().map(String::trim)
        }
    }.getOrDefault(emptyList())

    override fun episodeFromElement(element: Element) = throw Exception("Not used")

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = listOf(Video(episode.url, episode.name, episode.url))

    override fun videoListSelector() = throw Exception("Not used")

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun videoUrlParse(document: Document) = throw Exception("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = VIDEO_ONLY_KEY
            title = "Only show video torrents"
            setDefaultValue(VIDEO_ONLY_DEFAULT)
            summary = "Hide games, artwork and manga, which have no playable episodes."
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_FILENAME_KEY
            title = "Only display filename"
            setDefaultValue(IS_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Will note display full path of episode."
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_IMG_KEY
            title = "Display Images in episode list."
            setDefaultValue(IS_IMG_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Its an experimental option."
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_AUDIO_KEY
            title = "Display Audio in episode list."
            setDefaultValue(IS_AUDIO_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Its an experimental option."
        }.also(screen::addPreference)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoriesList(categoryName),
    )

    private data class Category(val name: String, val id: String?)
    private class CategoriesList(category: Array<String>) : AnimeFilter.Select<String>("category", category)
    private val categoryName = getCategory().map {
        it.name
    }.toTypedArray()
    private fun getCategory(): List<Category> = listOf(
        Category("Home", "0"),
        Category("Cartoons", "Cartoons"),
        Category("2D video Hentai", "2D%20video%20Hentai"),
        Category("3D video Hentai", "3D%20video%20Hentai"),
        Category("Hentai DVD HD", "Hentai%20DVD%20HD"),
        Category("Main Subsection Hentai", "Main%20Subsection%20Hentai"),
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PAGE_SIZE = 60
        private const val MAX_PAGE_SKIP = 5

        private const val VIDEO_ONLY_KEY = "video_only"
        private const val VIDEO_ONLY_DEFAULT = true

        private val NON_VIDEO_CATALOGS = listOf("Game", "Artwork", "Comics", "Manga", "Software")

        // Upper-case format tags only, so genre lists ("[Full Color, Art Style, …]") don't match.
        private val NON_VIDEO_TAG = Regex("""\[(?:[^\]]*[ ,])?(?:ART|JPE?G|PNG|GIF)(?:[ ,][^\]]*)?]""")

        private const val IS_FILENAME_KEY = "filename"
        private const val IS_FILENAME_DEFAULT = false

        private const val IS_IMG_KEY = "img"
        private const val IS_IMG_DEFAULT = false

        private const val IS_AUDIO_KEY = "audio"
        private const val IS_AUDIO_DEFAULT = false
    }
}
