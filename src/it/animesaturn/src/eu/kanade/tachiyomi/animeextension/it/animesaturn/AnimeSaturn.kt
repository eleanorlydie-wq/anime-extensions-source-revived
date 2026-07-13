package eu.kanade.tachiyomi.animeextension.it.animesaturn

import android.util.Base64
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class AnimeSaturn :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeSaturn"

    private fun isNewDomain(): Boolean = baseUrl == DOMAIN_DEFAULT

    override val lang = "it"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        val currentDomain = getString(PREF_DOMAIN, DOMAIN_DEFAULT)!!
        if (currentDomain !in DOMAIN_VALUES) {
            edit()
                .putString(PREF_DOMAIN, DOMAIN_DEFAULT)
                .apply()
        }
    }

    override val baseUrl by preferences.delegate(PREF_DOMAIN, DOMAIN_DEFAULT)

    private val json: Json by injectLazy()

    // The current (2026) AnimeMars template renders every listing (ongoing/newest/filter results)
    // with the same card markup: a div.grid.grid-cols-2.gap-4... wrapping <a class="group block"> cards.
    private fun newDomainCardSelector(): String = "div.grid.grid-cols-2.gap-4 a.group.block"

    private fun newDomainCardFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = formatTitle(element.selectFirst("h3")!!.text())
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        return anime
    }

    private fun newDomainNextPageSelector(): String = "a.am-pgbtn[rel=next]"

    override fun popularAnimeSelector(): String = if (isNewDomain()) newDomainCardSelector() else "div.sebox"

    override fun popularAnimeRequest(page: Int): Request = if (isNewDomain()) {
        GET(baseUrl + "/ongoing" + if (page > 1) "/$page" else "")
    } else {
        GET("$baseUrl/animeincorso?page=$page")
    }

    private fun formatTitle(titlestring: String): String = titlestring.replace("(ITA) ITA", "Dub ITA").replace("(ITA)", "Dub ITA").replace("Sub ITA", "")

    override fun popularAnimeFromElement(element: Element): SAnime {
        if (isNewDomain()) return newDomainCardFromElement(element)
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("div.msebox div.headsebox div.tisebox h2 a")!!.attr("href"))
        anime.title = formatTitle(element.selectFirst("div.msebox div.headsebox div.tisebox h2 a")!!.text())
        anime.thumbnail_url =
            element.selectFirst("div.msebox div.bigsebox div.l img.attachment-post-thumbnail.size-post-thumbnail.wp-post-image")!!
                .attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = if (isNewDomain()) newDomainNextPageSelector() else "li.page-item.active:not(li:last-child)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = if (isNewDomain()) "a.ep-tile" else "div.btn-group.episodes-button.episodi-link-button"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        if (isNewDomain()) {
            // Episode tiles link to /watch/<slug>/ep-N, a landing page with no player;
            // the actual player iframe lives on /stream/<slug>/ep-N.
            episode.setUrlWithoutDomain(element.attr("href").replace("/watch/", "/stream/"))
            val epTitle = element.attr("title") // e.g. "Episodio 1"
            episode.name = epTitle
            val epNumber = epTitle.substringAfter("Episodio ").trim()
            episode.episode_number = if (epNumber.contains("-", true)) {
                epNumber.substringBefore("-").trim().toFloatOrNull() ?: 0F
            } else {
                epNumber.toFloatOrNull() ?: 0F
            }
            return episode
        }
        episode.setUrlWithoutDomain(element.selectFirst("a.btn.btn-dark.mb-1.bottone-ep")!!.attr("href"))
        val epText = element.selectFirst("a.btn.btn-dark.mb-1.bottone-ep")!!.text()
        val epNumber = epText.substringAfter("Episodio ")
        if (epNumber.contains("-", true)) {
            episode.episode_number = epNumber.substringBefore("-").toFloat()
        } else {
            episode.episode_number = epNumber.toFloat()
        }
        episode.name = epText
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        if (isNewDomain()) return videosFromNewDomain(document)
        val standardVideos = videosFromElement(document)
        val videoList = mutableListOf<Video>()
        videoList.addAll(standardVideos)
        return videoList
    }

    override fun videoListRequest(episode: SEpisode): Request {
        if (isNewDomain()) return GET(baseUrl + episode.url)
        val episodePage = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val watchUrl = episodePage.select("a[href*=/watch]").attr("href")
        return GET("$watchUrl&s=alt")
    }

    @Serializable
    private data class PlaylistResponseDto(
        val d: String = "",
        val p: String = "",
        val t: String = "",
    )

    // The embed player (play.marscdn.org) fetches /embed/<id>/playlist?token=...&expires=...
    // and XORs the base64-decoded "d" field with the token to recover the real source URL.
    // Confirmed against the live embed.js: atob(e) then charCodeAt XOR key.charCodeAt(i % key.length).
    private fun xorDecode(encoded: String, key: String): String {
        if (encoded.isEmpty() || key.isEmpty()) return ""
        val decodedBytes = Base64.decode(encoded, Base64.DEFAULT)
        val sb = StringBuilder(decodedBytes.size)
        for (i in decodedBytes.indices) {
            sb.append(((decodedBytes[i].toInt() and 0xFF) xor key[i % key.length].code).toChar())
        }
        return sb.toString()
    }

    private fun videosFromNewDomain(document: Document): List<Video> {
        val iframeUrl = document.selectFirst("iframe#watch-iframe")?.attr("src") ?: return emptyList()
        val match = Regex("""(https?://[^/]+)/embed/(\d+)\?token=([^&]+)&expires=(\d+)""").find(iframeUrl)
            ?: return emptyList()
        val (embedOrigin, embedId, token, expires) = match.destructured
        val playlistUrl = "$embedOrigin/embed/$embedId/playlist?token=$token&expires=$expires"
        val playlistBody = client.newCall(
            GET(playlistUrl, headers = Headers.headersOf("Referer", "$embedOrigin/")),
        ).execute().body.string()
        val playlist = json.decodeFromString<PlaylistResponseDto>(playlistBody)
        val videoUrl = xorDecode(playlist.d, token)
        if (videoUrl.isEmpty()) return emptyList()
        return listOf(Video(videoUrl, "Qualità predefinita", videoUrl))
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    private fun videosFromElement(document: Document): List<Video> {
        val url = if (document.html().contains("jwplayer(")) {
            document.html().substringAfter("file: \"").substringBefore("\"")
        } else {
            document.select("source").attr("src")
        }
        val referer = document.location()
        return if (url.endsWith("playlist.m3u8")) {
            val playlist = client.newCall(GET(url)).execute().body.string()
            val linkRegex = """(?<=\n)./.+""".toRegex()
            val qualityRegex = """(?<=RESOLUTION=)\d+x\d+""".toRegex()
            val qualities = qualityRegex.findAll(playlist).map {
                it.value.substringAfter('x') + "p"
            }.toList()
            val videoLinks = linkRegex.findAll(playlist).map {
                url.substringBefore("playlist.m3u8") + it.value.substringAfter("./")
            }.toList()
            videoLinks.mapIndexed { i, link ->
                Video(
                    link,
                    qualities[i],
                    link,
                )
            }
        } else {
            listOf(
                Video(
                    url,
                    "Qualità predefinita",
                    url,
                    headers = Headers.headersOf("Referer", referer),
                ),
            )
        }
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

    override fun searchAnimeFromElement(element: Element): SAnime {
        if (isNewDomain()) return newDomainCardFromElement(element)
        val anime = SAnime.create()
        if (filterSearch) {
            // filter search
            anime.setUrlWithoutDomain(element.selectFirst("div.card.mb-4.shadow-sm a")!!.attr("href"))
            anime.title = formatTitle(element.selectFirst("div.card.mb-4.shadow-sm a")!!.attr("title"))
            anime.thumbnail_url = element.selectFirst("div.card.mb-4.shadow-sm a img.new-anime")!!.attr("src")
        } else {
            // word search
            anime.setUrlWithoutDomain(
                element.selectFirst("li.list-group-item.bg-dark-as-box-shadow div.item-archivio div.info-archivio h3 a.badge.badge-archivio.badge-light")!!
                    .attr("href"),
            )
            anime.title = formatTitle(
                element.selectFirst("li.list-group-item.bg-dark-as-box-shadow div.item-archivio div.info-archivio h3 a.badge.badge-archivio.badge-light")!!
                    .text(),
            )
            anime.thumbnail_url = element.select("li.list-group-item.bg-dark-as-box-shadow div.item-archivio a.thumb.image-wrapper img.rounded.locandina-archivio").attr("src")
        }
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = if (isNewDomain()) newDomainNextPageSelector() else "li.page-item.active:not(li:last-child)"

    private var filterSearch = false

    override fun searchAnimeSelector(): String = if (isNewDomain()) {
        newDomainCardSelector() // both word and filter search share the same card grid
    } else if (filterSearch) {
        "div.anime-card-newanime.main-anime-card" // filter search
    } else {
        "ul.list-group" // regular search
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        if (isNewDomain()) {
            val path = if (page > 1) "/filter/$page" else "/filter"
            return if (parameters.isEmpty()) {
                filterSearch = false
                GET("$baseUrl$path".toHttpUrl().newBuilder().addQueryParameter("key", query).build())
            } else {
                filterSearch = true
                GET("$baseUrl$path?${parameters.removePrefix("&")}")
            }
        }
        return if (parameters.isEmpty()) {
            filterSearch = false
            GET("$baseUrl/animelist?search=$query") // regular search
        } else {
            filterSearch = true
            GET("$baseUrl/filter?$parameters&page=$page") // with filters
        }
    }

    override fun animeDetailsParse(document: Document): SAnime = if (isNewDomain()) newDomainAnimeDetailsParse(document) else oldDomainAnimeDetailsParse(document)

    private fun metaRowValue(document: Document, label: String): String? = document.select("div.am-meta-row > a, div.am-meta-row > div").firstOrNull { el ->
        el.selectFirst("span")?.text()?.trim() == label
    }?.select("span")?.getOrNull(1)?.text()?.trim()

    private fun newDomainAnimeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val titleEl = document.selectFirst("h1")!!
        anime.title = formatTitle(titleEl.text())
        anime.thumbnail_url = document.selectFirst("img.w-full.object-cover")?.attr("src")
        anime.genre = document.select("a.am-chip").joinToString { it.text() }
        anime.status = parseStatus(metaRowValue(document, "Stato") ?: "")
        anime.author = metaRowValue(document, "Studio")
        anime.description = document.selectFirst("div.story-clip")?.text()
        val altTitleEl = titleEl.nextElementSibling()
        val altTitle = if (altTitleEl?.tagName() == "p") formatTitle(altTitleEl.text()) else ""
        if (altTitle.isNotBlank() && !anime.title.contains(altTitle, true)) {
            anime.description = (anime.description ?: "") + "\n\nTitolo Alternativo: " + altTitle
        }
        return anime
    }

    private fun oldDomainAnimeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title =
            formatTitle(document.select("div.container.anime-title-as.mb-3.w-100 b").text())
        val tempDetails =
            document.select("div.container.shadow.rounded.bg-dark-as-box.mb-3.p-3.w-100.text-white")
                .text()
        val indexA = tempDetails.indexOf("Stato:")
        anime.author = tempDetails.substring(7, indexA).trim()
        val indexS1 = tempDetails.indexOf("Stato:") + 6
        val indexS2 = tempDetails.indexOf("Data di uscita:")
        anime.status = parseStatus(tempDetails.substring(indexS1, indexS2).trim())
        anime.genre =
            document.select("div.container.shadow.rounded.bg-dark-as-box.mb-3.p-3.w-100 a.badge.badge-dark.generi-as.mb-1")
                .joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img.img-fluid.cover-anime.rounded")!!.attr("src")
        val alterTitle = formatTitle(
            document.selectFirst("div.box-trasparente-alternativo.rounded")!!.text(),
        ).replace("Dub ITA", "").trim()
        val description1 = document.selectFirst("div#trama div#shown-trama")?.ownText()
        val description2 = document.selectFirst("div#full-trama.d-none")?.ownText()
        when {
            description1 == null -> {
                anime.description = description2
            }

            description2 == null -> {
                anime.description = description1
            }

            description1.length > description2.length -> {
                anime.description = description1
            }

            else -> {
                anime.description = description2
            }
        }
        if (!anime.title.contains(alterTitle, true)) anime.description = anime.description + "\n\nTitolo Alternativo: " + alterTitle
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

    override fun latestUpdatesSelector(): String = if (isNewDomain()) newDomainCardSelector() else "div.card.mb-4.shadow-sm"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        if (isNewDomain()) return newDomainCardFromElement(element)
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        anime.title = formatTitle(element.selectFirst("a")!!.attr("title"))
        anime.thumbnail_url = element.selectFirst("a img.new-anime")!!.attr("src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = if (isNewDomain()) {
        GET(baseUrl + "/newest" + if (page > 1) "/$page" else "")
    } else {
        GET("$baseUrl/newest?page=$page")
    }

    override fun latestUpdatesNextPageSelector(): String = if (isNewDomain()) newDomainNextPageSelector() else "li.page-item.active:not(li:last-child)"

    // Filters
    // Genre ids below are the numeric category ids used by the current (2026) AnimeMars
    // /filter?categories[]=<id> search; taken from the live checkboxes on /filter.
    internal class Genre(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("3", "Arti Marziali"),
        Genre("5", "Avanguardia"),
        Genre("2", "Avventura"),
        Genre("1", "Azione"),
        Genre("47", "Bambini"),
        Genre("4", "Commedia"),
        Genre("6", "Demoni"),
        Genre("7", "Drammatico"),
        Genre("8", "Ecchi"),
        Genre("9", "Fantasy"),
        Genre("10", "Gioco"),
        Genre("11", "Harem"),
        Genre("43", "Hentai"),
        Genre("13", "Horror"),
        Genre("49", "Isekai"),
        Genre("14", "Josei"),
        Genre("16", "Magia"),
        Genre("18", "Mecha"),
        Genre("19", "Militari"),
        Genre("21", "Mistero"),
        Genre("20", "Musicale"),
        Genre("22", "Parodia"),
        Genre("23", "Polizia"),
        Genre("24", "Psicologico"),
        Genre("46", "Romantico"),
        Genre("26", "Samurai"),
        Genre("28", "Sci-Fi"),
        Genre("27", "Scolastico"),
        Genre("29", "Seinen"),
        Genre("25", "Sentimentale"),
        Genre("30", "Shoujo"),
        Genre("31", "Shoujo Ai"),
        Genre("32", "Shounen"),
        Genre("33", "Shounen Ai"),
        Genre("34", "Slice of Life"),
        Genre("37", "Soprannaturale"),
        Genre("35", "Spazio"),
        Genre("36", "Sport"),
        Genre("12", "Storico"),
        Genre("38", "Superpoteri"),
        Genre("39", "Thriller"),
        Genre("40", "Vampiri"),
        Genre("48", "Veicoli"),
        Genre("41", "Yaoi"),
        Genre("42", "Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)
    private fun getYears() = listOf(
        Year("1969"),
        Year("1970"),
        Year("1975"),
        Year("1978"),
        Year("1979"),
        Year("1981"),
        Year("1983"),
        Year("1984"),
        Year("1986"),
        Year("1987"),
        Year("1988"),
        Year("1989"),
        Year("1990"),
        Year("1991"),
        Year("1992"),
        Year("1993"),
        Year("1994"),
        Year("1995"),
        Year("1996"),
        Year("1997"),
        Year("1998"),
        Year("1999"),
        Year("2000"),
        Year("2001"),
        Year("2002"),
        Year("2003"),
        Year("2004"),
        Year("2005"),
        Year("2006"),
        Year("2007"),
        Year("2008"),
        Year("2009"),
        Year("2010"),
        Year("2011"),
        Year("2012"),
        Year("2013"),
        Year("2014"),
        Year("2015"),
        Year("2016"),
        Year("2017"),
        Year("2018"),
        Year("2019"),
        Year("2020"),
        Year("2021"),
        Year("2022"),
        Year("2023"),
        Year("2024"),
        Year("2025"),
        Year("2026"),
    )

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

    // The old domain used boolean 0/1 language ids; the new domain (default) uses
    // language codes as seen on /filter: languages[]=jp|it|en|kr|ch.
    private fun getLangs() = if (isNewDomain()) {
        listOf(
            Lang("jp", "Subbato"),
            Lang("it", "Doppiato"),
        )
    } else {
        listOf(
            Lang("0", "Subbato"),
            Lang("1", "Doppiato"),
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ricerca per titolo ignora i filtri e viceversa"),
        GenreList(getGenres()),
        YearList(getYears()),
        StateList(getStates()),
        LangList(getLangs()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String = if (isNewDomain()) {
        getNewDomainSearchParameters(filters)
    } else {
        getOldDomainSearchParameters(filters)
    }

    // New domain (default) uses repeated empty-bracket array params, e.g. categories[]=1&categories[]=2,
    // confirmed against the live /filter endpoint.
    private fun getNewDomainSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        filters.forEach { filter ->
            when (filter) {
                is GenreList -> {
                    filter.state.forEach { genre -> if (genre.state) totalstring += "&categories%5B%5D=${genre.id}" }
                }

                is YearList -> {
                    filter.state.forEach { year -> if (year.state) totalstring += "&years%5B%5D=${year.id}" }
                }

                is StateList -> {
                    filter.state.forEach { state -> if (state.state) totalstring += "&states%5B%5D=${state.id}" }
                }

                is LangList -> {
                    filter.state.forEach { lang -> if (lang.state) totalstring += "&languages%5B%5D=${lang.id}" }
                }

                else -> {}
            }
        }
        return totalstring
    }

    private fun getOldDomainSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var variantgenre = 0
        var variantstate = 0
        var variantyear = 0
        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            totalstring = totalstring + "&categories%5B" + variantgenre.toString() + "%5D=" + genre.id
                            variantgenre++
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { year ->
                        if (year.state) {
                            totalstring = totalstring + "&years%5B" + variantyear.toString() + "%5D=" + year.id
                            variantyear++
                        }
                    }
                }

                is StateList -> { // ---State
                    filter.state.forEach { state ->
                        if (state.state) {
                            totalstring = totalstring + "&states%5B" + variantstate.toString() + "%5D=" + state.id
                            variantstate++
                        }
                    }
                }

                is LangList -> { // ---Lang
                    filter.state.forEach { lang ->
                        if (lang.state) {
                            totalstring = totalstring + "&language%5B0%5D=" + lang.id
                        }
                    }
                }

                else -> {}
            }
        }
        return totalstring
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY,
            title = "Qualità preferita",
            entries = QUALITY_ENTRIES,
            entryValues = QUALITY_VALUES,
            default = QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_DOMAIN,
            title = "Domain in uso (riavvio dell'app richiesto)",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = DOMAIN_DEFAULT,
            summary = "%s",
        )
    }

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private val QUALITY_VALUES = listOf("1080", "720", "480", "360", "240", "144")
        private val QUALITY_ENTRIES = QUALITY_VALUES.map { "${it}p" }
        private val QUALITY_DEFAULT = QUALITY_VALUES.first()

        private const val PREF_DOMAIN = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf("www.animemars.org", "animesaturn.cx")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val DOMAIN_DEFAULT = DOMAIN_VALUES.first()
    }
}
