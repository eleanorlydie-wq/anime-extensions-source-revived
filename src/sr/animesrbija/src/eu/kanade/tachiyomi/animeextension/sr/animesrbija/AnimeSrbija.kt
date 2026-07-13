package eu.kanade.tachiyomi.animeextension.sr.animesrbija

import aniyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class AnimeSrbija : AnimeHttpSource() {

    override val name = "Anime Srbija"

    override val baseUrl = "https://www.animesrbija.com"

    override val lang = "sr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = findQueryData(response.body.string()) { key ->
            key.size == 3 && key[0].stringOrNull() == "anime" && key[1].stringOrNull() == "list"
        }?.jsonObject ?: return AnimesPage(emptyList(), false)

        val items = data["items"]?.jsonArray ?: JsonArray(emptyList())
        val animes = items.map { parseAnime(it.jsonObject) }
        val hasNextPage = data["meta"]?.jsonObject?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull ?: false
        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/filter?sort=popular&page=$page")

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = findQueryData(response.body.string()) { key ->
            key.size == 2 && key[0].stringOrNull() == "episodes"
        }?.jsonArray ?: return emptyList()

        return episodes.map {
            val obj = it.jsonObject
            val animeId = obj["animeId"]!!.jsonPrimitive.content
            val number = obj["number"]!!.jsonPrimitive.int
            SEpisode.create().apply {
                setUrlWithoutDomain("/epizoda?animeId=$animeId&ep=$number")
                name = "Epizoda $number"
                episode_number = number.toFloat()
                if (obj["filler"]?.jsonPrimitive?.booleanOrNull == true) scanlator = "filler"
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val data = findQueryData(response.body.string()) { key ->
            key.size == 3 && key[0].stringOrNull() == "episodes" && key[1].stringOrNull() != "latest"
        }?.jsonObject ?: return emptyList()

        val players = data["players"]?.jsonArray ?: JsonArray(emptyList())
        val links = players.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
        return links.flatMap(::getVideosFromURL)
    }

    private fun getVideosFromURL(url: String): List<Video> {
        val trimmedUrl = url.trim('!')
        return runCatching {
            when {
                "filemoon" in trimmedUrl ->
                    FilemoonExtractor(client).videosFromUrl(trimmedUrl)

                ".m3u8" in trimmedUrl ->
                    listOf(Video(trimmedUrl, "Internal Player", trimmedUrl))

                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val anime = findQueryData(response.body.string()) { key ->
            key.size == 3 && key[0].stringOrNull() == "anime" && key[1].stringOrNull() == "details"
        }?.jsonObject ?: error("Failed to parse anime details")

        return SAnime.create().apply {
            val slug = anime["slug"]!!.jsonPrimitive.content
            setUrlWithoutDomain("/anime/$slug")
            thumbnail_url = baseUrl + anime["img"]!!.jsonPrimitive.content
            title = anime["title"]!!.jsonPrimitive.content
            status = when (anime["status"]?.jsonPrimitive?.contentOrNull) {
                "Završeno" -> SAnime.COMPLETED
                "Emituje se" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            artist = anime["studios"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            genre = anime["genres"]?.jsonArray?.joinToString { it.jsonPrimitive.content }

            description = buildString {
                anime["season"]?.jsonPrimitive?.contentOrNull?.let { append("Sezona: $it\n") }
                anime["aired"]?.jsonPrimitive?.contentOrNull?.let { append("Datum: $it\n") }
                anime["subtitle"]?.jsonPrimitive?.contentOrNull?.let { append("Alternativni naziv: $it\n") }
                // "desc" is streamed separately as a React reference (e.g. "$1a") on most pages,
                // so only use it when it was actually inlined as plain text.
                anime["desc"]?.jsonPrimitive?.contentOrNull
                    ?.takeUnless { it.startsWith("$") }
                    ?.let { append("\n\n$it") }
            }
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList() = AnimeSrbijaFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeSrbijaFilters.getSearchParameters(filters)
        val url = buildString {
            append("$baseUrl/filter?page=$page&sort=${params.sortby}")
            if (query.isNotBlank()) append("&q=$query")
            params.parsedCheckboxes.forEach {
                if (it.isNotBlank()) append("&$it")
            }
        }

        return GET(url)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val items = findQueryData(response.body.string()) { key ->
            key.size == 3 && key[0].stringOrNull() == "episodes" && key[1].stringOrNull() == "latest"
        }?.jsonArray ?: return AnimesPage(emptyList(), false)

        val animes = items.mapNotNull { it.jsonObject["anime"]?.jsonObject }.map(::parseAnime)
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    // ============================= Utilities ==============================

    /**
     * The site now hydrates its pages via Next.js RSC streaming chunks
     * (`self.__next_f.push([1, "..."])`) instead of a `__NEXT_DATA__` JSON blob.
     * Each chunk can contain one or more `id:<json>` entries; the ones we care
     * about carry a react-query `initialData` cache keyed by `queryKey`.
     * This walks every chunk looking for an entry whose queryKey satisfies [matches]
     * and returns its cached `data`.
     */
    private fun findQueryData(html: String, matches: (List<JsonElement>) -> Boolean): JsonElement? {
        for (match in NEXT_F_REGEX.findAll(html)) {
            val decoded = runCatching { json.decodeFromString<String>("\"${match.groupValues[1]}\"") }
                .getOrNull() ?: continue
            if ("\"initialData\"" !in decoded) continue

            for (line in decoded.split("\n")) {
                if ("\"initialData\"" !in line) continue
                val sep = line.indexOf(':')
                if (sep < 0) continue

                val entry = runCatching { json.parseToJsonElement(line.substring(sep + 1)) }.getOrNull()
                val initialData = entry?.jsonArray?.getOrNull(3)
                    ?.jsonObject?.get("initialData")?.jsonObject ?: continue

                for (queryEntry in initialData.values) {
                    val queryKey = queryEntry.jsonObject["queryKey"]?.jsonArray ?: continue
                    if (matches(queryKey.toList())) return queryEntry.jsonObject["data"]
                }
            }
        }
        return null
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun parseAnime(item: JsonObject): SAnime = SAnime.create().apply {
        val slug = item["slug"]!!.jsonPrimitive.content
        setUrlWithoutDomain("/anime/$slug")
        thumbnail_url = baseUrl + item["img"]!!.jsonPrimitive.content
        title = item["title"]!!.jsonPrimitive.content
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val NEXT_F_REGEX = Regex(
            """self\.__next_f\.push\(\[1,"(.*?)"\]\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
