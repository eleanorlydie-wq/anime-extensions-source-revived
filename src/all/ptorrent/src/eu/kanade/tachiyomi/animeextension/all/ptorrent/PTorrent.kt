package eu.kanade.tachiyomi.animeextension.all.ptorrent

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

class PTorrent :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "PTorrent (Torrent)"

    override val baseUrl = "https://www.ptorrents.com"

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

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        // Open a shared/deep-linked detail URL directly. Detail pages are top-level
        // slugs (e.g. /some-title-589736.html), so the page IS the anime.
        val url = query.removePrefix(PREFIX_SEARCH)
        if (url.startsWith("https://")) {
            if (url.toHttpUrl().host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            return client.newCall(GET(url))
                .awaitSuccess()
                .use(::searchAnimeByUrlParse)
        }
        return super.getSearchAnime(page, query, filters)
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

    // Catalog pages render an empty pagination block, so a full page is the only "more" signal.
    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select(searchAnimeSelector())
        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null || cards.size >= PAGE_SIZE
        return AnimesPage(cards.map(::searchAnimeFromElement), hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // Set only when found so the browse flow's title/thumbnail (from the listing) are never blanked.
        document.selectFirst("h1")?.text()?.trim()?.let { anime.title = it }
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
        return "This torrent has no video files$contents. It's probably a picture gallery or a game."
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
            key = IS_FILENAME_KEY
            title = "Only display filename"
            setDefaultValue(IS_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Will note display full path of episode."
        }.also(screen::addPreference)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoriesList(categoryName),
    )

    private data class Category(val name: String, val id: String?)
    private class CategoriesList(category: Array<String>) : AnimeFilter.Select<String>("Category", category)
    private val categoryName = getCategory().map {
        it.name
    }.toTypedArray()
    private fun getCategory(): List<Category> = listOf(
        Category("Home", "0"),
        Category("3D and VR Movies", "3D%20and%20VR%20Movies"),
        Category("Adult Anime and Game", "Adult%20Anime%20and%20Game"),
        Category("Anime", "Anime"),
        Category("BDSM", "BDSM"),
        Category("Bisexual", "Bisexual"),
        Category("Bukkake", "Bukkake"),
        Category("Chinese Movie", "Chinese%20Movie"),
        Category("Erotic Picture Gallery", "Erotic%20Picture%20Gallery"),
        Category("Erotic Softcore Movies", "Erotic%20Softcore%20Movies"),
        Category("Femdom and Strapon", "Femdom%20and%20Strapon"),
        Category("Fetish", "Fetish"),
        Category("Fisting and Dildo", "Fisting%20and%20Dildo"),
        Category("Game", "Game"),
        Category("Japanese Movie", "Japanese%20Movie"),
        Category("Peeing", "Peeing"),
        Category("Porn Movies", "Porn%20Movies"),
        Category("Pregnant", "Pregnant"),
        Category("Special Porn Movies", "Special%20Porn%20Movies"),
        Category("Transsexual", "Transsexual"),
        Category("Voyeur", "Voyeur"),
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PAGE_SIZE = 60

        private const val IS_FILENAME_KEY = "filename"
        private const val IS_FILENAME_DEFAULT = false
    }
}
