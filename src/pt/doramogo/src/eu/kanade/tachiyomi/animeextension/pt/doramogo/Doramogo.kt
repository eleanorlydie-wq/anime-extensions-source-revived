package eu.kanade.tachiyomi.animeextension.pt.doramogo

import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animeextension.pt.doramogo.extractors.DoramogoExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.catchingFlatMapBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Doramogo : ParsedAnimeHttpSource() {

    override val name = "Doramogo"

    override val baseUrl = "https://www.doramogo.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/dorama/pagina/$page" else "$baseUrl/dorama"
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "div.episode-card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("div.episode-info h3")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector() = "a.next-btn"

    // =============================== Latest ===============================
    // The rebuilt site (2026) no longer exposes a separate "latest" ordering,
    // so latest updates reuses the same listing as popular.
    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

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
            return client.newCall(GET("$baseUrl/series/$id"))
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

    override fun getFilterList() = DoramogoFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DoramogoFilters.getSearchParameters(filters)

        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addPathSegment(query)
            .addIfNotBlank("filter_audio", params.audio)
            .addIfNotBlank("filter_genre", params.genre)
            .build()

        return GET(url, headers = headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("div.detail h1")!!.text()
        thumbnail_url = document.selectFirst("div.thumbnail img")!!.attr("abs:src")
        description = document.selectFirst("p#sinopse-text")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "a.dorama-one-episode-item"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span.episode-title")?.text()?.trim()
            ?: element.selectFirst("span.dorama-one-episode-number")!!.text()
        val number = element.selectFirst("span.dorama-one-episode-number")?.text().orEmpty()
        episode_number = Regex("\\d+").find(number)?.value?.toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        // Legacy embed-based episodes (kept for any content still served this way).
        val iframeUrls = document.select("div.source-box iframe[src]").map { it.attr("src") }
        if (iframeUrls.isNotEmpty()) {
            return iframeUrls.catchingFlatMapBlocking { getVideosFromURL(it) }
        }

        // Current site (2026 rebuild): episode pages embed a JWPlayer instance whose
        // stream path is built from an inline `urlConfig` object, e.g.:
        //   var urlConfig = { base: "...", slug: "meu-namorado-coreano-2025",
        //                      tipo: "doramas", temporada: 1, episodio: 1 };
        //   const PRIMARY_URL = "https://ondemand.telabrasil.shop";
        //   const FALLBACK_URL = "https://forks-doramas.telabrasil.shop";
        // and the client falls back from PRIMARY_URL to FALLBACK_URL on failure.
        return videosFromPlayerConfig(document)
    }

    private fun videosFromPlayerConfig(document: Document): List<Video> {
        val script = document.select("script:containsData(urlConfig)").firstOrNull()?.data()
            ?: return emptyList()

        val slug = Regex("slug:\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1) ?: return emptyList()
        val tipo = Regex("tipo:\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1) ?: return emptyList()
        val temporada = Regex("temporada:\\s*(\\d+)").find(script)?.groupValues?.get(1)
        val episodio = Regex("episodio:\\s*(\\d+)").find(script)?.groupValues?.get(1)
        val primaryBase = Regex("PRIMARY_URL\\s*=\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)
        val fallbackBase = Regex("FALLBACK_URL\\s*=\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)

        val prefix = slug.first().uppercaseChar()
        val streamPath = if (tipo == "filmes") {
            "$prefix/$slug/stream/stream.m3u8"
        } else {
            val t = (temporada ?: "1").padStart(2, '0')
            val e = (episodio ?: "1").padStart(2, '0')
            "$prefix/$slug/$t-temporada/$e/stream.m3u8"
        }

        // Mirrors the site's own client-side fallback: try the primary CDN first,
        // then the fallback CDN if the primary stream isn't reachable/valid.
        for (base in listOfNotNull(primaryBase, fallbackBase).distinct()) {
            val streamUrl = "$base/$streamPath?nocache=${System.currentTimeMillis()}"

            val isPlayableM3u8 = runCatching {
                client.newCall(GET(streamUrl, headers)).execute().use { res ->
                    res.isSuccessful && res.peekBody(15L).string().contains("#EXTM3U")
                }
            }.getOrDefault(false)

            if (isPlayableM3u8) {
                return playlistUtils.extractFromHls(streamUrl, referer = baseUrl, videoNameGen = { "Doramogo" })
            }
        }

        return emptyList()
    }

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val doramogoExtractor by lazy { DoramogoExtractor(client, headers) }
    private val gdriveExtractor by lazy { GoogleDriveExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private suspend fun getVideosFromURL(url: String): List<Video> = when {
        "dailymotion" in url -> dailymotionExtractor.videosFromUrl(url)

        "ok.ru" in url -> okruExtractor.videosFromUrl(url)

        "drive.google.com" in url -> {
            val id = Regex("[\\w-]{28,}").find(url)?.groupValues?.get(0) ?: return emptyList()
            gdriveExtractor.videosFromUrl(id, "GDrive")
        }

        "embedrise.com" in url -> {
            val m3u8Url = client.newCall(GET(url)).execute()
                .asJsoup()
                .selectFirst("video source")?.attr("src") ?: return emptyList()
            playlistUtils.extractFromHls(
                m3u8Url,
                referer = url,
                videoNameGen = { "Embedrise - $it" },
            )
        }

        "streamable.com" in url -> {
            val mp4Url = client.newCall(GET(url)).execute()
                .asJsoup()
                .selectFirst("video")
                ?.attr("src")
                ?.let {
                    if (it.startsWith("//")) {
                        return@let "https:$it"
                    }
                    it
                }
                ?: return emptyList()
            listOf(
                Video(mp4Url, "Streamable", mp4Url, headers),
            )
        }

        "/player/" in url -> doramogoExtractor.videosFromUrl(url)

        else -> emptyList()
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
