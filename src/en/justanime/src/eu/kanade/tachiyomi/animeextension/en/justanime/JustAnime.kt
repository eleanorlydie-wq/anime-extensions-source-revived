package eu.kanade.tachiyomi.animeextension.en.justanime

import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class JustAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "JustAnime"

    override val baseUrl = "https://justanime.to"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val apiUrl = "https://core.justanime.to/api"

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int) = searchRequest(page)

    override fun popularAnimeParse(response: Response) = searchParse(response)

    override fun latestUpdatesRequest(page: Int) = searchRequest(page, "status" to "RELEASING", "sort" to "updated")

    override fun latestUpdatesParse(response: Response) = searchParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = buildList {
            if (query.isNotBlank()) add("query" to query)
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> add("sort" to filter.selected)
                    is TypeFilter -> add("type" to filter.selected)
                    is StatusFilter -> add("status" to filter.selected)
                    is GenreFilter -> add("genre" to filter.state.filter { it.state }.joinToString(",") { it.name })
                    else -> {}
                }
            }
        }
        return searchRequest(page, *params.toTypedArray())
    }

    override fun searchAnimeParse(response: Response) = searchParse(response)

    private fun searchRequest(page: Int, vararg params: Pair<String, String>): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder().addQueryParameter("page", page.toString())
        params.filter { it.second.isNotEmpty() }.forEach { url.addQueryParameter(it.first, it.second) }
        return GET(url.build(), headers)
    }

    private fun searchParse(response: Response): AnimesPage {
        val data = response.parseAs<SearchDto>()
        val animes = data.results.filterNot { it.id in BLOCKED_IDS }.map { result ->
            SAnime.create().apply {
                url = "/anime/${result.id}"
                title = result.title.display
                thumbnail_url = result.cover
            }
        }
        return AnimesPage(animes, data.pageInfo.hasNextPage)
    }

    override fun getFilterList() = AnimeFilterList(SortFilter(), TypeFilter(), StatusFilter(), GenreFilter())

    override fun animeDetailsRequest(anime: SAnime) = GET(apiUrl + anime.url, headers)

    override fun getAnimeUrl(anime: SAnime) = baseUrl + anime.url

    override fun animeDetailsParse(response: Response): SAnime {
        val media = response.parseAs<DetailsDto>().data
        return SAnime.create().apply {
            title = media.title.display
            thumbnail_url = media.coverImage?.extraLarge
            description = media.description?.replace(HTML_TAG_REGEX, "")
            genre = media.genres.joinToString()
            status = when (media.status) {
                "RELEASING" -> SAnime.ONGOING
                "FINISHED" -> SAnime.COMPLETED
                "CANCELLED" -> SAnime.CANCELLED
                "HIATUS" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
        }
    }

    // Hard content block, deliberately not configurable. The API has no tag for sexualised minors and listings carry
    // no genres, so every adult or Ecchi title, plus the listed ids, is refused here before any episode is returned.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfterLast('/')
        val media = client.newCall(animeDetailsRequest(anime)).awaitSuccess().parseAs<DetailsDto>().data
        if (media.isAdult || id.toIntOrNull() in BLOCKED_IDS || media.genres.any { it.equals(BLOCKED_GENRE, true) }) {
            throw Exception("This title is blocked by the extension")
        }

        val episodes = mutableListOf<SEpisode>()
        var page = 1
        do {
            val url = "$apiUrl/anime/$id/episodes?page=$page"
            val data = client.newCall(GET(url, headers)).awaitSuccess().parseAs<EpisodesDto>()
            data.episodes.mapTo(episodes) { ep ->
                SEpisode.create().apply {
                    this.url = "/watch/$id/episode/${ep.number}"
                    episode_number = ep.number
                    val number = if (ep.number % 1 == 0f) ep.number.toInt().toString() else ep.number.toString()
                    name = ep.title?.takeIf(String::isNotBlank)?.let { "Episode $number: $it" } ?: "Episode $number"
                }
            }
            page++
        } while (data.hasNextPage)
        return episodes.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = PROVIDERS.parallelCatchingFlatMap { provider ->
        val url = apiUrl + episode.url + "/$provider"
        val data = client.newCall(GET(url, headers)).awaitSuccess().parseAs<WatchDto>()
        val label = provider.replaceFirstChar(Char::uppercase)
        data.sub.toVideos("$label - Sub") + data.dub.toVideos("$label - Dub")
    }.sort()

    private fun StreamDto?.toVideos(label: String): List<Video> {
        if (this == null) return emptyList()
        val videoHeaders = this@JustAnime.headers.newBuilder().removeAll("Origin").removeAll("Referer").apply {
            headers.orEmpty().forEach { (name, value) -> set(name, value) }
        }.build()
        val tracks = subtitles.filter { it.kind == "captions" && it.file != null }
            .map { Track(it.file!!, it.label ?: "Unknown") }
        return sources.mapNotNull { source ->
            val url = source.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            Video(url, label, url, headers = videoHeaders, subtitleTracks = tracks)
        }
    }

    override fun videoListParse(response: Response) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.startsWith(server, true) }
                .thenByDescending { it.quality.endsWith(type, true) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val servers = PROVIDERS.map { it.replaceFirstChar(Char::uppercase) }
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = servers,
            entryValues = servers,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred audio",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("Sub", "Dub"),
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )
    }

    private open class SelectFilter(name: String, private val options: List<Pair<String, String>>) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected get() = options[state].second
    }

    private class SortFilter :
        SelectFilter(
            "Sort",
            listOf(
                "Default" to "",
                "Trending" to "trending",
                "Top rated" to "rating",
                "Recently updated" to "updated",
                "Recently completed" to "completed",
                "Newest" to "newest",
                "Most favourited" to "favourites",
            ),
        )

    private class TypeFilter :
        SelectFilter(
            "Type",
            listOf("All" to "", "TV" to "TV", "Movie" to "MOVIE", "OVA" to "OVA", "ONA" to "ONA", "Special" to "SPECIAL"),
        )

    private class StatusFilter :
        SelectFilter(
            "Status",
            listOf("All" to "", "Airing" to "RELEASING", "Finished" to "FINISHED", "Upcoming" to "NOT_YET_RELEASED"),
        )

    private class GenreOption(name: String) : AnimeFilter.CheckBox(name)

    private class GenreFilter : AnimeFilter.Group<GenreOption>("Genres", GENRES.map(::GenreOption))

    @Serializable
    private class SearchDto(val results: List<ResultDto> = emptyList(), val pageInfo: PageInfoDto)

    @Serializable
    private class PageInfoDto(val hasNextPage: Boolean = false)

    @Serializable
    private class ResultDto(val id: Int, val title: TitleDto, val cover: String? = null)

    @Serializable
    private class TitleDto(val english: String? = null, val romaji: String? = null) {
        val display get() = english?.takeIf(String::isNotBlank) ?: romaji.orEmpty()
    }

    @Serializable
    private class DetailsDto(val data: MediaDto)

    @Serializable
    private class MediaDto(
        val title: TitleDto,
        val description: String? = null,
        val coverImage: CoverDto? = null,
        val status: String? = null,
        val genres: List<String> = emptyList(),
        val isAdult: Boolean = false,
    )

    @Serializable
    private class CoverDto(val extraLarge: String? = null)

    @Serializable
    private class EpisodesDto(val episodes: List<EpisodeDto> = emptyList(), val hasNextPage: Boolean = false)

    @Serializable
    private class EpisodeDto(val number: Float, val title: String? = null)

    @Serializable
    private class WatchDto(val sub: StreamDto? = null, val dub: StreamDto? = null)

    @Serializable
    private class StreamDto(
        val sources: List<SourceDto> = emptyList(),
        val subtitles: List<SubtitleDto> = emptyList(),
        val headers: Map<String, String>? = null,
    )

    @Serializable
    private class SourceDto(val url: String? = null)

    @Serializable
    private class SubtitleDto(val file: String? = null, val label: String? = null, val kind: String? = null)

    companion object {
        private const val BLOCKED_GENRE = "Ecchi"

        // Kodomo no Jikan, Mitsudomoe and Koi Kaze: some of these lack the Ecchi genre.
        private val BLOCKED_IDS = setOf(2403, 3121, 4195, 7627, 9396, 9510, 634)

        private val HTML_TAG_REGEX = Regex("<[^>]+>")

        // The site also lists revo and gigi, left out because their streams could not be confirmed as playable.
        // Providers come and go, and failures are skipped.
        private val PROVIDERS = listOf("momo", "nami", "calm", "onyx", "zone", "susi")
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Momo"
        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "Sub"

        private val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery",
            "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
        )
    }
}
