package eu.kanade.tachiyomi.animeextension.de.kinoking

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.goodstramextractor.GoodStreamExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class Kinoking :
    DooPlay(
        "de",
        "Kinoking",
        "https://kinoking.cc",
    ) {
    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_TITLE = "Standard-Hoster"
        private const val PREF_HOSTER_DEFAULT = "https://dood"
        private val PREF_HOSTER_ENTRIES = arrayOf("Doodstream", "Voe", "Filehosted")
        private val PREF_HOSTER_VALUES = arrayOf("https://dood", "https://voe.sx", "https://fs1.filehosted")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf(
            "Doodstream",
            "Voe",
            "Filehosted",
            "MixDrop",
            "Streamtape",
            "Upstream",
            "GoodStream",
            "Direct",
        )
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf(
            "dood",
            "voe",
            "filehosted",
            "mixdrop",
            "streamtape",
            "upstream",
            "goodstream",
            "direct",
        )
        private val PREF_HOSTER_SELECTION_DEFAULT = PREF_HOSTER_SELECTION_VALUES.toSet()

        // Stop probing alternate mirrors once we already collected this many working videos.
        private const val MAX_WANTED_VIDEOS = 6

        // Hard cap on how many "#serverLinksContainer" mirror pages we will fetch, so a
        // string of dead mirrors can't blow past the harness/runtime's overall timeout.
        // Live-verified: fetching all 18 mirrors of movie.php?id=63080 through a 3-4s-timeout
        // probe client took ~8s total wall time, so this comfortably fits within budget.
        private const val MAX_MIRROR_FETCHES = 20

        // Matches the *rendered* (server-side, non-templated) player iframe emitted by both
        // movie.php and (after selecting a mirror) series.php, e.g. on movie.php?id=63080:
        //   target.innerHTML = `<iframe id="videoPlayer" src="https://cinesrc.st/embed/movie/1612018?prioritize=true&amp;lastserver=sturm" ...>`;
        private val VIDEO_PLAYER_SRC_REGEX = Regex("id=\"videoPlayer\"\\s+src=\"([^\"]+)\"")

        // series.php?id=<id> bakes every episode's real hoster link into an inline
        // `allEpisodesData = [...]` JS array, e.g. (series.php?id=36448):
        //   {"id":955866,...,"video_links":"https:\/\/voe.sx\/e\/iaybsqslbhbn","link_count":1}
        private val VIDEO_LINKS_REGEX = Regex("\"video_links\":\"([^\"]*)\"")

        private val DIRECT_FILE_REGEX = Regex("""\.(mp4|m3u8|mkv)(\?[^"']*)?$""", RegexOption.IGNORE_CASE)
    }

    override val videoSortPrefKey = PREF_HOSTER_KEY
    override val videoSortPrefDefault = PREF_HOSTER_DEFAULT

    // ============================== Popular ===============================
    // KinoKing V2 redesign: the WordPress/DooPlay markup is gone. The home page now
    // renders "Top Filme"/"Top Serien" carousels as plain divs (no <a href>) carrying
    // data-id/data-type/data-title/data-img attributes, e.g.:
    // <div class="group/card relative shrink-0 fav-data-source" data-id="63080"
    //   data-type="movie" data-title="Jackass: Einer geht noch"
    //   data-img="https://image.tmdb.org/t/p/w500/8LxyuMNFYhmDZNBKvZeZe4RDPun.jpg" ...>
    // Detail pages are served at /movie.php?id=<id> and /series.php?id=<id>, matching
    // the site's own onclick="trackHistoryAndGo(this, 'movie.php?id=63080')" links.
    override fun popularAnimeSelector(): String = "#moviesRef div.fav-data-source, #seriesRef div.fav-data-source"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val id = element.attr("data-id")
        val type = element.attr("data-type")
        setUrlWithoutDomain("/$type.php?id=$id")
        title = element.attr("data-title")
        thumbnail_url = element.attr("data-img")
    }

    // =============================== Latest ===============================
    // There is no more separate paginated "latest" listing (the old
    // "$baseUrl/episodes/page/$page" path 404s); the home page's carousels are the
    // only listing available, so latest reuses the same request/selectors as popular.
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // No more pages to page through; use a selector that can never match anything.
    override fun latestUpdatesNextPageSelector(): String = "nav.non-existent-pagination"

    // ============================ Anime Details ============================
    // KinoKing V2's movie.php and series.php pages don't share a template with each other,
    // let alone with DooPlay's "div.sheader"/"div.sgeneros" markup (grep for either class on
    // either page: zero matches) -- so the inherited animeDetailsParse NPEs on its `!!`
    // selectors the instant a user opens any title. Live-verified markup:
    //   movie.php?id=63080:
    //     <h1 class="text-4xl sm:text-5xl ... text-white">Jackass: Einer geht noch</h1>
    //     <img src="https://image.tmdb.org/t/p/w500/8Lxy...jpg" alt="Poster" class="w-full h-full ...">
    //     <p class="max-w-4xl text-[#EDEDED]/60 leading-relaxed text-sm md:text-base font-medium">
    //       Johnny Knoxville und seine Gang kehren ...</p>
    //   series.php?id=36448:
    //     <h2 class="text-xl md:text-3xl font-black tracking-tight leading-tight md:leading-none
    //       mb-1 truncate text-white">Spider-Noir</h2>
    //     <img src="https://image.tmdb.org/t/p/w500/sTMA...jpg" alt="Poster" class="w-16 md:w-24 ...">
    //     <div class="text-[10px] font-mono text-white/50 uppercase tracking-widest mb-2
    //       font-black">Beschreibung</div>
    //     <p class="text-sm text-white/60 leading-relaxed line-clamp-2 md:line-clamp-3
    //       font-medium">Der Privatdetektiv Ben Reilly ...</p>
    //   Note: series.php also has an unrelated adblock-modal "Zugriff verweigert" <h2> and an
    //   unrelated adblock-modal <p class="... leading-relaxed"> earlier in the DOM, which is why
    //   the title/description selectors below key off the "truncate"/"Beschreibung" markers that
    //   only exist on the real info block, instead of a bare "h2"/"p.leading-relaxed" selector.
    //   Neither page renders any genre/category markup, so genre is intentionally left unset.
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("img[alt=Poster]")?.attr("abs:src")

        if (document.location().contains("/series.php")) {
            title = document.selectFirst("h2.truncate")?.text().orEmpty()
            description = document.selectFirst("div:containsOwn(Beschreibung) + p")?.text()
        } else {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            description = document.selectFirst("p.leading-relaxed")?.text()
        }
    }

    // ============================== Episodes ==============================
    // Little workaround to show season episode names like the original extension
    // TODO: Create a "getEpisodeName(element, seasonName)" function in DooPlay class
    override fun episodeFromElement(element: Element, seasonName: String) = super.episodeFromElement(element, seasonName).apply {
        val substring = name.substringBefore(" -")
        val newString = substring.replace("Season", "Staffel")
            .replace("x", "Folge")
        name = name.replace("$substring -", "$newString :")
    }

    // ============================ Video Links =============================
    // KinoKing V2 has no more "li.dooplay_player_option" / admin-ajax.php doo_player_ajax
    // mechanism at all (grep for it on movie.php/series.php: zero matches). Real playback
    // sources now come from two different places depending on page type:
    //
    // 1) series.php?id=<id>: every episode's real hoster URL is baked into an inline
    //    `allEpisodesData = [...]` JS array, e.g. (series.php?id=36448, live-verified):
    //      {"id":955866,...,"video_links":"https:\/\/voe.sx\/e\/iaybsqslbhbn","link_count":1}
    //    -> grabbed directly with VIDEO_LINKS_REGEX, no extra request needed.
    //
    // 2) movie.php?id=<id>: the default player is server-rendered into an inline <script>:
    //      target.innerHTML = `<iframe id="videoPlayer" src="https://cinesrc.st/embed/movie/1612018?prioritize=true&amp;lastserver=sturm" ...>`;
    //    cinesrc.st has no extractor in this repo, but the page also renders a
    //    "#serverLinksContainer" with mirrors that link back to the SAME page with a
    //    `link=<key>` query param, e.g. `<a href="?id=63080&link=dyn_fpto_3072c1">VOE (LIVE)</a>`.
    //    Fetching that URL re-renders the same inline script with a DIFFERENT embed src.
    //    Live-verified key -> host mappings on movie.php?id=63080:
    //      dyn_fpto_3072c1 -> voe.sx, "63100" -> doodstream.com, group_kk_ares -> streamtape.com,
    //      group_kk_dl -> a direct .mp4 file, group_kk_mixdrop -> mixdrop.ag,
    //      group_kk_dood -> dood.to, group_kk_upstream -> upstream.to,
    //      group_kk_goodstream -> goodstream.uno.
    //    Others (cinesrc_backup, meinecloud_backup/meinecloud.click, backup/vidsync.xyz,
    //    dyn_fpto_c41d1a/vidmatrixa.com, dyn_fpto_8691fc/vidsonic.net, group_kk_drop/
    //    drop.download, group_kk_hades/dropload.io, group_kk_hephaistos/supervideo.cc,
    //    dyn_fpto_c4163c/firestream.to, dyn_fpto_42a856/flyfile.app) have no extractor in
    //    this repo and are silently skipped. The Cloudflare Turnstile shown to browsers on
    //    movie.php is a client-side localStorage gate (see `cf_captcha_solved_time`); it
    //    never blocks a plain GET from reaching these bytes.
    override fun videoListParse(response: Response): List<Video> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        val scriptData = document.select("script").joinToString("\n") { it.data() }

        if ("allEpisodesData" in scriptData) {
            VIDEO_LINKS_REGEX.find(scriptData)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.forEach { embedUrl ->
                    runCatching { resolveEmbedUrl(embedUrl, hosterSelection) }
                        .onSuccess { videos += it }
                }
        }

        extractPlayerSrc(scriptData)
            ?.let { embedUrl ->
                runCatching { resolveEmbedUrl(embedUrl, hosterSelection) }
                    .onSuccess { videos += it }
            }

        if (videos.size < MAX_WANTED_VIDEOS) {
            document.select("#serverLinksContainer a[href]")
                .asSequence()
                .map { it.absUrl("href") }
                .filter { it.isNotBlank() }
                .take(MAX_MIRROR_FETCHES)
                .forEach { mirrorUrl ->
                    if (videos.size >= MAX_WANTED_VIDEOS) return@forEach
                    runCatching {
                        val mirrorScript = probeClient.newCall(GET(mirrorUrl, headers))
                            .execute()
                            .asJsoup()
                            .select("script")
                            .joinToString("\n") { it.data() }
                        extractPlayerSrc(mirrorScript)?.let { resolveEmbedUrl(it, hosterSelection) }
                    }.getOrNull()?.let { videos += it }
                }
        }

        return videos
    }

    /**
     * Extracts the `videoPlayer` iframe `src` rendered into an inline <script>. Guards
     * against series.php's un-substituted `src="${currentPendingStreamUrl}"` template
     * literal (only movie.php server-renders a literal URL here).
     */
    private fun extractPlayerSrc(scriptData: String): String? = VIDEO_PLAYER_SRC_REGEX.find(scriptData)
        ?.groupValues
        ?.get(1)
        ?.replace("&amp;", "&")
        ?.takeIf { it.startsWith("http") }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client, headers) }

    // Short-timeout client for probing "#serverLinksContainer" mirror pages: these are only
    // used to read a re-rendered <script>, so a slow/dead mirror must fail fast instead of
    // eating into the overall video-list timeout budget.
    private val probeClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private fun resolveEmbedUrl(embedUrl: String, hosterSelection: Set<String>): List<Video> = when {
        embedUrl.contains("dood") && hosterSelection.contains("dood") -> {
            val quality = "Doodstream"
            val redirect = !embedUrl.contains("doodstream")
            doodExtractor.videosFromUrl(embedUrl, quality, redirect)
        }

        embedUrl.contains("voe.sx") && hosterSelection.contains("voe") -> {
            voeExtractor.videosFromUrl(embedUrl)
        }

        embedUrl.contains("mixdrop") && hosterSelection.contains("mixdrop") -> {
            mixDropExtractor.videosFromUrl(embedUrl)
        }

        embedUrl.contains("streamtape.com") && hosterSelection.contains("streamtape") -> {
            streamTapeExtractor.videosFromUrl(embedUrl)
        }

        embedUrl.contains("upstream.to") && hosterSelection.contains("upstream") -> {
            upstreamExtractor.videosFromUrl(embedUrl)
        }

        embedUrl.contains("goodstream") && hosterSelection.contains("goodstream") -> {
            goodStreamExtractor.videosFromUrl(embedUrl, "GoodStream")
        }

        embedUrl.contains("filehosted") && hosterSelection.contains("filehosted") -> {
            listOf(Video(embedUrl, "Filehosted", embedUrl))
        }

        DIRECT_FILE_REGEX.containsMatchIn(embedUrl) && hosterSelection.contains("direct") -> {
            listOf(Video(embedUrl, "Direct", embedUrl))
        }

        else -> null
    }.orEmpty()

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
