package eu.kanade.tachiyomi.animeextension.de.serienstream

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Serienstream :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Serienstream"

    override val baseUrl = "http://186.2.175.5"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val json: Json by injectLazy()

    // ===== POPULAR ANIME =====
    // Site redesign (2026): cards are now `a.show-card` (no more h3 title, no
    // `div.seriesListContainer`); the title lives in the `<img alt>` and the
    // image is lazy-loaded via `data-src` (eager cards just use `src`).
    override fun popularAnimeSelector(): String = "a.show-card"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/beliebte-serien")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.url = element.attr("href")
        val img = element.selectFirst("img")!!
        anime.thumbnail_url = baseUrl + img.attr("data-src").ifBlank { img.attr("src") }
        anime.title = img.attr("alt")
        return anime
    }

    // ===== LATEST ANIME =====
    // `/neu` is gone; the equivalent page is now `/neue-episoden`, a flat table
    // of recently added episodes (date/day/series/season/episode/language),
    // with no thumbnail per row.
    override fun latestUpdatesSelector(): String = "table.new-episodes-table tbody tr"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neue-episoden")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("td a")!!
        anime.url = linkElement.attr("href").substringBefore("/staffel-")
        anime.title = linkElement.text()
        return anime
    }

    // ===== SEARCH =====
    // `/ajax/search` (POST, `keyword`) is gone -> 404 "The route ajax/search
    // could not be found." The site's own search modal now calls
    // `GET /api/search/suggest?term=<query>`, returning
    // `{"shows":[{"name":...,"url":...}],"people":[...],"genres":[...]}`.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/api/search/suggest".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .build()
        return GET(url)
    }
    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val results = json.decodeFromString<JsonObject>(body)
        val shows = results["shows"]?.jsonArray ?: JsonArray(emptyList())
        val animes = shows.map { animeFromSearch(it.jsonObject) }
        return AnimesPage(animes, false)
    }

    private fun animeFromSearch(result: JsonObject): SAnime {
        val anime = SAnime.create()
        val title = result["name"]!!.jsonPrimitive.content
        val link = result["url"]!!.jsonPrimitive.content
        anime.title = title
        val thumpage = client.newCall(GET("$baseUrl$link")).execute().asJsoup()
        anime.thumbnail_url = baseUrl + thumpage.selectFirst("div.show-cover-mobile img")!!.attr("data-src")
        anime.url = link
        return anime
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // Site redesign (2026): no more `div.series-title`/`div.seriesCoverBox`/
        // `div.genres`/`p.seri_des`/`div.cast` - replaced by a plain `<h1>`, a
        // `div.show-cover-mobile img[data-src]`, `<li class="series-group">`
        // label/value pairs (Regisseur/Produzent/Besetzung/Land/Genre), and a
        // `div.series-description span.description-text`.
        anime.title = document.selectFirst("h1")!!.text()
        document.selectFirst("div.show-cover-mobile img")?.let {
            anime.thumbnail_url = baseUrl + it.attr("data-src")
        }
        anime.genre = document.select("li.series-group:contains(Genre:) a").joinToString { it.text() }
        anime.description = document.selectFirst("div.series-description span.description-text")?.text()
        document.selectFirst("li.series-group:contains(Produzent:)")?.let {
            anime.author = it.select("a").joinToString { a -> a.text() }
        }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ===== EPISODE =====
    // The old `#stream > ul` season nav + `table.seasonEpisodesList` are gone.
    // Seasons are now listed as `#season-nav a[data-season-pill]`, and each
    // season page lists its episodes as `tr.episode-row` (number in
    // `th.episode-number-cell`, German title in `strong.episode-title-ger`,
    // and the episode URL only available via the row's `onclick` handler,
    // e.g. onclick="window.location='/serie/all-american/staffel-1/episode-1'").
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seasonsElements = document.select("#season-nav a[data-season-pill]")
        seasonsElements.forEach {
            episodeList.addAll(parseEpisodesFromSeries(it))
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonNum = element.attr("data-season-pill")
        val seasonId = element.attr("abs:href")
        val episodesHtml = client.newCall(GET(seasonId)).execute().asJsoup()
        val episodeElements = episodesHtml.select("tr.episode-row")
        return episodeElements.map { episodeFromRow(it, seasonNum) }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromRow(element: Element, seasonNum: String): SEpisode {
        val episode = SEpisode.create()
        episode.url = element.attr("onclick").substringAfter("'").substringBefore("'")
        val num = element.selectFirst("th.episode-number-cell")?.text()?.toFloatOrNull() ?: 0f
        episode.episode_number = num
        val title = element.selectFirst("strong.episode-title-ger")?.text().orEmpty()
        episode.name = "Staffel $seasonNum Folge ${num.toInt()} : $title"
        return episode
    }

    // ===== VIDEO SOURCES =====
    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val redirectlink = document.select("div.hosterSiteVideo ul.row li")
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet(SConstants.HOSTER_SELECTION, null)
        redirectlink.forEach {
            val langkey = it.attr("data-lang-key")
            val language = getlanguage(langkey)
            val redirectgs = baseUrl + it.selectFirst("a.watchEpisode")!!.attr("href")
            val hoster = it.select("a h4").text()
            if (hosterSelection != null) {
                when {
                    hoster.contains("VOE") && hosterSelection.contains(SConstants.NAME_VOE) -> {
                        val url = client.newCall(GET(redirectgs)).execute().request.url.toString()
                        videoList.addAll(VoeExtractor(client, headers).videosFromUrl(url, "($language) "))
                    }

                    hoster.contains("Doodstream") && hosterSelection.contains(SConstants.NAME_DOOD) -> {
                        val quality = "Doodstream $language"
                        val url = client.newCall(GET(redirectgs)).execute().request.url.toString()
                        val video = DoodExtractor(client).videoFromUrl(url, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }

                    hoster.contains("Streamtape") && hosterSelection.contains(SConstants.NAME_STAPE) -> {
                        val quality = "Streamtape $language"
                        val url = client.newCall(GET(redirectgs)).execute().request.url.toString()
                        val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                }
            }
        }
        return videoList
    }

    private fun getlanguage(langkey: String): String? {
        when {
            langkey.contains("${SConstants.KEY_GER_SUB}") -> {
                return "Deutscher Sub"
            }

            langkey.contains("${SConstants.KEY_GER_DUB}") -> {
                return "Deutscher Dub"
            }

            langkey.contains("${SConstants.KEY_ENG_SUB}") -> {
                return "Englischer Sub"
            }

            else -> {
                return null
            }
        }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(SConstants.PREFERRED_HOSTER, null)
        val subPreference = preferences.getString(SConstants.PREFERRED_LANG, "Sub")!!
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else {
            otherList += this
        }
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        for (video in otherList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }

        return newList
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ===== PREFERENCES ======
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = SConstants.PREFERRED_HOSTER
            title = "Standard-Hoster"
            entries = SConstants.HOSTER_NAMES
            entryValues = SConstants.HOSTER_URLS
            setDefaultValue(SConstants.URL_STAPE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = SConstants.PREFERRED_LANG
            title = "Bevorzugte Sprache"
            entries = SConstants.LANGS
            entryValues = SConstants.LANGS
            setDefaultValue(SConstants.LANG_GER_SUB)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hosterSelection = MultiSelectListPreference(screen.context).apply {
            key = SConstants.HOSTER_SELECTION
            title = "Hoster auswählen"
            entries = SConstants.HOSTER_NAMES
            entryValues = SConstants.HOSTER_NAMES
            setDefaultValue(SConstants.HOSTER_NAMES.toSet())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(hosterSelection)
    }
}
