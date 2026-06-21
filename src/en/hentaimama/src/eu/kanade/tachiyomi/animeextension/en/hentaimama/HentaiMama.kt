package eu.kanade.tachiyomi.animeextension.en.hentaimama

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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiMama :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.tvshows"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/advance-search/page/$page/?submit=Submit&filter=weekly")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination-wraper div.resppages a"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> = super.episodeListParse(response)

    override fun episodeListSelector() = "div.series div.items article"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val date = SimpleDateFormat("MMM. dd, yyyy", Locale.US).parse(element.select("div.data > span").text())
        val epNumPattern = Regex("Episode (\\d+\\.?\\d*)")
        val epNumMatch = epNumPattern.find(element.select("div.season_m a span.c").text())

        episode.setUrlWithoutDomain(element.select("div.season_m a").attr("href"))
        episode.name = element.select("div.data h3").text()
        episode.date_upload = runCatching { date?.time }.getOrNull() ?: 0L
        episode.episode_number = runCatching { epNumMatch?.groups?.get(1)!!.value.toFloat() }.getOrNull() ?: 1F

        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        // POST body data
        val body = FormBody.Builder()
            .add("action", "get_player_contents")
            .add(
                "a",
                document.selectFirst("#post_report input:nth-child(5)")?.attr("value").toString(),
            )
            .build()

        // Call POST
        val newHeaders = Headers.headersOf("referer", "$baseUrl/")

        val listOfiFrame = client.newCall(
            POST("$baseUrl/wp-admin/admin-ajax.php", newHeaders, body),
        )
            .execute().asJsoup()
            .body().select("iframe").toString()

        val regex = Regex("https?[\\S][^\"]+")
        val allLinks = regex.findAll(listOfiFrame)
        val urls = allLinks.map { it.value }.toList()

        val videoRegex = Regex("(https:[^\"]+\\.mp4*)")

        val videoList = mutableListOf<Video>()

        for (url in urls) {
            val req = client.newCall(GET(url)).execute().asJsoup()
                .body().toString()

            val videoLink = videoRegex.find(req)
            val videoRes = when {
                url.contains("newr2") -> "Beta"
                url.contains("new1") -> "Mirror 1"
                url.contains("new2") -> "Mirror 2"
                url.contains("new3") -> "Mirror 3"
                else -> ""
            }

            if (videoLink != null) {
                videoList.add(Video(videoLink.value, videoRes, videoLink.value))
            }
        }

        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
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

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search
    private var filterSearch = false

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        if (filterSearch) {
            // filter search
            anime.setUrlWithoutDomain(element.select("a").attr("href"))
            anime.title = element.select("div.data h3 a").text()
            anime.thumbnail_url = element.select("div.poster img").attr("data-src")
            return anime
        } else {
            // normal search
            anime.setUrlWithoutDomain(element.select("div.details > div.title a").attr("href"))
            anime.thumbnail_url = element.select("div.image div a img").attr("src")
            anime.title = element.select("div.details > div.title a").text()
            return anime
        }
    }

    override fun searchAnimeNextPageSelector(): String = if (filterSearch) {
        "div.pagination-wraper div.resppages a" // filter search
    } else {
        "link[rel=next]" // normal search
    }

    override fun searchAnimeSelector(): String = "article"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotEmpty()) {
        filterSearch = false
        GET("$baseUrl/page/$page/?s=${query.replace(Regex("[\\W]"), " ")}") // regular search
    } else {
        filterSearch = true
        val urlBuilder = "$baseUrl/advance-search/page/$page/".toHttpUrl().newBuilder()
        addSearchParameters(urlBuilder, filters)
        GET(urlBuilder.build().toString(), headers) // filter search
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.sheader div.poster img")!!.attr("data-src")
        anime.title = document.select("#info1 div:nth-child(2) span").text()
        anime.genre = document.select("div.sheader  div.data  div.sgeneros a")
            .joinToString(", ") { it.text() }
        anime.description = document.select("#info1 div.wp-content p").text()
        anime.author = document.select("#info1 div:nth-child(3) span div  div a")
            .joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("#info1 div:nth-child(6) span").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int = when (statusString) {
        "Ongoing" -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // Latest

    override fun latestUpdatesSelector(): String = "article.tvshows"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/tvshows/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "link[rel=next]"

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue("Mirror 2")
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

    // Filters

    // Single autocomplete tag-style inputs. The user types names (comma/semicolon
    // separated) and gets live suggestions; each typed token is matched
    // case-insensitively back to the exact value the site expects.
    private class GenreFilter(suggestions: List<String>) : AnimeFilter.AutoComplete("Genres", "e.g. Anal, Big Breasts, Vanilla", suggestions = suggestions)

    private class YearFilter(suggestions: List<String>) : AnimeFilter.AutoComplete("Years", "e.g. 2025, 2024", suggestions = suggestions)

    private class StudioFilter(suggestions: List<String>) : AnimeFilter.AutoComplete("Studios", "e.g. Pink Pineapple, Queen Bee", suggestions = suggestions)

    private data class Order(val name: String, val id: String)
    private class OrderList(orders: Array<String>) : AnimeFilter.Select<String>("Order", orders)
    private val orderName = getOrder().map { it.name }.toTypedArray()
    private fun getOrder() = listOf(
        Order("Weekly Views", "weekly"),
        Order("Monthly Views", "monthly"),
        Order("Alltime Views", "alltime"),
        Order("A-Z", "alphabet"),
        Order("Rating", "rating"),
    )

    private val genreValues = listOf(
        "3D", "Action", "Adventure", "Ahegao",
        "Anal", "Animal Girls", "BDSM", "Blackmail",
        "Blowjob", "Bondage", "Brainwashed", "Bukakke",
        "Cat Girl", "Comedy", "Condom", "Cosplay",
        "Creampie", "Cross-dressing", "Cute & Funny", "Dark Skin",
        "DeepThroat", "Demons", "Doctor", "Domination",
        "Double Penetration", "Drama", "Dubbed", "Ecchi",
        "Elf", "Eroge", "Facesitting", "Facial",
        "Fantasy", "Female Doctor", "Female Teacher", "Femdom",
        "Footjob", "Furry", "Futanari", "Gangbang",
        "Gyaru", "Harem", "Historical", "Horny Slut",
        "Housewife", "Humiliation", "Inflation", "Internal Cumshot",
        "Lactation", "Large Breasts", "Magical Girls", "Maid",
        "Martial Arts", "Megane", "MILF", "Mind Break",
        "Molestation", "Nipple Fuck", "Non-Japanese", "NTR",
        "Nuns", "Nurses", "Office Ladies", "Orc/Goblin",
        "Police", "POV", "Pregnant", "Princess",
        "Public Sex", "Rape", "Rim job", "Romance",
        "Scat", "School Girls", "Sci-Fi", "Shimapan",
        "Short", "Shoutacon", "Sports", "Squirting",
        "Step Daughter", "Step Mother", "Step Sister", "Stocking",
        "Strap-on", "Succubus", "Super Power", "Supernatural",
        "Swimsuit", "Tentacles", "Three some", "Tits Fuck",
        "Toys", "Train Molestation", "Tsundere", "Uncensored",
        "Urination", "Vampire", "Vanilla", "Virgins",
        "Widow", "X-Ray", "Yuri",
    )

    private val yearValues = listOf(
        "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019",
        "2018", "2017", "2016", "2015", "2014", "2013", "2012", "2011",
        "2010", "2009", "2008", "2007", "2006", "2005", "2004", "2003",
        "2002", "2001", "2000", "1999", "1998", "1997", "1996", "1995",
        "1994", "1993", "1992", "1991", "1987",
    )

    private val studioValues = listOf(
        "8bit", "Actas", "Active", "AIC",
        "AIC A.S.T.A.", "Alice Soft", "An DerCen", "Angelfish",
        "Animac", "AniMan", "Animax", "AnimeFesta",
        "Antechinus", "APPP", "Armor", "Arms",
        "Asahi Production", "AT-2", "Blue Eyes", "BOMB! CUTE! BOMB!",
        "BOOTLEG", "Bunnywalker", "Central Park Media", "CherryLips",
        "ChiChinoya", "Chippai", "ChuChu", "Circle Tribute",
        "CLOCKUP", "Collaboration Works", "Comic Media", "Cosmic Ray",
        "Cosmo", "Cotton Doll", "Cranberry", "D3",
        "Daiei", "Digital Works", "Discovery", "Dream Force",
        "Dubbed", "Easy Film", "Echo", "EDGE",
        "Filmlink International", "Five Ways", "Front Line", "Frontier Works",
        "Godoy", "Gold Bear", "Green Bunny", "Himajin Planning",
        "Hokiboshi", "Hoods Entertainment", "Horipro", "Hot Bear",
        "HydraFXX", "Innocent Grey", "Jam", "JapanAnime",
        "Juicymango", "King Bee", "Kitty Films", "Kitty Media",
        "Knack Productions", "KSS", "Lemon Heart", "Lune Pictures",
        "Majin", "Marvelous Entertainment", "Mary Jane", "Media",
        "Media Blasters", "Milkshake", "Mitsu", "Moonstone Cherry",
        "Mousou Senka", "MS Pictures", "Nag", "Nihikime no Dozeu",
        "No Future", "Nur", "NuTech Digital", "Obtain Future",
        "Office Take Off", "OLE-M", "Oriental Light and Magic", "Oz",
        "Pashmina", "Pink Pineapple", "Pixy", "PoRO",
        "Production I.G", "Queen Bee", "Rojiura Jack", "Sakura Purin Animation",
        "Schoolzone", "Selfish", "Seven", "Shelf",
        "Shinkuukan", "Shinyusha", "Shouten", "Silky’s",
        "Sodeno19", "Soft Garage", "SoftCel Pictures", "SPEED",
        "Studio 9 Maiami", "Studio Eromatick", "Studio Fantasia", "Studio Jack",
        "Studio Kyuuma", "Studio Matrix", "Studio Sign", "Studio Tulip",
        "Studio Unicorn", "Suzuki Mirano", "T-Rex", "The Right Stuf International",
        "Toho Company", "Top-Marschal", "Toranoana", "Torudaya",
        "Toshiba Entertainment", "Triangle Bitter", "Triple X", "Umemaro3D",
        "Union Cho", "Valkyria", "White Bear", "Y.O.U.C",
        "ZIZ Entertainment", "Zyc",
    )

    // Splits the autocomplete text into tokens and maps each to its canonical
    // site value (ignoring a leading "-"; this site only supports inclusion).
    private fun resolveTokens(state: String, validValues: List<String>): List<String> = state.split(',', ';')
        .map { it.trim().removePrefix("-").trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { token -> validValues.firstOrNull { value -> value.equals(token, ignoreCase = true) } }
        .distinct()

    // Appends the selected filters as properly URL-encoded query parameters.
    private fun addSearchParameters(urlBuilder: HttpUrl.Builder, filters: AnimeFilterList) {
        var sortBy = "weekly"

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> resolveTokens(filter.state, genreValues)
                    .forEach { urlBuilder.addQueryParameter("genres_filter[]", it) }

                is YearFilter -> resolveTokens(filter.state, yearValues)
                    .forEach { urlBuilder.addQueryParameter("years_filter[]", it) }

                is StudioFilter -> resolveTokens(filter.state, studioValues)
                    .forEach { urlBuilder.addQueryParameter("studios_filter[]", it) }

                is OrderList -> sortBy = getOrder()[filter.state].id

                else -> {}
            }
        }

        urlBuilder.addQueryParameter("submit", "Submit")
        urlBuilder.addQueryParameter("filter", sortBy)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored if using Text Search"),
        AnimeFilter.Header("Type a name and pick from the suggestions; separate with , or ;"),
        OrderList(orderName),
        GenreFilter(genreValues),
        YearFilter(yearValues),
        StudioFilter(studioValues),
    )
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("Mirror 1", "Mirror 2", "Mirror 3", "Beta")
    }
}
