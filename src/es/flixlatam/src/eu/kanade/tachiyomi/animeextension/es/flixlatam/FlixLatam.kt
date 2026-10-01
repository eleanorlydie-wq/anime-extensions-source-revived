package eu.kanade.tachiyomi.animeextension.es.flixlatam

import android.util.Base64
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
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FlixLatam :
    DooPlay(
        "es",
        "FlixLatam",
        "https://flixlatam.com",
    ) {
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun latestUpdatesNextPageSelector() = "div.pagination > a > i.fa-chevron-right"

    override fun latestUpdatesRequest(page: Int): Request {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return GET("$baseUrl/year/$year?page=$page")
    }

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val players = document.select("ul#playeroptionsul li")
        val referer = response.request.url.toString()
        val embedHeaders = headersBuilder().set("Referer", referer).build()

        val ajaxUrls = players.parallelCatchingFlatMapBlocking { player ->
            listOfNotNull(getPlayerUrl(player))
        }
        val iframeUrls = document.select("iframe[src]")
            .map { it.attr("abs:src") }
            .filter { "/vidurl/" in it || "embed69" in it }

        return (ajaxUrls + iframeUrls).distinct().parallelCatchingFlatMapBlocking { url ->
            if (url.contains("embed69") || "/vidurl/" in url) {
                val htmlContent = client.newCall(GET(url, embedHeaders)).awaitSuccess().bodyString()
                if (htmlContent.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

                val embedDoc = Jsoup.parse(htmlContent)
                val links = extractNewExtractorLinks(embedDoc, htmlContent)
                    ?: return@parallelCatchingFlatMapBlocking emptyList()

                links.parallelCatchingFlatMap { (link, language) ->
                    serverVideoResolver(link, " $language")
                }
            } else {
                emptyList()
            }
        }
    }

    private suspend fun getPlayerUrl(player: Element): String? {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .awaitSuccess().bodyString()
            .takeIf { "\"embed_url\":\"" in it }
            ?.substringAfter("\"embed_url\":\"")
            ?.substringBefore("\",")
            ?.replace("\\", "")
            ?.takeIf(String::isNotBlank)
    }

    /*-------------------------------- Video extractors ------------------------------------*/
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

    private suspend fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val matched = conventions.firstOrNull { (_, names) -> names.any { it.lowercase() in url.lowercase() } }?.first
        return when (matched) {
            "voe" -> voeExtractor.videosFromUrl(url, "$prefix ")
            "okru" -> okruExtractor.videosFromUrl(url, prefix)
            "filemoon" -> filemoonExtractor.videosFromUrl(url, "$prefix Filemoon:")
            "amazon" -> extractAmazonVideo(url, prefix)
            "uqload" -> uqloadExtractor.videosFromUrl(url, prefix)
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(url, headers, "$prefix ")
            "streamwish" -> streamWishExtractor.videosFromUrl(url, "$prefix StreamWish:")
            "doodstream" -> doodExtractor.videosFromUrl(url.replace("https://doodstream.com/e/", "https://d0000d.com/e/"), "$prefix DoodStream")
            "streamlare" -> streamlareExtractor.videosFromUrl(url, prefix)
            "yourupload" -> yourUploadExtractor.videoFromUrl(url, headers, "$prefix ")
            "burstcloud" -> burstCloudExtractor.videoFromUrl(url, headers, "$prefix ")
            "fastream" -> fastreamExtractor.videosFromUrl(url, "$prefix Fastream:")
            "upstream" -> upstreamExtractor.videosFromUrl(url, "$prefix ")
            "streamsilk" -> streamSilkExtractor.videosFromUrl(url, "$prefix StreamSilk:")
            "streamtape" -> streamTapeExtractor.videosFromUrl(url, "$prefix StreamTape")
            "vidhide" -> vidHideExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamHideVid:$it" })
            "vidguard" -> vidGuardExtractor.videosFromUrl(url, prefix = "$prefix ")
            else -> emptyList()
        }
    }

    private val conventions = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "amazon" to listOf("amazon", "amz"),
        "uqload" to listOf("uqload"),
        "mp4upload" to listOf("mp4upload"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "Kswplayer", "Swhoi", "Multimovies", "Uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg", "hglink"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
        "streamlare" to listOf("streamlare", "slmaxed"),
        "yourupload" to listOf("yourupload", "upload"),
        "burstcloud" to listOf("burstcloud", "burst"),
        "fastream" to listOf("fastream"),
        "upstream" to listOf("upstream"),
        "streamsilk" to listOf("streamsilk"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "vidhide" to listOf("ahvsh", "streamhide", "guccihide", "streamvid", "vidhide", "kinoger", "smoothpre", "dhtpre", "peytonepre", "earnvids", "ryderjet", "morencius"),
        "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
    )

    private suspend fun extractAmazonVideo(url: String, prefix: String): List<Video> {
        val body = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        val shareId = body.selectFirst("script:containsData(var shareId)")
            ?.data()
            ?.substringAfter("shareId = \"")
            ?.substringBefore("\"") ?: return emptyList()

        val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
            .awaitSuccess().useAsJsoup()

        val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
        val amazonApi = client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
            .awaitSuccess().useAsJsoup()

        val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
        return listOf(Video(videoUrl, "$prefix Amazon", videoUrl))
    }

    private fun extractNewExtractorLinks(doc: Document, htmlContent: String): List<Pair<String, String>>? {
        val scriptData = doc.select("script")
            .asSequence()
            .map(Element::data)
            .firstOrNull { it.contains("dataLink") }

        val rawExpression = scriptData?.let {
            getFirstMatch(DATA_LINK_REGEX, it)
        } ?: getFirstMatch(DATA_LINK_REGEX, htmlContent)

        val jsonPayload = resolveDataLink(rawExpression) ?: return null
        val powKey = solveProofOfWork(htmlContent)

        val items = jsonPayload.parseAs<List<Item>>()
        val idiomas = mapOf("LAT" to "[LAT]", "ESP" to "[CAST]", "SUB" to "[SUB]")

        return items.flatMap { item ->
            val languageKey = item.video_language?.uppercase() ?: ""
            val languageCode = idiomas[languageKey] ?: "unknown"

            item.sortedEmbeds.mapNotNull { embed ->
                runCatching {
                    if (!"video".equals(embed.type, ignoreCase = true)) return@mapNotNull null

                    val decryptedLink = decryptEmbedLink(embed.link, powKey)
                    decryptedLink?.let { Pair(it, languageCode) }
                }.getOrNull()
            }
        }.ifEmpty { null }
    }

    private fun getFirstMatch(regex: Regex, input: String): String? = regex.find(input)?.groupValues?.get(1)

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
            when {
                expr.removeOuterCall("JSON.parse") != null -> expr = expr.removeOuterCall("JSON.parse")!!
                expr.removeOuterCall("window.JSON.parse") != null -> expr = expr.removeOuterCall("window.JSON.parse")!!
                expr.removeOuterCall("decodeURIComponent") != null -> {
                    val inner = expr.removeOuterCall("decodeURIComponent")!!.trimMatchingQuotes()
                    expr = runCatching { URLDecoder.decode(inner, "UTF-8") }.getOrElse { return null }
                }
                expr.removeOuterCall("window.decodeURIComponent") != null -> {
                    val inner = expr.removeOuterCall("window.decodeURIComponent")!!.trimMatchingQuotes()
                    expr = runCatching { URLDecoder.decode(inner, "UTF-8") }.getOrElse { return null }
                }
                expr.removeOuterCall("atob") != null -> {
                    val inner = expr.removeOuterCall("atob")!!.trimMatchingQuotes()
                    expr = runCatching { String(Base64.decode(inner, Base64.DEFAULT)) }.getOrElse { return null }
                }
                expr.removeOuterCall("window.atob") != null -> {
                    val inner = expr.removeOuterCall("window.atob")!!.trimMatchingQuotes()
                    expr = runCatching { String(Base64.decode(inner, Base64.DEFAULT)) }.getOrElse { return null }
                }
                else -> break
            }
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
            val element = String(decoded, Charsets.UTF_8).parseAs<JsonElement>()
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

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = FlixLatamFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = FlixLatamFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("ratings", "tendencias", "pelicula")) {
                    "/${params.genre}"
                } else {
                    "/genero/${params.genre}"
                }
            }

            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=$query"
                        else -> "/"
                    },
                )

                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path")
        } else {
            GET("$baseUrl$path/page/$page")
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
        }
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================
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
        val download: String? = null,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "[LAT]"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private val PREF_LANG_ENTRIES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val PREF_LANG_VALUES = arrayOf("[LAT]", "[SUB]", "[CAST]")
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload", "VidGuard", "StreamHideVid", "Voe")
        private const val AES_KEY = "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"
        private val POW_CHALLENGE_REGEX = """POW_CHALLENGE\s*=\s*['"]([^'"]+)['"]""".toRegex()
        private val POW_DIFFICULTY_REGEX = """POW_DIFFICULTY\s*=\s*(\d+)""".toRegex()
        private val POW_SALT_REGEX = """POW_SALT\s*=\s*['"]([^'"]+)['"]""".toRegex()
        private const val POW_MAX_NONCE = 5_000_000
        private val DATA_LINK_REGEX = """dataLink\s*=\s*([^;]+);""".toRegex(RegexOption.DOT_MATCHES_ALL)
    }
}
