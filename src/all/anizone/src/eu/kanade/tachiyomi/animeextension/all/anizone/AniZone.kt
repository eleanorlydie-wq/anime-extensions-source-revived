package eu.kanade.tachiyomi.animeextension.all.anizone

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class AniZone :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniZone"

    override val baseUrl = "https://anizone.to"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private var token: String = ""

    // Livewire state of the anime index, kept between pages of the same listing
    private var listSnapshot: String = ""
    private var listCursor: String? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = listRequest(page, sort = "title-asc")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val component = response.parseAs<LivewireDto>().components.first()
        listSnapshot = component.snapshot

        val result = component.itemsPage()
        listCursor = result.nextCursor

        val animeList = result.items.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.url)
                title = item.mainTitle ?: item.pickTitle()
                thumbnail_url = item.cover
            }
        }

        return AnimesPage(animeList, result.hasMore && result.nextCursor != null)
    }

    private fun LivewireDto.ComponentDto.itemsPage(): ItemsPageDto = effects.dispatches
        .firstOrNull { it.name == "filters-reset" || it.name == "items-loaded" }
        ?.params
        ?.let { json.decodeFromJsonElement<ItemsPageDto>(it) }
        ?: ItemsPageDto()

    private fun listRequest(page: Int, sort: String, query: String = "", type: String = "0"): Request {
        if (page > 1) {
            val cursor = listCursor ?: throw Exception("Missing page cursor, reload the list")
            return livewireRequest(
                listSnapshot,
                buildJsonObject { },
                buildJsonArray {
                    addJsonObject {
                        put("path", "")
                        put("method", "loadPage")
                        putJsonArray("params") { add(cursor) }
                    }
                },
            )
        }

        listSnapshot = fetchSnapshot("/anime")
        listCursor = null

        val updates = buildJsonObject {
            put("search", query)
            put("sort", sort)
            put("type", type)
        }
        return livewireRequest(listSnapshot, updates, buildJsonArray { })
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = listRequest(page, sort = "release-desc")

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sortFilter = filters.filterIsInstance<SortFilter>().first()
        val typeFilter = filters.filterIsInstance<TypeFilter>().first()

        return listRequest(page, sortFilter.toUriPart(), query, typeFilter.toUriPart())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(SortFilter(), TypeFilter())

    private class SortFilter :
        UriPartFilter(
            "Sort",
            arrayOf(
                Pair("A-Z", "title-asc"),
                Pair("Z-A", "title-desc"),
                Pair("Earliest Release", "release-asc"),
                Pair("Latest Release", "release-desc"),
                Pair("First Added", "added-asc"),
                Pair("Last Added", "added-desc"),
            ),
        )

    private class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", "0"),
                Pair("Unknown", "1"),
                Pair("TV Series", "2"),
                Pair("OVA", "3"),
                Pair("Movie", "4"),
                Pair("Other", "5"),
                Pair("Web", "6"),
                Pair("TV Special", "7"),
                Pair("Music Video", "8"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.title().substringBeforeLast(" \u2014 ")
            thumbnail_url = document.selectFirst("img[class*=zoom-in]")?.attr("abs:src")
            status = document.select("span.inline-block").firstNotNullOfOrNull {
                when (it.text().lowercase()) {
                    "completed" -> SAnime.COMPLETED
                    "ongoing" -> SAnime.ONGOING
                    else -> null
                }
            } ?: SAnime.UNKNOWN
            description = document.selectFirst("div:has(> h3.sr-only) > div")?.html()
                ?.replace("<br>", "\n")
                ?.replace(MULTILINE_REGEX, "\n\n")
            genre = document.select("a[href*=/tag/]").joinToString { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val snapshot = fetchSnapshot(anime.url)

        return livewireRequest(
            snapshot,
            buildJsonObject { put("sort", "default-desc") },
            buildJsonArray { },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        var component = response.parseAs<LivewireDto>().components.first()
        var page = component.itemsPage()
        val items = page.items.toMutableList()

        while (page.hasMore && page.nextCursor != null) {
            val resp = client.newCall(
                livewireRequest(
                    component.snapshot,
                    buildJsonObject { },
                    buildJsonArray {
                        addJsonObject {
                            put("path", "")
                            put("method", "loadPage")
                            putJsonArray("params") { add(page.nextCursor!!) }
                        }
                    },
                ),
            ).execute()
            component = resp.parseAs<LivewireDto>().components.first()
            page = component.itemsPage()
            items.addAll(page.items)
        }

        return items.map { item ->
            SEpisode.create().apply {
                setUrlWithoutDomain(item.url)
                val number = item.slug.toFloatOrNull()
                episode_number = number ?: -1f
                val title = item.pickTitle()
                name = when {
                    number != null && title.isNotEmpty() -> "Episode ${item.slug}: $title"
                    number != null -> "Episode ${item.slug}"
                    else -> title.ifEmpty { item.slug }
                }
                date_upload = item.airDate?.let(::parseDate) ?: 0L
            }
        }
    }

    private fun ItemDto.pickTitle(): String = titleList?.get("1")
        ?: titleList?.get("5")
        ?: titleList?.values?.firstOrNull { !it.isNullOrBlank() }
        ?: mainTitle
        ?: ""

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    private val playlistUtils: PlaylistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val servers = document.select("button[wire:click^=setVideo]")

        val first = parsePlayer(document.html())
            ?: throw Exception("No video found")

        val m3u8List = mutableListOf(VideoData(first, servers.firstOrNull()?.serverName() ?: "Server"))

        if (servers.size > 1) {
            val snapshot = document.getSnapshot()
            servers.drop(1).forEach { server ->
                val videoId = SET_VIDEO_REGEX.find(server.attr("wire:click"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                runCatching {
                    val html = client.newCall(
                        livewireRequest(
                            snapshot,
                            buildJsonObject { },
                            buildJsonArray {
                                addJsonObject {
                                    put("path", "")
                                    put("method", "setVideo")
                                    putJsonArray("params") { add(videoId) }
                                }
                            },
                        ),
                    ).execute().parseAs<LivewireDto>().components.first().effects.html

                    parsePlayer(html)?.let { m3u8List.add(VideoData(it, server.serverName())) }
                }
            }
        }

        val serverList = if (preferences.dub) {
            m3u8List
        } else {
            m3u8List.reversed()
        }

        return serverList.flatMap {
            playlistUtils.extractFromHls(
                playlistUrl = it.player.src,
                referer = "$baseUrl/",
                videoNameGen = { q -> "${it.name} - $q" },
                subtitleList = it.player.subtitles.map { sub ->
                    Track(sub.file, sub.title ?: sub.language ?: "Subtitle")
                },
            )
        }
    }

    private fun Element.serverName(): String = selectFirst("img[alt]")?.attr("alt")?.ifBlank { null }
        ?: text().substringBefore("Source:").trim().ifEmpty { "Server" }

    private fun parsePlayer(html: String): PlayerDto? = PLAYER_REGEX.find(html)
        ?.groupValues?.get(1)
        ?.let(::unescapeJs)
        ?.parseAs<PlayerDto>()

    // The player config is a PHP-escaped JS string literal: \uXXXX, \\ and \/ sequences.
    private fun unescapeJs(raw: String): String = JS_ESCAPE_REGEX.replace(raw) {
        val code = it.groupValues[1]
        if (code.length == 5 && code[0] == 'u') code.substring(1).toInt(16).toChar().toString() else code
    }

    private class VideoData(
        val player: PlayerDto,
        val name: String,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================= Utilities ==============================

    private fun Document.getSnapshot(): String = this.selectFirst("main div[wire:snapshot]")!!
        .attr("wire:snapshot")

    /** Loads [path], remembers the csrf token and returns the main Livewire component snapshot. */
    private fun fetchSnapshot(path: String): String {
        val doc = client.newCall(GET(baseUrl + path, headers)).execute().asJsoup()

        token = doc.selectFirst("script[data-csrf]")
            ?.attr("data-csrf")
            ?.takeIf(String::isNotEmpty)
            ?: throw Exception("Failed to get csrf token")

        return doc.getSnapshot()
    }

    private fun livewireRequest(snapshot: String, updates: JsonObject, calls: JsonArray): Request {
        val headers = headersBuilder().apply {
            add("X-Livewire", "")
        }.build()

        val body = buildJsonObject {
            put("_token", token)
            putJsonArray("components") {
                addJsonObject {
                    put("calls", calls)
                    put("snapshot", snapshot)
                    put("updates", updates)
                }
            }
        }.toJsonRequestBody()

        return POST("$baseUrl/livewire/update", headers, body)
    }

    private fun parseDate(dateStr: String): Long = try {
        DATE_FORMAT.parse(dateStr)!!.time
    } catch (_: ParseException) {
        0L
    }

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.dub
        get() = getBoolean(PREF_DUB_KEY, PREF_DUB_DEFAULT)

    companion object {
        private val MULTILINE_REGEX = Regex("""\n{2,}""")
        private val DATE_FORMAT by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

        private val SET_VIDEO_REGEX = Regex("""setVideo\((\d+)\)""")
        private val PLAYER_REGEX = Regex("""vidstackPlayer\(JSON\.parse\('(.*?)'\)\)""", RegexOption.DOT_MATCHES_ALL)
        private val JS_ESCAPE_REGEX = Regex("""\\(u[0-9a-fA-F]{4}|.)""", RegexOption.DOT_MATCHES_ALL)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_DUB_KEY = "attempt_dub"
        private const val PREF_DUB_TITLE = "Attempt to prefer dub"
        private const val PREF_DUB_DEFAULT = false
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, new ->
                val index = findIndexOfValue(new as String)
                preferences.edit().putString(key, entryValues[index] as String).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DUB_KEY
            title = PREF_DUB_TITLE
            setDefaultValue(PREF_DUB_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)
    }
}
