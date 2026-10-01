package eu.kanade.tachiyomi.animeextension.all.xnxx

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Xnxx :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Xnxx"

    override val baseUrl = "https://www.xnxx.com"

    override val lang = "all"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    override fun popularAnimeSelector(): String = "div.thumb-block.video"

    override fun popularAnimeRequest(page: Int): Request {
        // The current month's "best of" page 500s until the site has enough data for it
        // (e.g. on the first days of a month), so always use the last full month.
        val sdf = SimpleDateFormat("yyyy-MM", Locale.US)
        val lastMonth = sdf.format(Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.time)
        return GET("$baseUrl/best/$lastMonth/${page - 1}")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain("$baseUrl${element.select("div.thumb > a").attr("href")}")
        anime.title = element.select("div.thumb-under a.title").text()
        anime.thumbnail_url = element.select("div.thumb img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.next"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val episode = SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        }
        episodes.add(episode)
        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.select("script:containsData(html5player.setVideo)").joinToString("\n") { it.data() }

        val videos = listOfNotNull(
            "Low" to script.playerUrl("setVideoUrlLow"),
            "HLS" to script.playerUrl("setVideoHLS"),
            "High" to script.playerUrl("setVideoUrlHigh"),
        ).mapNotNull { (quality, url) -> url?.let { Video(it, quality, it) } }

        if (videos.isNotEmpty()) return videos

        // The player script no longer carries the stream urls; the page still links the SD file
        // in its no-JS fallback and in its JSON-LD.
        val fallback = document.select("#html5video_base a[href*=.mp4]").map { it.attr("abs:href") }
            .plus(document.select("script[type=application/ld+json]").mapNotNull { contentUrlRegex.find(it.data())?.groupValues?.get(1) })
            .filter(String::isNotBlank)
            .distinct()

        return fallback.map { Video(it, "SD", it) }
            .ifEmpty { throw Exception("No videos found") }
    }

    private val contentUrlRegex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")

    private fun String.playerUrl(setter: String): String? = Regex("""html5player\.$setter\('([^']+)'\)""").find(this)?.groupValues?.get(1)

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "HLS")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val tagFilter = filters.find { it is Tags } as Tags
        val calcPage = page - 1
        return when {
            query.isNotBlank() -> GET("$baseUrl/search/hits/$query/$calcPage", headers)
            tagFilter.state.isNotBlank() -> GET("$baseUrl/search/hits/${tagFilter.state}/$calcPage")
            else -> popularAnimeRequest(page)
        }
    }
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("#video-content-metadata > div.clear-infobar strong").text()
        anime.author = document.select("#video-content-metadata > div.clear-infobar span a").text()
        anime.description = document.select("#video-content-metadata > p").text().replace("\n", "")
        anime.genre = document.select("#video-content-metadata > div.metadata-row.video-tags > a").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Search by text does not affect the filter"),
        Tags("Tag"),
    )

    internal class Tags(name: String) : AnimeFilter.Text(name)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("High", "Low", "HLS")
            entryValues = arrayOf("High", "Low", "HLS")
            setDefaultValue("HLS")
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
}
