package eu.kanade.tachiyomi.animeextension.es.serieskao

import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.burstcloudextractor.BurstCloudExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.fastreamextractor.FastreamExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamsilkextractor.StreamSilkExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class Serieskao :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SeriesKao"

    override val baseUrl = "https://serieskao.top"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "VidGuard",
        )

        private const val AES_KEY = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"
        private val POW_CHALLENGE_REGEX = """POW_CHALLENGE\s*=\s*['"]([^'"]+)['"]""".toRegex()
        private val POW_DIFFICULTY_REGEX = """POW_DIFFICULTY\s*=\s*(\d+)""".toRegex()
        private val POW_SALT_REGEX = """POW_SALT\s*=\s*['"]([^'"]+)['"]""".toRegex()
        private const val POW_MAX_NONCE = 5_000_000
        private val DATA_LINK_REGEX = """dataLink\s*=\s*([^;]+);""".toRegex(RegexOption.DOT_MATCHES_ALL)
    }

    override fun popularAnimeSelector(): String = "article.card"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.card__link")!!.attr("href"))
        title = element.selectFirst(".card__title")?.text()
            ?: element.selectFirst("img")?.attr("alt").orEmpty()

        val image = element.selectFirst("img")
        val thumb = image?.attr("src").orEmpty().ifBlank { image?.attr("data-src").orEmpty() }
        thumbnail_url = thumb.replace("/w300/", "/w500/")
    }

    override fun popularAnimeNextPageSelector(): String = "a.pagination__btn[aria-label=Siguiente]"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.useAsJsoup()
        val url = response.request.url.toString()

        if (url.contains("/pelicula/")) {
            return listOf(
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "PELÍCULA"
                    setUrlWithoutDomain(url)
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        val numberRegex = Regex("\\d+")
        val seasonIds = doc.select("select#temporadas_select option[value]").map { it.attr("value") }

        val seasonPanes = if (seasonIds.isEmpty()) {
            listOf("1" to doc.select(".episodes-list"))
        } else {
            seasonIds.mapIndexed { index, seasonId ->
                val seasonNumber = numberRegex.find(seasonId)?.value ?: (index + 1).toString()
                seasonNumber to doc.select("#$seasonId.episodes-list")
            }
        }

        seasonPanes.forEach { (seasonNumber, panes) ->
            panes.select("a.episode-item").forEach { element ->
                val episodeNumber = element.selectFirst(".episode-item__number")?.text()
                    ?.let { numberRegex.find(it)?.value } ?: "0"
                val episodeTitle = element.selectFirst(".episode-item__title")?.text()
                    .orEmpty().ifBlank { "Episodio $episodeNumber" }

                episodes += SEpisode.create().apply {
                    episode_number = episodeNumber.toFloatOrNull() ?: 0F
                    name = "T$seasonNumber - Episodio $episodeNumber: $episodeTitle"
                    setUrlWithoutDomain(element.attr("href"))
                }
            }
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val playerUrls = document.select("iframe#player-iframe[src], iframe[src*=/vidurl/]")
            .map { it.attr("abs:src") }
            .filter { it.startsWith("http") }
            .distinct()

        if (playerUrls.isEmpty()) return emptyList()

        val referer = response.request.url.toString()
        val headers = headersBuilder().set("Referer", referer).build()

        return playerUrls.parallelCatchingFlatMapBlocking { videoUrl ->
            val body = client.newCall(GET(videoUrl, headers)).awaitSuccess().bodyString()
            if (body.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val bodyDoc = Jsoup.parse(body)
            val parsedLinks = extractNewExtractorLinks(bodyDoc, body)

            if (parsedLinks.isNullOrEmpty()) {
                Log.w("SeriesKao", "Sin enlaces descifrados para $videoUrl")
                return@parallelCatchingFlatMapBlocking emptyList()
            }

            parsedLinks.parallelCatchingFlatMap { (url, lang) ->
                serverVideoResolver(url, lang)
            }
        }
    }

    private fun extractNewExtractorLinks(doc: Document, htmlContent: String): List<Pair<String, String>>? {
        val links = mutableListOf<Pair<String, String>>()

        val scriptData = doc.select("script")
            .asSequence()
            .map(Element::data)
            .firstOrNull { it.contains("dataLink") }

        val rawExpression = scriptData?.let {
            getFirstMatch(DATA_LINK_REGEX, it)
        } ?: getFirstMatch(DATA_LINK_REGEX, htmlContent)

        val jsonPayload = resolveDataLink(rawExpression) ?: return null
        val powKey = solveProofOfWork(htmlContent)

        val items = runCatching {
            jsonPayload.parseAs<List<Item>>()
        }.getOrElse {
            Log.e("SeriesKao", "No se pudo parsear dataLink", it)
            return null
        }

        val idiomas = mapOf("LAT" to "[LAT]", "ESP" to "[CAST]", "SUB" to "[SUB]")

        items.forEach { item ->
            val languageKey = item.video_language?.uppercase() ?: ""
            val languageCode = idiomas[languageKey] ?: "unknown"

            item.sortedEmbeds.forEach { embed ->
                if (!"video".equals(embed.type, ignoreCase = true)) return@forEach

                val decryptedLink = decryptEmbedLink(embed.link, powKey)
                decryptedLink?.let { links.add(it to languageCode) }
            }
        }

        return links.ifEmpty { null }
    }

    private fun resolveDataLink(rawExpression: String?): String? {
        if (rawExpression.isNullOrBlank()) return null

        var expr = rawExpression.trim().trimEnd(';')

        fun String.removeOuterCall(prefix: String): String? {
            if (!startsWith(prefix, ignoreCase = true) || !endsWith(')')) return null
            val start = indexOf('(')
            val end = lastIndexOf(')')
            if (start == -1 || end == -1 || end <= start) return null
            return substring(start + 1, end).trim()
        }

        fun String.trimMatchingQuotes(): String = if ((startsWith('"') && endsWith('"')) || (startsWith('\'') && endsWith('\''))) {
            substring(1, length - 1)
        } else {
            this
        }

        while (true) {
            expr.removeOuterCall("JSON.parse")?.let {
                expr = it
            }
            expr.removeOuterCall("window.JSON.parse")?.let {
                expr = it
            }
            expr.removeOuterCall("decodeURIComponent")?.let {
                expr = runCatching { URLDecoder.decode(it.trimMatchingQuotes(), "UTF-8") }
                    .getOrElse { return null }
            }
            expr.removeOuterCall("window.decodeURIComponent")?.let {
                expr = runCatching { URLDecoder.decode(it.trimMatchingQuotes(), "UTF-8") }
                    .getOrElse { return null }
            }
            expr.removeOuterCall("atob")?.let {
                expr = runCatching {
                    String(Base64.decode(it.trimMatchingQuotes(), Base64.DEFAULT))
                }.getOrElse { return null }
            }
            expr.removeOuterCall("window.atob")?.let {
                expr = runCatching {
                    String(Base64.decode(it.trimMatchingQuotes(), Base64.DEFAULT))
                }.getOrElse { return null }
            }
            break
        }

        expr = expr.trim().trimMatchingQuotes()

        return expr.takeIf { it.isNotBlank() }
    }

    // embed69 derives the AES key from a small proof-of-work:
    // nonce = first n where sha256(challenge + n) starts with `difficulty` zeros,
    // key = sha256(challenge + nonce + salt).
    private fun solveProofOfWork(html: String): ByteArray? {
        val challenge = POW_CHALLENGE_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val difficulty = POW_DIFFICULTY_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val salt = POW_SALT_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val prefix = "0".repeat(difficulty)
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0
        while (nonce < POW_MAX_NONCE) {
            val hash = digest.digest("$challenge$nonce".toByteArray()).joinToString("") { "%02x".format(it) }
            if (hash.startsWith(prefix)) {
                return digest.digest("$challenge$nonce$salt".toByteArray())
            }
            nonce++
        }
        return null
    }

    private fun decryptWithKey(link: String, key: ByteArray): String? = runCatching {
        val raw = Base64.decode(link, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(raw.copyOfRange(0, 16)))
        String(cipher.doFinal(raw.copyOfRange(16, raw.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun decryptEmbedLink(rawLink: String?, powKey: ByteArray? = null): String? {
        if (rawLink.isNullOrBlank()) return null

        val link = rawLink.trim()
        if (link.startsWith("http", true)) return link

        powKey?.let { key -> decryptWithKey(link, key)?.takeIf { it.startsWith("http") }?.let { return it } }

        CryptoAES.decryptCbcIV(link, AES_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        CryptoAES.decrypt(link, AES_KEY).takeIf { it.isNotBlank() }?.let { return it }

        decodeJwtLink(link)?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun decodeJwtLink(token: String): String? {
        val segments = token.split('.')
        if (segments.size < 2) return null

        val payload = segments[1].padBase64Url()

        return runCatching {
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            val element = String(decoded).parseAs<JsonElement>()
            val obj = element.jsonObject

            val link = obj["link"]?.jsonPrimitive?.contentOrNull
            val nestedLink = obj["data"]?.jsonObject?.get("link")?.jsonPrimitive?.contentOrNull

            link ?: nestedLink
        }.getOrNull()
    }

    private fun String.padBase64Url(): String {
        val padding = (4 - length % 4) % 4
        return this + "=".repeat(padding)
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    private suspend fun serverVideoResolver(url: String, prefix: String = ""): List<Video> = when {
        arrayOf("voe").any(url) -> voeExtractor.videosFromUrl(url, "$prefix ")

        arrayOf("ok.ru", "okru").any(url) -> okruExtractor.videosFromUrl(url, prefix)

        arrayOf("filemoon", "moonplayer").any(url) -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")

        !url.contains("disable") && (arrayOf("amazon", "amz").any(url)) -> {
            val body = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
            if (body.select("script:containsData(var shareId)").toString().isNotBlank()) {
                val shareId = body.selectFirst("script:containsData(var shareId)")!!.data()
                    .substringAfter("shareId = \"").substringBefore("\"")
                val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
                    .awaitSuccess().useAsJsoup()
                val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
                val amazonApi =
                    client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
                        .awaitSuccess().useAsJsoup()
                val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
                listOf(Video(videoUrl, "$prefix Amazon", videoUrl))
            } else {
                emptyList()
            }
        }

        arrayOf("uqload").any(url) -> uqloadExtractor.videosFromUrl(url, prefix)

        arrayOf("mp4upload").any(url) -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$prefix ")

        arrayOf("wishembed", "streamwish", "strwish", "wish", "hglink", "iplayerhls", "streamgg").any(url) -> {
            streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
        }

        arrayOf("doodstream", "dood.", "ds2play", "doods.").any(url) -> {
            val url2 = url.replace("https://doodstream.com/e/", "https://d0000d.com/e/")
            doodExtractor.videosFromUrl(url2, "$prefix DoodStream")
        }

        arrayOf("streamlare").any(url) -> streamlareExtractor.videosFromUrl(url, prefix)

        arrayOf("yourupload", "upload").any(url) -> yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")

        arrayOf("burstcloud", "burst").any(url) -> burstCloudExtractor.videoFromUrl(url, headers = headers, prefix = "$prefix ")

        arrayOf("fastream").any(url) -> fastreamExtractor.videosFromUrl(url, prefix = "$prefix Fastream:")

        arrayOf("upstream").any(url) -> upstreamExtractor.videosFromUrl(url, prefix = "$prefix ")

        arrayOf("streamsilk").any(url) -> streamSilkExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamSilk:$it" })

        arrayOf("streamtape", "stp", "stape").any(url) -> streamTapeExtractor.videosFromUrl(url, quality = "$prefix StreamTape")

        arrayOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet", "morencius").any(url) -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamHideVid:$it" })

        arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay").any(url) -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")

        else -> emptyList()
    }

    private fun getFirstMatch(regex: Regex, input: String): String = regex.find(input)?.groupValues?.get(1) ?: ""

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val tagFilter = filterList.find { it is YearFilter } as YearFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            tagFilter.state != 0 -> GET("$baseUrl/series?year=${tagFilter.toUriPart()}&page=$page")
            else -> GET("$baseUrl/peliculas?page=$page")
        }
    }
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.detail-hero__title")?.text()?.ifBlank { "Sin título" } ?: "Sin título"
        thumbnail_url = document.selectFirst("figure.detail-hero__poster img")?.attr("src")
        description = document.selectFirst(".detail-hero__desc")?.text()
        genre = document.select("a.detail-hero__genre").joinToString { it.text() }
        status = SAnime.COMPLETED
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro de año"),
        GenreFilter(),
        AnimeFilter.Header("Busqueda por año"),
        YearFilter(),
    )

    private class GenreFilter :
        UriPartFilter(
            "Géneros",
            arrayOf(
                Pair("<selecionar>", ""),
                Pair("Peliculas", "peliculas"),
                Pair("Series", "series"),
                Pair("Doramas", "generos/dorama"),
                Pair("Animes", "animes"),
                Pair("Acción", "generos/accion"),
                Pair("Animación", "generos/animacion"),
                Pair("Aventura", "generos/aventura"),
                Pair("Ciencia Ficción", "generos/ciencia-ficcion"),
                Pair("Comedia", "generos/comedia"),
                Pair("Crimen", "generos/crimen"),
                Pair("Documental", "generos/documental"),
                Pair("Drama", "generos/drama"),
                Pair("Fantasía", "generos/fantasia"),
                Pair("Foreign", "generos/foreign"),
                Pair("Guerra", "generos/guerra"),
                Pair("Historia", "generos/historia"),
                Pair("Misterio", "generos/misterio"),
                Pair("Pelicula de Televisión", "generos/pelicula-de-la-television"),
                Pair("Romance", "generos/romance"),
                Pair("Suspense", "generos/suspense"),
                Pair("Terror", "generos/terror"),
                Pair("Western", "generos/western"),
            ),
        )
    private class YearFilter :
        UriPartFilter(
            "Año",
            arrayOf(Pair("<selecionar>", "")) +
                (2024 downTo 1979).map {
                    Pair(it.toString(), it.toString())
                }.toTypedArray(),
        )

    open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    @Serializable
    data class Item(
        val file_id: Int? = null,
        val video_language: String? = null,
        val sortedEmbeds: List<Embed> = emptyList(),
    )

    @Serializable
    data class Embed(
        val servername: String? = null,
        val link: String? = null,
        val type: String? = null,
    )

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
