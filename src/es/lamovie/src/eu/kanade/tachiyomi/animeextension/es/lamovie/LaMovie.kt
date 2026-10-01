package eu.kanade.tachiyomi.animeextension.es.lamovie

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.goodstramextractor.GoodStreamExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.es.lamovie.extractors.VimeosExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapBlocking
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class LaMovie :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "LaMovie"

    override val id: Long = 5419283741928374105

    override val baseUrl = "https://lamovie.org"

    private val apiUrl = "https://tmdb.lamovie.org/v1"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/top?page=$page&limit=$PAGE_SIZE", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = response.parseAs<ListingDto>().toAnimesPage()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/now?page=$page&limit=$PAGE_SIZE", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora los filtros"),
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter :
        UriPartFilter(
            "Tipo",
            arrayOf(
                Pair("Todos", ""),
                Pair("Peliculas", "movie"),
                Pair("Series", "tvshow"),
                Pair("Animes", "anime"),
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genero",
            arrayOf(
                Pair("<Selecionar>", ""),
                Pair("Acción", "acción"),
                Pair("Animación", "animación"),
                Pair("Aventura", "aventura"),
                Pair("Ciencia ficción", "ciencia ficción"),
                Pair("Comedia", "comedia"),
                Pair("Crimen", "crimen"),
                Pair("Documental", "documental"),
                Pair("Drama", "drama"),
                Pair("Familia", "familia"),
                Pair("Fantasía", "fantasía"),
                Pair("Historia", "historia"),
                Pair("Misterio", "misterio"),
                Pair("Música", "música"),
                Pair("Romance", "romance"),
                Pair("Suspense", "suspense"),
                Pair("Terror", "terror"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val kind = filters.firstNotNullOfOrNull { (it as? TypeFilter)?.toUriPart() }.orEmpty()
        val genre = filters.firstNotNullOfOrNull { (it as? GenreFilter)?.toUriPart() }.orEmpty()
        val url = if (query.isNotBlank()) {
            // The search endpoint has no paging: ask for as many results as the page needs
            apiUrl.toHttpUrl().newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", query.trim().padEnd(2))
                .addQueryParameter("limit", (page * PAGE_SIZE).coerceAtMost(MAX_SEARCH_SIZE).toString())
                .addQueryParameter("page", page.toString())
        } else {
            apiUrl.toHttpUrl().newBuilder()
                .addPathSegment("items")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .apply {
                    if (kind.isNotEmpty()) addQueryParameter("kind", kind)
                    if (genre.isNotEmpty()) addQueryParameter("genre", genre)
                }
        }
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url
        val dto = response.parseAs<ListingDto>()
        if (url.pathSegments.last() != "search") return dto.toAnimesPage()

        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val limit = url.queryParameter("limit")?.toIntOrNull() ?: PAGE_SIZE
        val items = dto.items.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val hasNext = dto.items.size >= limit && limit < MAX_SEARCH_SIZE && (dto.total ?: 0) > page * PAGE_SIZE
        return AnimesPage(items.map { it.toSAnime() }, hasNext)
    }

    private fun ListingDto.toAnimesPage() = AnimesPage(items.map { it.toSAnime() }, pagination?.hasNext ?: false)

    private fun ItemDto.toSAnime() = SAnime.create().apply {
        url = "$kind/$tmdbId"
        title = this@toSAnime.title
        thumbnail_url = posterPath?.let { "$IMAGE_URL$it" }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/items/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val item = response.parseAs<ItemResponseDto>().item
        return item.toSAnime().apply {
            description = item.overview
            genre = item.genres.joinToString { it.title }.ifBlank { null }
            status = when (item.status) {
                "Returning Series", "In Production" -> SAnime.ONGOING
                "Ended", "Released", "Canceled" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = if (anime.url.startsWith("movie/")) {
        GET("$apiUrl/items/${anime.url}", headers)
    } else {
        GET("$apiUrl/items/${anime.url}/seasons", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val path = response.request.url.pathSegments
        val kind = path[path.indexOf("items") + 1]
        val id = path[path.indexOf("items") + 2]
        if (kind == "movie") {
            return listOf(
                SEpisode.create().apply {
                    name = "PELÍCULA"
                    url = "movie/$id"
                    episode_number = 1f
                },
            )
        }

        return response.parseAs<SeasonsDto>().seasons
            .filter { it.playableCount > 0 || it.availableCount > 0 }
            .parallelMapBlocking { season ->
                val detail = client.newCall(GET("$apiUrl/items/$kind/$id/seasons/${season.season}", headers))
                    .execute()
                    .parseAs<SeasonDetailDto>()
                detail.season.episodes.filter { it.playable }
            }
            .flatten()
            .map { ep ->
                SEpisode.create().apply {
                    url = "$kind/$id?season=${ep.season}&episode=${ep.episode}"
                    name = "T${ep.season} - E${ep.episode}" + ep.title?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    episode_number = ep.season * 1000f + ep.episode
                    date_upload = ep.airDate?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() } ?: 0L
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client, headers) }
    private val vimeosExtractor by lazy { VimeosExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListRequest(episode: SEpisode): Request = GET("$apiUrl/playback/${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> = response.parseAs<PlaybackDto>().embeds
        .parallelCatchingFlatMapBlocking { embed ->
            val langs = embed.lang.orEmpty().lowercase()
            val prefix = when {
                langs.contains("sub") || langs.contains("japon") -> "[SUB]"
                langs.contains("latino") -> "[LAT]"
                else -> "[CAST]"
            }
            val url = embed.url
            val server = "${embed.host.orEmpty()} $url".lowercase()
            when {
                server.contains("streamtape") || server.contains("stp") || server.contains("stape") -> {
                    listOfNotNull(streamTapeExtractor.videoFromUrl(url, quality = "$prefix StreamTape"))
                }

                server.contains("voe") -> voeExtractor.videosFromUrl(url, "$prefix ")

                server.contains("filemoon") -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")

                server.contains("vimeos") -> vimeosExtractor.videosFromUrl(url, "$prefix Vimeos")

                server.contains("wishembed") || server.contains("streamwish") || server.contains("strwish") || server.contains("wish") -> {
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                }

                server.contains("doodstream") || server.contains("dood.") || server.contains("ds2play") || server.contains("doods.") -> {
                    doodExtractor.videosFromUrl(url, prefix)
                }

                server.contains("vidhide") || server.contains("vid.") -> {
                    vidHideExtractor.videosFromUrl(url) { "$prefix - VidHide:$it" }
                }

                server.contains("goodstream") || server.contains("vidstream") -> {
                    goodStreamExtractor.videosFromUrl(url, "$prefix GoodStream")
                }

                else -> universalExtractor.videosFromUrl(url, headers, prefix = prefix)
            }
        }
        .sortVideos()

    private fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // =========================== Preferences =============================

    companion object {
        private const val PAGE_SIZE = 24
        private const val MAX_SEARCH_SIZE = 100
        private const val IMAGE_URL = "https://image.tmdb.org/t/p/w500"
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("Latino", "Castellano", "Subtitulado")
        private val LANGUAGE_VALUES = arrayOf("[LAT]", "[CAST]", "[SUB]")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("DoodStream", "StreamTape", "Voe", "Filemoon", "StreamWish", "VidHide", "GoodStream")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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
