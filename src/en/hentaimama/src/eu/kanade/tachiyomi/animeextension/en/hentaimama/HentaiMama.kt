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
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
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

    override fun popularAnimeSelector(): String = "article.series-card"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/advance-search/page/$page/?submit=Submit&filter=weekly")

    override fun popularAnimeFromElement(element: Element): SAnime = seriesCardToAnime(element)

    override fun popularAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    private fun seriesCardToAnime(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.sc-poster") ?: element.selectFirst("h3.sc-title a")!!
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst("h3.sc-title a")?.text().orEmpty()
        thumbnail_url = element.selectFirst("a.sc-poster img")?.imgUrl()
    }

    private fun Element.imgUrl(): String = attr("abs:data-src").ifEmpty { attr("abs:src") }

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> = super.episodeListParse(response).sortedByDescending { it.episode_number }

    override fun episodeListSelector() = "div#episodes a.dt-se-item"

    private val dateFormat by lazy { SimpleDateFormat("MMM dd, yyyy", Locale.US) }

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("b.dt-se-title")?.text().orEmpty().ifEmpty { "Episode" }
        episode_number = element.selectFirst("b.dt-se-num")?.text()
            ?.let { EPISODE_NUMBER_REGEX.find(it)?.value?.toFloatOrNull() }
            ?: 1F
        date_upload = element.selectFirst("span.dt-se-date")?.text()
            ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() }
            ?: 0L
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val postId = document.selectFirst("link[rel=shortlink]")?.attr("href")
            ?.substringAfter("?p=", "")?.takeIf { it.isNotEmpty() }
            ?: POST_ID_REGEX.find(document.body().className())?.groupValues?.get(1)
            ?: return emptyList()

        // One HTML <iframe> snippet per mirror, returned as a JSON array
        val body = FormBody.Builder()
            .add("action", "get_player_contents")
            .add("a", postId)
            .build()
        val playerHeaders = headersBuilder().set("Referer", "$baseUrl/").build()

        val embedUrls = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", playerHeaders, body))
            .execute().use { it.body.string() }
            .parseAs<List<String>>()
            .flatMap { snippet -> Jsoup.parseBodyFragment(snippet, baseUrl).select("iframe[src]").map { it.absUrl("src") } }

        return embedUrls.parallelCatchingFlatMapBlocking { embedUrl -> videosFromEmbed(embedUrl) }
    }

    private fun videosFromEmbed(embedUrl: String): List<Video> {
        val videoHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
        val html = client.newCall(GET(embedUrl, videoHeaders)).execute().use { it.body.string() }
        val mirror = when {
            "dt_embed=rtmp" in embedUrl -> "Mirror 1"
            "newjav" in embedUrl -> "Mirror 2"
            "dt_embed=hls-mp4" in embedUrl -> "MP4"
            "dt_embed=hls" in embedUrl -> "HLS"
            else -> "Mirror"
        }

        // JW Player setup: sources: [{"file":"https:\/\/...","label":"1080p","type":"mp4"}, ...]
        val sources = SOURCES_BLOCK_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        return sources.split('}').mapNotNull { item ->
            val file = FILE_REGEX.find(item)?.groupValues?.get(1)?.replace("\\/", "/")
                ?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            val label = LABEL_REGEX.find(item)?.groupValues?.get(1)
            val quality = if (label.isNullOrEmpty()) mirror else "$mirror $label"
            Video(file, quality, file, videoHeaders)
        }.distinctBy { it.videoUrl }
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

    override fun searchAnimeFromElement(element: Element): SAnime = seriesCardToAnime(element)

    override fun searchAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun searchAnimeSelector(): String = "article.series-card"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotEmpty()) {
        GET("$baseUrl/page/$page/?s=${query.replace(Regex("[\\W]"), " ")}") // regular search
    } else {
        val urlBuilder = "$baseUrl/advance-search/page/$page/".toHttpUrl().newBuilder()
        addSearchParameters(urlBuilder, filters)
        GET(urlBuilder.build().toString(), headers) // filter search
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.dsc-poster img")?.imgUrl()
        title = document.selectFirst("h1.dsc-title")?.text().orEmpty()
        genre = document.select("div.dsc-genres a").joinToString(", ") { it.text() }
        description = document.select("div.dsc-desc p").joinToString("\n\n") { it.text() }
            .ifEmpty { document.selectFirst("div.dsc-desc")?.text() }
        author = document.select("div.dsc-stat")
            .firstOrNull { it.selectFirst("span")?.text() == "Studio" }
            ?.selectFirst("b")?.text()
            ?.takeIf { it.any(Char::isLetterOrDigit) }
        status = when {
            document.selectFirst("span.dsc-chip.is-completed") != null -> SAnime.COMPLETED
            document.select("span.dsc-chip").any { it.text().contains("Ongoing", ignoreCase = true) } -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // Latest

    override fun latestUpdatesSelector(): String = "article.tvshows"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/tvshows/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.data h3 a") ?: element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.text().ifEmpty { element.selectFirst("div.poster img")?.attr("alt").orEmpty() }
        thumbnail_url = element.selectFirst("div.poster img")?.imgUrl()
    }

    override fun latestUpdatesNextPageSelector(): String = NEXT_PAGE_SELECTOR

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
        private const val NEXT_PAGE_SELECTOR = "a.dt-pg-next"
        private val EPISODE_NUMBER_REGEX = Regex("""\d+(\.\d+)?""")
        private val POST_ID_REGEX = Regex("""postid-(\d+)""")
        private val SOURCES_BLOCK_REGEX = Regex("""sources:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        private val FILE_REGEX = Regex(""""?file"?\s*:\s*"([^"]+)"""")
        private val LABEL_REGEX = Regex(""""?label"?\s*:\s*"([^"]*)"""")
        private val PREF_QUALITY_ENTRIES = arrayOf("Mirror 1", "Mirror 2", "MP4", "HLS")
    }
}
