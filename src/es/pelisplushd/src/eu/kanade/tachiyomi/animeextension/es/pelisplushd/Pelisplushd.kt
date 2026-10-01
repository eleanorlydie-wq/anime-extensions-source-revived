package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.pelisplus.Filters
import eu.kanade.tachiyomi.multisrc.pelisplus.PelisPlus
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.bodyString
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.flatMapCatching
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Pelisplushd : PelisPlus() {

    override val name = "PelisPlusHD"

    override val baseUrl = "https://pelisplushd.bz"

    override val id: Long = 1400819034564144238L

    companion object {
        private val REGEX_VIDEO_OPTS = "'(https?://[^']*)'".toRegex()
        private val POW_CHALLENGE_REGEX = """POW_CHALLENGE\s*=\s*['"]([^'"]+)['"]""".toRegex()
        private val POW_DIFFICULTY_REGEX = """POW_DIFFICULTY\s*=\s*(\d+)""".toRegex()
        private val POW_SALT_REGEX = """POW_SALT\s*=\s*['"]([^'"]+)['"]""".toRegex()
        private const val POW_MAX_NONCE = 5_000_000
    }

    override fun popularAnimeSelector(): String = "div.Posters a.Posters-link"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
        title = element.select("a div.listing-content p").text()
        thumbnail_url = element.select("a img").attr("src").replace("/w154/", "/w200/")
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
                setUrlWithoutDomain(response.request.url.toString())
            }
            episodes.add(episode)
        } else {
            jsoup.select("div.tab-content div a").forEachIndexed { index, element ->
                val episode = SEpisode.create().apply {
                    episode_number = (index + 1).toFloat()
                    name = element.text()
                    setUrlWithoutDomain(element.attr("abs:href"))
                }
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val data = document.selectFirst("script:containsData(video[1] = )")?.data() ?: return emptyList()

        return REGEX_VIDEO_OPTS.findAll(data).map { it.groupValues[1] }
            .filter { it.contains("embed69.org") }.toList()
            .flatMapCatching { opt ->
                val html = client.newCall(GET(opt, headers)).execute().bodyString()
                val docResponse = Jsoup.parse(html)
                val cryptoScript = docResponse.selectFirst("script:containsData(let dataLink)")?.data()
                if (!cryptoScript.isNullOrBlank()) {
                    val jsLinksMatch = cryptoScript.substringAfter("let dataLink =").substringBefore("];") + "]"
                    val powKey = solveProofOfWork(html)
                    jsLinksMatch.parseAs<List<DataLinkDto>>().flatMap { data ->
                        data.sortedEmbeds.filterNotNull()
                            .filter { it.type.equals("video", true) }
                            .mapNotNull { embed ->
                                val link = embed.link?.let { decryptLink(it, powKey) } ?: return@mapNotNull null
                                (embed.servername ?: "Embed69") to (data.videoLanguage ?: "") to link
                            }
                    }.catchingFlatMapBlocking {
                        serverVideoResolver(it.third, it.second, it.first)
                    }
                } else {
                    docResponse.select("li[onclick]")
                        .flatMap { fetchUrls(it.attr("onclick")) }
                        .catchingFlatMapBlocking { realUrl ->
                            serverVideoResolver(realUrl)
                        }
                }
            }
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

    private fun decryptLink(link: String, key: ByteArray?): String? {
        if (link.startsWith("http", true)) return link
        if (key == null) return null
        return runCatching {
            val raw = Base64.decode(link, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(raw.copyOfRange(0, 16)))
            String(cipher.doFinal(raw.copyOfRange(16, raw.size)), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val tagFilter = filters.find { it is Tags } as Tags

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            tagFilter.state.isNotBlank() -> GET("$baseUrl/year/${tagFilter.state}?page=$page")
            else -> GET("$baseUrl/peliculas?page=$page")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.m-b-5")!!.text()
        thumbnail_url = document.selectFirst("div.card-body div.row div.col-sm-3 img.img-fluid")!!
            .attr("src").replace("/w154/", "/w500/")
        description = document.selectFirst("div.col-sm-4 div.text-large")!!.ownText()
        genre = document.select("div.p-v-20.p-h-15.text-center a span").joinToString { it.text() }
        status = SAnime.COMPLETED
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro de año"),
        GenreFilter(),
        AnimeFilter.Header("Busqueda por año"),
        Tags("Año"),
    )

    private class GenreFilter :
        Filters.UriPartFilter(
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

    private class Tags(name: String) : AnimeFilter.Text(name)

    infix fun <A, B> Pair<A, B>.to(c: String): Triple<A, B, String> = Triple(this.first, this.second, c)

    @Serializable
    data class DataLinkDto(
        @SerialName("video_language")
        val videoLanguage: String? = null,
        @SerialName("sortedEmbeds")
        val sortedEmbeds: List<SortedEmbedsDto?> = emptyList(),
    )

    @Serializable
    data class SortedEmbedsDto(
        val link: String? = null,
        val type: String? = null,
        val servername: String? = null,
    )
}
