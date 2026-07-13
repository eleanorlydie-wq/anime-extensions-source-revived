package eu.kanade.tachiyomi.animeextension.it.hentaisaturn

import android.util.Base64
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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiSaturn :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiSaturn"

    override val baseUrl = "https://www.hentaisaturn.tv"

    override val lang = "it"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private fun formatTitle(titlestring: String): String = titlestring.replace("(ITA) ITA", "Dub ITA").replace("(ITA)", "Dub ITA").replace("Sub ITA", "")

    // ============================== Popular ==============================

    override fun popularAnimeSelector(): String = "a.hs-rankrow"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/toplist")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val poster = element.selectFirst("img.hs-rankrow__poster")!!
        anime.title = formatTitle(poster.attr("alt"))
        anime.thumbnail_url = poster.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.hs-pagenum[rel=next]"

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = "a.ep-tile"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        val epText = element.attr("title")
        val epNumber = epText.substringAfter("Episodio ")
        episode.episode_number = if (epNumber.contains("-", true)) {
            epNumber.substringBefore("-").toFloatOrNull() ?: 0F
        } else {
            epNumber.toFloatOrNull() ?: 0F
        }
        episode.name = epText
        return episode
    }

    // ============================== Video ==============================

    // Stashed here by videoListRequest so videoListParse can undo the XOR
    // obfuscation the player API applies to the "d" (direct link) field.
    private var embedTokenKey = ""

    override fun videoListRequest(episode: SEpisode): Request {
        val episodePage = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val watchUrl = episodePage.selectFirst("a.ept-btn--play")!!.attr("href")
        val watchPage = client.newCall(GET(baseUrl + watchUrl)).execute().asJsoup()
        val iframeUrl = watchPage.selectFirst("iframe#watch-iframe")!!.attr("src")
        val embedHtml = client.newCall(GET(iframeUrl)).execute().body.string()
        val embedMatch = Regex("""window\.__E=\{i:(\d+),k:"([^"]+)",e:(\d+)\}""").find(embedHtml)
            ?: throw Exception("Player non trovato")
        val (embedId, embedKey, embedExpires) = embedMatch.destructured
        embedTokenKey = embedKey
        return GET(
            "https://play.hentaisaturn.tv/embed/$embedId/playlist?token=$embedKey&expires=$embedExpires",
            headers = Headers.headersOf("Referer", "https://play.hentaisaturn.tv/"),
        )
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val json = JSONObject(response.body.string())
        val encoded = json.optString("d")
        if (encoded.isEmpty()) return emptyList()
        val decodedBytes = Base64.decode(encoded, Base64.DEFAULT)
        val keyBytes = embedTokenKey.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(decodedBytes.size) { i ->
            (decodedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        val videoUrl = String(xored, Charsets.UTF_8)
        return listOf(Video(videoUrl, "Predefinita", videoUrl))
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val qualityList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (video.quality.contains(quality)) {
                qualityList.add(preferred, video)
                preferred++
            } else {
                qualityList.add(video)
            }
        }
        return qualityList
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ======================= Search / Latest / Details =======================

    private fun animeFromCard(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = formatTitle(element.selectFirst("h3.hs-card__title")!!.text())
        anime.thumbnail_url = element.selectFirst("div.hs-card__media img")!!.attr("src")
        return anime
    }

    override fun searchAnimeFromElement(element: Element): SAnime = animeFromCard(element)

    override fun searchAnimeNextPageSelector(): String = "a.hs-pagenum[rel=next]"

    override fun searchAnimeSelector(): String = "a.hs-card"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return if (parameters.isEmpty()) {
            GET("$baseUrl/filter/$page?key=$query")
        } else {
            GET("$baseUrl/filter/$page?$parameters")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = formatTitle(document.selectFirst("div.hs-dhero h1")!!.text())
        anime.thumbnail_url = document.selectFirst("div.hs-dposter img")!!.attr("src")
        anime.genre = document.select("div.hs-dhero a.hs-chip").joinToString { it.text() }
        anime.description = document.selectFirst("section:has(h2:contains(Trama)) div")?.text()
        val statusText = document.select("div.hs-info-row:has(dt:contains(Stato)) dd").text()
        anime.status = parseStatus(statusText)
        val studio = document.select("div.hs-info-row:has(dt:contains(Studio)) dd").text()
        if (studio.isNotEmpty()) {
            anime.author = studio
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("In corso") -> {
            SAnime.ONGOING
        }

        statusString.contains("Finito") -> {
            SAnime.COMPLETED
        }

        else -> {
            SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesSelector(): String = "a.hs-card"

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromCard(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest/$page")

    override fun latestUpdatesNextPageSelector(): String = "a.hs-pagenum[rel=next]"

    // Filters
    internal class Genre(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("1", "3D"),
        Genre("2", "Ahegao"),
        Genre("3", "Anal"),
        Genre("4", "BDSM"),
        Genre("5", "Big Boobs"),
        Genre("6", "Blow Job"),
        Genre("7", "Bondage"),
        Genre("8", "Boob Job"),
        Genre("9", "Censored"),
        Genre("10", "Comedy"),
        Genre("11", "Cosplay"),
        Genre("12", "Creampie"),
        Genre("13", "Dark Skin"),
        Genre("14", "Facial"),
        Genre("15", "Fantasy"),
        Genre("16", "Filmed"),
        Genre("17", "Foot Job"),
        Genre("18", "Futanari"),
        Genre("19", "Gangbang"),
        Genre("20", "Glasses"),
        Genre("21", "Hand Job"),
        Genre("22", "Harem"),
        Genre("25", "Horror"),
        Genre("26", "Incest"),
        Genre("27", "Inflation"),
        Genre("29", "Lactation"),
        Genre("30", "Loli"),
        Genre("31", "Maid"),
        Genre("32", "Masturbation"),
        Genre("33", "Milf"),
        Genre("34", "Mind Break"),
        Genre("35", "Mind Control"),
        Genre("36", "Monster"),
        Genre("37", "NTR"),
        Genre("38", "Nurse"),
        Genre("39", "Orgy"),
        Genre("40", "Plot"),
        Genre("41", "POV"),
        Genre("42", "Pregnant"),
        Genre("43", "Public Sex"),
        Genre("44", "Rape"),
        Genre("45", "Reverse Rape"),
        Genre("46", "Rimjob"),
        Genre("48", "Scat"),
        Genre("49", "School Girl"),
        Genre("51", "Shota"),
        Genre("52", "Softcore"),
        Genre("53", "Swimsuit"),
        Genre("54", "Teacher"),
        Genre("55", "Tentacle"),
        Genre("56", "Threesome"),
        Genre("57", "Toys"),
        Genre("58", "Trap"),
        Genre("59", "Tsundere"),
        Genre("60", "Ugly Bastard"),
        Genre("61", "Uncensored"),
        Genre("62", "Vanilla"),
        Genre("63", "Virgin"),
        Genre("64", "Watersports"),
        Genre("65", "X-Ray"),
        Genre("66", "Yaoi"),
        Genre("67", "Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)
    private fun getYears() = (2026 downTo 1960).map { Year(it.toString()) }

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)
    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Lang(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class LangList(langs: List<Lang>) : AnimeFilter.Group<Lang>("Lingua", langs)
    private fun getLangs() = listOf(
        Lang("0", "Sottotitolato"),
        Lang("1", "Doppiato"),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ricerca per titolo ignora i filtri e viceversa"),
        GenreList(getGenres()),
        YearList(getYears()),
        StateList(getStates()),
        LangList(getLangs()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        val params = mutableListOf<String>()
        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            params.add("categories%5B%5D=" + genre.id)
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { year ->
                        if (year.state) {
                            params.add("years%5B%5D=" + year.id)
                        }
                    }
                }

                is StateList -> { // ---State
                    filter.state.forEach { state ->
                        if (state.state) {
                            params.add("states%5B%5D=" + state.id)
                        }
                    }
                }

                is LangList -> { // ---Lang (sub vs dub)
                    filter.state.forEach { lang ->
                        if (lang.state) {
                            params.add("dub=" + lang.id)
                        }
                    }
                }

                else -> {}
            }
        }
        return params.joinToString("&")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualità preferita"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "144")
            setDefaultValue("1080")
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
