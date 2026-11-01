package eu.kanade.tachiyomi.animeextension.en.oppaistream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.oppaistream.dto.AnilistResponseDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OppaiStream :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Oppai Stream"

    override val lang = "en"

    override val baseUrl = "https://oppai.stream"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/popular", headers)

    override fun popularAnimeParse(response: Response) = AnimesPage(
        response.asJsoup().select("a.episode[data-folder]").map(::animeFromCard).distinctBy { it.title },
        false,
    )

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/new-releases", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder().add("term", query.trim()).build()
        return POST("$baseUrl/api/search-quick.php", headers, body)
    }

    override fun searchAnimeParse(response: Response) = AnimesPage(
        response.asJsoup().select("a.search-result-hit[href*=/watch/]").map { element ->
            SAnime.create().apply {
                title = element.text()
                setUrlWithoutDomain(element.attr("abs:href").cleanWatchLink())
            }
        }.distinctBy { it.title },
        false,
    )

    private fun animeFromCard(element: Element) = SAnime.create().apply {
        val cover = element.selectFirst("img.cover")
        thumbnail_url = cover?.attr("abs:data-src")
        title = cover?.attr("alt").orEmpty().removeSuffix(" thumbnail").replace(EPISODE_SUFFIX_REGEX, "")
        setUrlWithoutDomain(element.attr("abs:href").cleanWatchLink())
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = response.asJsoup()
        // Fetch from from Anilist when "Anilist Cover" is selected in settings
        val name = document.selectFirst("h1#episode-title")!!.text().replace(EPISODE_SUFFIX_REGEX, "")
        title = name
        description = document.selectFirst("meta[name=description]")?.attr("content")
        genre = document.select("div.tags a[href*=/category/]").eachText().distinct().joinToString()
        val studios = document.select("div.tags a[href*=/studio/], a[href*=/studio/]:not(.episode)").eachText()
            .map { it.removePrefix("All Episodes by ").trim() }.filter(String::isNotBlank).distinct()
        artist = studios.joinToString()

        val useAnilistCover = preferences.getBoolean(PREF_ANILIST_COVER_KEY, PREF_ANILIST_COVER_DEFAULT)
        val thumbnailUrl = if (useAnilistCover) {
            val newTitle = name.replace(Regex("[^a-zA-Z0-9\\s!.:\"]"), " ")
            runCatching { fetchThumbnailUrlByTitle(newTitle) }.getOrNull()
        } else {
            null // Use default cover (episode preview)
        }

        // Match local studios with anilist studios to increase the accuracy of the poster
        val matchedStudio = thumbnailUrl?.second?.find { it in studios }

        thumbnail_url = matchedStudio?.let { thumbnailUrl.first }
            ?: document.selectFirst("meta[property=og:image]")?.attr("abs:content")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val others = document.select("a.other-episodes[href*=/watch/]").map { it.attr("abs:href").cleanWatchLink() }
        return (others + response.request.url.toString().cleanWatchLink()).distinct().map { link ->
            val number = link.substringAfterLast("-").toFloatOrNull() ?: 1F
            SEpisode.create().apply {
                setUrlWithoutDomain(link)
                name = "Episode ${link.substringAfterLast("-")}"
                episode_number = number
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val page = response.body.string()
        val document = Jsoup.parse(page, response.request.url.toString())
        val subtitles = document.select("video#episode track[src]").map {
            Track(it.attr("abs:src"), it.attr("label"))
        }

        val url = CONTENT_URL_REGEX.find(page)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw Exception("This episode has no video on the site")
        val quality = document.selectFirst("meta[name=video:height]")?.attr("content")?.let { "${it}p" } ?: "Default"
        return listOf(Video(url, quality, url, headers, subtitles))
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ANILIST_COVER_KEY
            title = PREF_ANILIST_COVER_TITLE
            summary = PREF_ANILIST_COVER_SUMMARY
            setDefaultValue(PREF_ANILIST_COVER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_COVER_QUALITY_KEY
            title = PREF_COVER_QUALITY_TITLE
            entries = PREF_COVER_QUALITY_ENTRIES
            entryValues = PREF_COVER_QUALITY_VALUES
            setDefaultValue(PREF_COVER_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    // Function to fetch thumbnail URL using AniList GraphQL API
    // Only use in animeDetailsParse.
    private fun fetchThumbnailUrlByTitle(title: String): Pair<String, List<String>>? {
        val query = """
            query {
                Media(search: "$title", type: ANIME, isAdult: true) {
                    coverImage {
                        extraLarge
                        large
                    }
                    studios {
                        nodes {
                            name
                        }
                    }
                }
            }
        """.trimIndent()

        val requestBody = FormBody.Builder()
            .add("query", query)
            .build()

        val request = POST("https://graphql.anilist.co", body = requestBody)

        val response = client.newCall(request).execute()

        return parseThumbnailUrlFromObject(response.parseAs<AnilistResponseDto>())
    }

    private fun parseThumbnailUrlFromObject(obj: AnilistResponseDto): Pair<String, List<String>>? {
        val media = obj.data.media ?: return null

        val coverURL = when (preferences.getString(PREF_COVER_QUALITY_KEY, PREF_COVER_QUALITY_DEFAULT)) {
            "extraLarge" -> media.coverImage.extraLarge
            else -> media.coverImage.large
        }

        val studiosList = media.studios.names

        return Pair(coverURL, studiosList)
    }

    private fun String.cleanWatchLink(): String = substringBefore("/from/").substringBefore("?")

    companion object {
        private val EPISODE_SUFFIX_REGEX = Regex("""\s+Episode\s+\d+$""")
        private val CONTENT_URL_REGEX = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_ANILIST_COVER_KEY = "preferred_anilist_cover"
        private const val PREF_ANILIST_COVER_TITLE = "Use Anilist as cover source - Beta"
        private const val PREF_ANILIST_COVER_DEFAULT = true
        private const val PREF_ANILIST_COVER_SUMMARY = "This feature is experimental. " +
            "It enables fetching covers from Anilist. If you see the default cover " +
            "after switching to AniList cover, try clearing the cache in " +
            "Settings > Advanced > Clear Anime Database > Oppai Steam. It only fetch Anilist covers in anime details page."

        private const val PREF_COVER_QUALITY_KEY = "preferred_cover_quality"
        private const val PREF_COVER_QUALITY_TITLE = "Preferred Anilist cover quality - Beta"
        private const val PREF_COVER_QUALITY_DEFAULT = "extraLarge"
        private val PREF_COVER_QUALITY_ENTRIES = arrayOf("Extra Large", "Large")
        private val PREF_COVER_QUALITY_VALUES = arrayOf("extraLarge", "large")
    }
}
