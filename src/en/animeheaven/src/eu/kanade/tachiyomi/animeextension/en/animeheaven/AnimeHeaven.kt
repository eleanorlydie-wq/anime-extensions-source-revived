package eu.kanade.tachiyomi.animeextension.en.animeheaven

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimeHeaven : AnimeHttpSource() {

    override val name = "AnimeHeaven"

    override val baseUrl = "https://animeheaven.me"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/popular.php", headers)

    override fun popularAnimeParse(response: Response) = chartParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/new.php", headers)

    override fun latestUpdatesParse(response: Response) = chartParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selected.orEmpty()
        val url = when {
            query.isNotBlank() -> "$baseUrl/search.php".toHttpUrl().newBuilder().addQueryParameter("s", query)
            tag.isNotEmpty() -> "$baseUrl/tags.php".toHttpUrl().newBuilder().addQueryParameter("tag", tag)
            else -> "$baseUrl/popular.php".toHttpUrl().newBuilder()
        }
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select(".similarimg, .chart").mapNotNull(::animeFromElement).distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    private fun chartParse(response: Response): AnimesPage {
        val animes = response.useAsJsoup().select(".chart").mapNotNull(::animeFromElement).distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    private fun animeFromElement(element: Element): SAnime? {
        val link = element.selectFirst("a[href*=anime.php]") ?: return null
        val image = element.selectFirst("img.coverimg")
        val animeTitle = image?.attr("alt")?.takeIf(String::isNotBlank) ?: return null
        return SAnime.create().apply {
            setUrlWithoutDomain(link.attr("abs:href"))
            title = animeTitle
            thumbnail_url = image.attr("abs:src")
        }
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Text search ignores the tag filter"),
        TagFilter(),
    )

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".infotitle")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".info img.posterimg, .info img.coverimg")?.attr("abs:src")
            description = document.selectFirst(".infodes")?.text()?.takeIf(String::isNotBlank)
            genre = document.select(".infotags a").joinToString { it.text() }
            status = if (document.selectFirst(".info2:contains(Countdown)") != null) SAnime.ONGOING else SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        // Hard content block, deliberately not configurable. The site has no tag for sexualised minors, so
        // every title with sexual-content tags is refused.
        if (document.select(".infotags a").any { it.text().lowercase() in BLOCKED_TAGS }) {
            throw Exception("This title is blocked by the extension")
        }
        return document.select("a[onclick^=gatea]").mapNotNull { link ->
            val key = link.id().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val number = link.selectFirst(".watch2")?.text().orEmpty()
            SEpisode.create().apply {
                url = "/gate.php?key=$key"
                name = "Episode $number"
                episode_number = number.toFloatOrNull() ?: -1f
            }
        }
    }

    // gate.php picks the episode from the "key" cookie that the site's own gatea() sets on click.
    override fun videoListRequest(episode: SEpisode): Request {
        val key = episode.url.substringAfter("key=")
        val pageHeaders = headers.newBuilder().add("Cookie", "key=$key").build()
        return GET("$baseUrl/gate.php", pageHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoHeaders = headers.newBuilder().build()
        return response.useAsJsoup().select("video source[src]").mapNotNull { source ->
            val url = source.attr("abs:src").takeIf { "/video.mp4" in it } ?: return@mapNotNull null
            val host = url.toHttpUrl().host.substringBefore('.')
            Video(url, "Mirror ${host.uppercase()}", url, headers = videoHeaders)
        }.distinctBy { it.videoUrl }
    }

    private class TagFilter : AnimeFilter.Select<String>("Tag", TAGS) {
        val selected get() = if (state == 0) "" else TAGS[state]
    }

    companion object {
        private val BLOCKED_TAGS = setOf(
            "based on an eroge", "borderline h", "ecchi", "explicit sex", "hentai", "loli", "lolicon", "nudity",
            "panty shots", "sexual abuse", "shota", "shotacon",
        )

        private val TAGS = arrayOf(
            "<Select>", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Historical",
            "Horror", "Isekai", "Josei", "Magic", "Martial Arts", "Mecha", "Military", "Music", "Mystery",
            "Parody", "Psychological", "Romance", "School Life", "Sci Fi", "Seinen", "Shoujo", "Shounen",
            "Slice Of Life", "Sports", "Superpowers", "Supernatural", "Thriller", "Vampires",
        )
    }
}
