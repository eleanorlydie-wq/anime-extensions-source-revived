package eu.kanade.tachiyomi.animeextension.de.animebase

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.de.animebase.extractors.UnpackerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class AnimeBase :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime-Base"

    override val baseUrl = "https://anime-base.net"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json: Json by injectLazy()

    // Site redesign (2026): anime-base.net is now a Laravel + Inertia.js/Svelte
    // SPA. Every document response still contains the full page state as a
    // JSON blob in `<div id="app" data-page="{...}">`, e.g. on `/anime-liste`:
    // data-page="{&quot;component&quot;:&quot;AB/ABPages/Serie/List&quot;,...,
    // &quot;props&quot;:{...,&quot;series&quot;:{&quot;meta&quot;:{&quot;total&quot;:2519,
    // &quot;perPage&quot;:24,&quot;currentPage&quot;:1,&quot;lastPage&quot;:105,...},
    // &quot;data&quot;:[{&quot;id&quot;:1374,&quot;name&quot;:&quot;.hack//Legend of the
    // Twilight&quot;,&quot;category&quot;:&quot;anime&quot;,&quot;image&quot;:
    // &quot;/uploads/series-covers/hack-legend-of-the-twilight/cover-large.webp&quot;,
    // &quot;nameSlug&quot;:&quot;hack-legend-of-the-twilight&quot;,...}]}}}"
    // Jsoup decodes the HTML entities for us via Element.attr().
    private fun Document.inertiaProps(): JsonObject = json.parseToJsonElement(selectFirst("[data-page]")!!.attr("data-page"))
        .jsonObject.getValue("props").jsonObject

    private fun JsonObject.toSAnime() = SAnime.create().apply {
        val category = this@toSAnime["category"]?.jsonPrimitive?.contentOrNull ?: "anime"
        val slug = getValue("nameSlug").jsonPrimitive.content
        setUrlWithoutDomain("/$category/$slug")
        title = getValue("name").jsonPrimitive.content
        thumbnail_url = this@toSAnime["image"]?.jsonPrimitive?.contentOrNull?.let { baseUrl + it }
    }

    // ============================== Popular ===============================
    // `/favorites` (login-only "favorites" list) 404s and no longer exists;
    // `/anime-liste` is the public, paginated all-anime browse page and is
    // the closest available equivalent.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime-liste?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val props = response.useAsJsoup().inertiaProps()
        val series = props.getValue("series").jsonObject
        val meta = series.getValue("meta").jsonObject
        val animes = series.getValue("data").jsonArray.map { it.jsonObject.toSAnime() }
        val hasNextPage = meta.getValue("currentPage").jsonPrimitive.int < meta.getValue("lastPage").jsonPrimitive.int
        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()

    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    // `/updates` still exists (still Bootstrap-era `div.box-header`/`div.box-body`
    // markup is gone though); it now renders via the same Inertia data-page blob,
    // props.updates: [{...,"seriesSlug":"skeleton-knight-in-another-world",
    // "serie":{"id":1626,"name":"Skeleton Knight in Another World","nameSlug":
    // "skeleton-knight-in-another-world","category":"anime","image":"/uploads/..."}}]
    // It is not paginated (same 50 entries regardless of ?page=).
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/updates", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val props = response.useAsJsoup().inertiaProps()
        val animes = props.getValue("updates").jsonArray
            .map { it.jsonObject.getValue("serie").jsonObject.toSAnime() }
            .distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun getFilterList() = AnimeBaseFilters.FILTER_LIST

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/searching", headers)).execute()
            .useAsJsoup()
            .selectFirst("form > input[name=_token]")!!
            .attr("value")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeBaseFilters.getSearchParameters(filters)

        return when {
            params.list.isEmpty() -> {
                val body = FormBody.Builder()
                    .add("_token", searchToken)
                    .add("_token", searchToken)
                    .add("name_serie", query)
                    .add("jahr", params.year.toIntOrNull()?.toString() ?: "")
                    .apply {
                        params.languages.forEach { add("dubsub[]", it) }
                        params.genres.forEach { add("genre[]", it) }
                    }.build()
                POST("$baseUrl/searching", headers, body)
            }

            else -> {
                GET("$baseUrl/${params.list}${params.letter}?page=$page", headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()

        return when {
            doc.location().contains("/searching") -> {
                val animes = doc.select(searchAnimeSelector()).map(::searchAnimeFromElement)
                AnimesPage(animes, false)
            }

            else -> { // pages like filmlist or animelist
                val animes = doc.select(popularAnimeSelector()).map(::popularAnimeFromElement)
                val hasNext = doc.selectFirst(searchAnimeNextPageSelector()) != null
                AnimesPage(animes, hasNext)
            }
        }
    }

    override fun searchAnimeSelector() = "div.col-lg-9.col-md-8 div.box-body > a"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = "ul.pagination li > a[rel=next]"

    // =========================== Anime Details ============================
    // props.serie, e.g. {"id":1626,"name":"Skeleton Knight in Another World",
    // "nameSlug":"skeleton-knight-in-another-world","category":"anime",
    // "originalName":"Gaikotsu Kishi-sama, Tadaima Isekai e Odekakechuu",
    // "image":"/uploads/series-covers/.../cover-large.webp","year":"2022",
    // "status":0,"description":"...","genres":[{"id":261,"name":"Abenteuerkomödie",...}]}
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())

        val serie = document.inertiaProps().getValue("serie").jsonObject
        title = serie.getValue("name").jsonPrimitive.content
        thumbnail_url = serie["image"]?.jsonPrimitive?.contentOrNull?.let { baseUrl + it }
        status = parseStatus(serie["status"]?.jsonPrimitive?.intOrNull)
        genre = serie["genres"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString()
            ?.takeIf(String::isNotBlank)

        description = buildString {
            serie["description"]?.jsonPrimitive?.contentOrNull?.also(::append)

            serie["originalName"]?.jsonPrimitive?.contentOrNull?.also { append("\nOriginal name: $it") }
            serie["year"]?.jsonPrimitive?.contentOrNull?.also { append("\nErscheinungsjahr: $it") }
        }
    }

    // status: 0 on the currently-airing "Skeleton Knight in Another World"
    // (weekday 6), 1 on the finished 2003 show ".hack//Legend of the Twilight"
    // (weekday 0) -> 0 = ongoing, 1 = completed.
    private fun parseStatus(status: Int?) = when (status) {
        0 -> SAnime.ONGOING
        1 -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    // serie.episodes, e.g. {"id":1829189,"serieId":1626,"season":"1",
    // "name":"Der wandernde Ritter begibt sich auf die Reise...","type":0,
    // "filler":0,"dubsub":0,"link1":null,...,"link5":"https://filemoon.to/d/...",
    // "link7":"https://strmup.to/v/...","episode":1}
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val animeUrl = document.location()
        val serie = document.inertiaProps().getValue("serie").jsonObject
        return serie.getValue("episodes").jsonArray
            .map { it.jsonObject.toSEpisode(animeUrl) }
            .sortedBy { it.episode_number }
            .reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun JsonObject.toSEpisode(animeUrl: String) = SEpisode.create().apply {
        val epNum = getValue("episode").jsonPrimitive.int
        val season = this@toSEpisode["season"]?.jsonPrimitive?.contentOrNull ?: "1"
        val rawName = this@toSEpisode["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val dubsub = this@toSEpisode["dubsub"]?.jsonPrimitive?.intOrNull ?: 0
        val id = getValue("id").jsonPrimitive.content

        name = "Staffel $season Folge $epNum" + if (rawName.isNotBlank()) ": $rawName" else ""
        episode_number = epNum.toFloat()
        scanlator = if (dubsub == 0) "Subbed" else "Dubbed"
        setUrlWithoutDomain("$animeUrl?epid=$id")
    }

    // ============================ Video Links =============================
    private val hosterSettings by lazy {
        mapOf(
            "Streamwish" to "https://streamwish.to/e/",
            "Voe.SX" to "https://voe.sx/e/",
            "Lulustream" to "https://lulustream.com/e/",
            "VTube" to "https://vtbe.to/embed-",
            "VidGuard" to "https://vembed.net/e/",
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val selector = response.request.url.queryParameter("selector")
            ?: return emptyList()

        return doc.select("$selector div.panel-body > button").toList()
            .filter { it.text() in hosterSettings.keys }
            .parallelCatchingFlatMapBlocking {
                val language = when (it.attr("data-dubbed")) {
                    "0" -> "SUB"
                    else -> "DUB"
                }

                getVideosFromHoster(it.text(), it.attr("data-streamlink"))
                    .map { video ->
                        Video(
                            video.url,
                            "$language ${video.quality}",
                            video.videoUrl,
                            video.headers,
                            video.subtitleTracks,
                            video.audioTracks,
                        )
                    }
            }
    }

    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val unpackerExtractor by lazy { UnpackerExtractor(client, headers) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }

    private suspend fun getVideosFromHoster(hoster: String, urlpart: String): List<Video> {
        val url = hosterSettings[hoster]!! + urlpart
        return when (hoster) {
            "Streamwish" -> streamWishExtractor.videosFromUrl(url)
            "Voe.SX" -> voeExtractor.videosFromUrl(url)
            "VTube", "Lulustream" -> unpackerExtractor.videosFromUrl(url, hoster)
            "VidGuard" -> vidguardExtractor.videosFromUrl(url)
            else -> null
        } ?: emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    companion object {
        private const val PREF_LANG_KEY = "preferred_sub"
        private const val PREF_LANG_TITLE = "Standardmäßig Sub oder Dub?"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("Sub", "Dub")
        private val PREF_LANG_VALUES = arrayOf("SUB", "DUB")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
