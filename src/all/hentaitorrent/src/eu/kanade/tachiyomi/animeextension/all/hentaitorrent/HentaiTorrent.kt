package eu.kanade.tachiyomi.animeextension.all.hentaitorrent

import android.annotation.SuppressLint
import android.app.Application
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
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelMap
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

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

    // The tracker mixes videos with games, artwork, manga and archives, and its labels are
    // unreliable, so every entry is checked against its .torrent's file list and only posts that
    // are mostly video are listed.
    private suspend fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select(popularAnimeSelector())
        val checked = cards
            .filterNot { it.isNonVideoCatalog() }
            .parallelMap { it to isVideoPost(it) }
        saveVerdicts()
        val animes = checked.filter { it.second }.map { popularAnimeFromElement(it.first) }
        // Catalog pages render an empty pagination block, so a full page is the only "more" signal.
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null || cards.size >= PAGE_SIZE
        return AnimesPage(animes, hasNextPage)
    }

    private fun Element.isNonVideoCatalog(): Boolean {
        val catalog = selectFirst("div.catalog")?.text().orEmpty()
        return NON_VIDEO_CATALOGS.any { catalog.contains(it, ignoreCase = true) }
    }

    private fun Element.isNonVideoTitle(): Boolean = NON_VIDEO_TAG.containsMatchIn(selectFirst("a.overlay")?.text().orEmpty())

    // Verdicts per post (slug), kept across sessions: a post's torrent doesn't change.
    private val verdicts = ConcurrentHashMap<String, Boolean>()
    private val newVerdicts = ConcurrentHashMap<String, Boolean>()
    private val verdictPrefs by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_verdicts", 0x0000)
    }

    private suspend fun isVideoPost(card: Element): Boolean {
        val postUrl = card.selectFirst("a.overlay")?.attr("abs:href").orEmpty()
        if (postUrl.isEmpty()) return false
        val key = postUrl.substringAfterLast('/')
        verdicts[key]?.let { return it }
        if (verdictPrefs.contains(key)) {
            return verdictPrefs.getBoolean(key, false).also { verdicts[key] = it }
        }
        // Network trouble: fall back to the title's format tag, and check again next time.
        val verdict = try {
            checkTorrent(card, postUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return !card.isNonVideoTitle()
        }
        verdicts[key] = verdict
        newVerdicts[key] = verdict
        return verdict
    }

    private fun saveVerdicts() {
        if (newVerdicts.isEmpty()) return
        val pending = HashMap(newVerdicts)
        newVerdicts.keys.removeAll(pending.keys)
        val editor = verdictPrefs.edit()
        if (verdictPrefs.all.size + pending.size > MAX_SAVED_VERDICTS) editor.clear()
        pending.forEach { (key, value) -> editor.putBoolean(key, value) }
        editor.apply()
    }

    /** Whether the post's torrent is mostly video. Dead and missing torrents count as not. */
    private suspend fun checkTorrent(card: Element, postUrl: String): Boolean {
        // Thumbnails are named after the torrent id (".../mini/202610/3313954_a1-500.jpg"),
        // which saves the two page loads behind the download button.
        val torrentId = THUMBNAIL_ID.find(card.selectFirst("img")?.attr("src").orEmpty())?.groupValues?.get(1)
        val bytes = torrentId?.let { downloadTorrent(torrentUrl(it)) }
            ?: resolveTorrentUrl(postUrl)?.let { downloadTorrent(it) }
            ?: return false
        val files = TorrentContents.parse(bytes)
        val total = files.sumOf { it.size }
        val video = files.filter { it.extension in validVideoExtensions }.sumOf { it.size }
        return total > 0 && video * 2 >= total
    }

    private fun torrentUrl(torrentId: String) = "https://h.hentaitorrents.com/bt/%5Bhentaitorrents.com%5D.$torrentId.torrent"

    /** Detail page -> download page -> .torrent link; null when the post has none. */
    private suspend fun resolveTorrentUrl(postUrl: String): String? {
        val downloadPageUrl = client.newCall(GET(postUrl, headers)).awaitSuccess().use { it.asJsoup() }
            .selectFirst("div.download-container a.download-button")?.attr("abs:href")
            .takeUnless { it.isNullOrEmpty() } ?: return null
        return client.newCall(GET(downloadPageUrl, headers)).awaitSuccess().use { it.asJsoup() }
            .selectFirst("a.download-button")?.attr("abs:href")
            .takeUnless { it.isNullOrEmpty() }
    }

    /** The .torrent's bytes, or null when it's gone (404 or not a torrent). */
    private suspend fun downloadTorrent(url: String): ByteArray? = client.newCall(GET(url, headers)).await().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val source = response.body.source()
        if (source.request(MAX_TORRENT_SIZE + 1)) throw IOException("Torrent file too large")
        source.buffer.readByteArray().takeIf { it.isNotEmpty() && it[0] == 'd'.code.toByte() }
    }

    // An empty page reads as "no results" in the browse screen, so skip ahead (bounded) past
    // pages whose entries were all filtered out.
    private suspend fun fetchListing(page: Int, request: (Int) -> Request): AnimesPage {
        var current = page
        while (true) {
            val result = client.newCall(request(current)).awaitSuccess().use { parseListing(it) }
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

    private val validVideoExtensions = setOf("mp4", "m4v", "mov", "avi", "wmv", "mkv", "flv", "webm", "mpeg", "mpg", "mts", "m2ts", "vob", "ts")

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

        private const val MAX_SAVED_VERDICTS = 4000
        private const val MAX_TORRENT_SIZE = 16L * 1024 * 1024

        private val THUMBNAIL_ID = Regex("""/mini/\d+/(\d+)[a-z]?(?:_a\d+)?-\d+\.\w+$""")

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
