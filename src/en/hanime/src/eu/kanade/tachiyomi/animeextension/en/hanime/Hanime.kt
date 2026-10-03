package eu.kanade.tachiyomi.animeextension.en.hanime

import android.text.InputType
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Hanime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "hanime.tv"

    override val baseUrl = "https://hanime.tv"

    /** Default CDN base URL for manifest and search API requests. */
    private val defaultCdnBaseUrl = DEFAULT_CDN_BASE_URL

    /** CDN base URL — uses custom domain if set and valid, otherwise the default. */
    private val cdnBaseUrl: String
        get() = preferences.getString(PREF_CUSTOM_CDN_KEY, PREF_CUSTOM_CDN_DEFAULT)
            ?.takeIf { it.isNotBlank() && it.toHttpUrlOrNull() != null }
            ?: defaultCdnBaseUrl

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        .add("Accept", "application/json")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("content-type", "application/json")
        .add("Origin", "https://hanime.tv")
        .add("Referer", "https://hanime.tv/")
        .add("sec-ch-ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Android\"")

    /** Headers for HTML page requests (details, episode list). */
    private val pageHeaders by lazy {
        headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .removeAll("content-type")
            .build()
    }

    /** Headers for video stream requests (m3u8, segments, AES key). */
    private fun videoHeaders(): Headers = headers.newBuilder()
        .set("Accept", "*/*")
        .removeAll("content-type")
        .build()

    private val preferences by getPreferencesLazy()

    @Volatile
    private var signatureProvider: SignatureProvider? = null

    @Volatile
    private var signatureProviderMode: String? = null
    private val signatureProviderMutex = Mutex()

    private suspend fun ensureSignatureProvider(): SignatureProvider {
        // Fast path: check if provider exists and mode hasn't changed
        val currentProvider = signatureProvider
        val currentMode = preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)!!
        if (currentProvider != null && currentMode == signatureProviderMode) {
            return currentProvider
        }

        // Slow path: acquire lock and re-read preference inside the lock
        return signatureProviderMutex.withLock {
            val mode = preferences.getString(PREF_SIG_PROVIDER_KEY, PREF_SIG_PROVIDER_DEFAULT)!!
            val lockedProvider = signatureProvider
            if (lockedProvider != null && signatureProviderMode == mode) {
                lockedProvider
            } else {
                val existing = signatureProvider
                val newProvider = createSignatureProvider(mode)
                Log.d(TAG, "Signature provider created: ${newProvider.javaClass.simpleName}")
                signatureProvider = newProvider
                signatureProviderMode = mode
                existing?.close()
                newProvider
            }
        }
    }

    private suspend fun createSignatureProvider(mode: String?): SignatureProvider = when (mode) {
        "native" -> NativeSignatureProvider()
        "webview" -> WebViewSignatureProvider()
        "wasm" -> {
            val binary = runCatching {
                withContext(Dispatchers.IO) { HanimeWasmBinary.fetchWasmBinary(client) }
            }.getOrNull()
            if (binary != null) {
                ChicorySignatureProvider(binary)
            } else {
                Log.w(TAG, "WASM binary fetch failed — falling back to WebView provider")
                WebViewSignatureProvider()
            }
        }
        else -> {
            Log.w(TAG, "Unknown signature provider mode '$mode', falling back to WebViewSignatureProvider")
            WebViewSignatureProvider()
        }
    }

    // ── Search API (v10 GET endpoint) ──────────────────────────────────

    /** Cached full search response for pagination and client-side filtering. */
    @Volatile
    private var cachedSearchHits: List<HitsModel>? = null

    /**
     * Tag and brand filter options derived from the live catalogue. Populated on
     * every successful search fetch so the filter sheet always reflects exactly
     * what the site currently offers (and matches it case-correctly), instead of
     * relying solely on a hand-maintained list that goes stale. Falls back to the
     * bundled lists until the first fetch completes.
     */
    @Volatile
    private var dynamicTagNames: List<String> = emptyList()

    @Volatile
    private var dynamicBrandNames: List<String> = emptyList()

    /** Timestamp of when [cachedSearchHits] was last fetched. */
    @Volatile
    private var cachedSearchHitsTimestamp: Long = 0L

    /** Maximum age of cached search hits before refetching (based on preference, default 10 minutes). */
    private val searchHitsTtlMs: Long
        get() = preferences.getString(PREF_CACHE_TTL_KEY, PREF_CACHE_TTL_DEFAULT)
            ?.toLongOrNull()?.times(60 * 1000L)
            ?: (10L * 60 * 1000L)

    /** Mutex to prevent concurrent search cache refreshes. */
    private val searchCacheMutex = Mutex()

    /**
     * Fetch or return cached search results from the v10 search API.
     * The API returns all content in a single response — pagination and
     * filtering are handled client-side.
     */
    private suspend fun fetchSearchHits(): List<HitsModel> {
        val ttlMs = searchHitsTtlMs

        // Fast path: check cache without lock
        val now = System.currentTimeMillis()
        val cached = cachedSearchHits
        if (cached != null && now - cachedSearchHitsTimestamp < ttlMs) {
            return cached
        }

        // Slow path: acquire lock to prevent redundant fetches
        return searchCacheMutex.withLock {
            // Re-check cache after acquiring lock (another thread may have fetched)
            val nowLocked = System.currentTimeMillis()
            val cachedLocked = cachedSearchHits
            if (cachedLocked != null && nowLocked - cachedSearchHitsTimestamp < ttlMs) {
                cachedLocked
            } else {
                val signature = ensureSignatureProvider().getSignature()
                val searchHeaders = headers.newBuilder().apply {
                    SignatureHeaders.build(signature).forEach { (key, value) ->
                        add(key, value)
                    }
                }.build()

                val response = client.newCall(GET("$cdnBaseUrl/api/v11/search_hvs", searchHeaders)).await()
                val result = response.use { resp ->
                    val jsonLine = resp.body.string()
                    if (jsonLine.isEmpty()) {
                        Log.w(TAG, "fetchSearchHits() — search API returned empty body")
                        emptyList()
                    } else {
                        if (jsonLine.trimStart().startsWith("[")) jsonLine.parseAs<List<HitsModel>>() else jsonLine.parseAs<SearchHvsResponse>().data
                    }
                }
                cachedSearchHits = result
                cachedSearchHitsTimestamp = System.currentTimeMillis()
                updateFilterOptions(result)
                result
            }
        }
    }

    /** Rebuilds the dynamic tag/brand filter lists from the freshly fetched catalogue. */
    private fun updateFilterOptions(hits: List<HitsModel>) {
        if (hits.isEmpty()) return
        dynamicTagNames = hits.asSequence()
            .flatMap { it.tags.asSequence() }
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .sortedBy { it.lowercase(Locale.US) }
            .toList()
        dynamicBrandNames = hits.asSequence()
            .mapNotNull { it.brand?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .sortedBy { it.lowercase(Locale.US) }
            .toList()
    }

    // ── Popular Anime ──────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val allHits = fetchSearchHits()
        return paginateHits(allHits, page, orderBy = "likes", ordering = "desc")
    }

    // ── Search Anime ───────────────────────────────────────────────────

    private data class SearchParameters(
        val includedTags: List<String>,
        val blackListedTags: List<String>,
        val brands: List<String>,
        val tagsMode: String,
        val orderBy: String,
        val ordering: String,
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val (includedTags, blackListedTags, brands, tagsMode, orderBy, ordering) = getSearchParameters(filters)
        val allHits = fetchSearchHits()
        return paginateHits(
            hits = allHits,
            page = page,
            query = query,
            includedTags = includedTags,
            blackListedTags = blackListedTags,
            brands = brands,
            tagsMode = tagsMode,
            orderBy = orderBy,
            ordering = ordering,
        )
    }

    // ── Latest Updates ─────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val allHits = fetchSearchHits()
        return paginateHits(allHits, page, orderBy = "created_at_unix", ordering = "desc")
    }

    // ── Hit parsing & pagination ───────────────────────────────────────

    private fun parseHitsToAnimeList(hits: List<HitsModel>): List<SAnime> = hits.groupBy { getTitle(it.name) }.map { (_, items) -> items.first() }.map { item ->
        SAnime.create().apply {
            title = getTitle(item.name)
            thumbnail_url = item.coverUrl
            author = item.brand
            description = item.description?.replace(HTML_TAG_REGEX, "")
            status = SAnime.UNKNOWN
            genre = item.tags.joinToString { it }
            initialized = true
            setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + item.slug)
        }
    }

    private val pageSize = 24

    /**
     * Paginate and sort the full hit list for a given page number.
     * The v10 search API returns all content in one response, so
     * pagination is handled client-side.
     */
    private fun paginateHits(
        hits: List<HitsModel>,
        page: Int,
        query: String = "",
        includedTags: List<String> = emptyList(),
        blackListedTags: List<String> = emptyList(),
        brands: List<String> = emptyList(),
        tagsMode: String = "OR",
        orderBy: String = "likes",
        ordering: String = "desc",
    ): AnimesPage {
        var filtered = hits

        // Apply text search filter
        if (query.isNotEmpty()) {
            val lowerQuery = query.lowercase(Locale.US)
            filtered = filtered.filter { hit ->
                hit.name.lowercase(Locale.US).contains(lowerQuery) ||
                    hit.tags.any { tag -> tag.lowercase(Locale.US).contains(lowerQuery) } ||
                    (hit.brand?.lowercase(Locale.US)?.contains(lowerQuery) == true)
            }
        }

        // Apply tag inclusion filter
        if (includedTags.isNotEmpty()) {
            val lowerTags = includedTags.map { it.lowercase(Locale.US) }
            val isAndMode = tagsMode.equals("and", ignoreCase = true)
            filtered = filtered.filter { hit ->
                if (isAndMode) {
                    lowerTags.all { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
                } else {
                    lowerTags.any { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
                }
            }
        }

        // Apply tag blacklist filter
        if (blackListedTags.isNotEmpty()) {
            val lowerBlacklist = blackListedTags.map { it.lowercase(Locale.US) }
            filtered = filtered.filterNot { hit ->
                lowerBlacklist.any { tag -> hit.tags.any { it.lowercase(Locale.US) == tag } }
            }
        }

        // Censored content filter
        val censoredFilter = preferences.getString(PREF_CENSORED_KEY, PREF_CENSORED_DEFAULT) ?: PREF_CENSORED_DEFAULT
        filtered = when (censoredFilter) {
            "uncensored" -> filtered.filter { it.isCensored != true } // != true includes null/unknown items as potentially uncensored
            "censored" -> filtered.filter { it.isCensored == true }
            else -> filtered
        }

        // Apply brand filter
        if (brands.isNotEmpty()) {
            val lowerBrands = brands.map { it.lowercase(Locale.US) }
            filtered = filtered.filter { hit ->
                hit.brand?.lowercase(Locale.US) in lowerBrands
            }
        }

        // Apply sorting
        val comparator: Comparator<HitsModel> = when (orderBy) {
            "views" -> compareByDescending { it.views ?: 0L }
            "likes" -> compareByDescending { it.likes ?: 0L }
            "created_at_unix", "published_at_unix" -> compareByDescending { it.createdAtUnix ?: 0L }
            "released_at_unix" -> compareByDescending { it.releasedAtUnix ?: 0L }
            "title_sortable" -> compareBy { it.name.lowercase(Locale.US) }
            else -> compareByDescending { it.likes ?: 0L }
        }
        val sorted = if (ordering == "asc") filtered.sortedWith(comparator.reversed()) else filtered.sortedWith(comparator)

        // Paginate
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, sorted.size)
        val pageItems = if (fromIndex < sorted.size) sorted.subList(fromIndex, toIndex) else emptyList()
        val hasNextPage = toIndex < sorted.size

        return AnimesPage(parseHitsToAnimeList(pageItems), hasNextPage)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun getTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.contains(" Ep ")) {
            return trimmed.split(" Ep ")[0].trim()
        }
        // Only strip trailing number if it's a standalone episode number
        // (1-3 digits at the end, preceded by a space)
        val match = EPISODE_SUFFIX_REGEX.find(trimmed)
        return if (match != null) {
            val beforeNumber = trimmed.substring(0, match.range.first)
            // Don't strip if the number is part of "Season N" (the N is a season label, not an episode number)
            if (beforeNumber.trimEnd().endsWith("Season", ignoreCase = true)) {
                trimmed
            }
            // Don't strip if the number is part of a compound like "x 3" or "- 3"
            else if (PREFIX_REGEX.containsMatchIn(beforeNumber)) {
                trimmed
            } else {
                beforeNumber.trim()
            }
        } else {
            trimmed
        }
    }

    private fun formatEpisodeTitle(rawName: String?, seriesName: String, index: Int, format: String): String {
        val fallback = "Episode ${index + 1}"
        if (rawName == null) return fallback
        if (format == "full") return rawName

        val trimmed = rawName.trim()
        // Try "Title Season N" pattern first (e.g. "Modaete yo, Adam-kun Season 1")
        val seasonMatch = SEASON_PATTERN_REGEX.find(trimmed)
        if (seasonMatch != null) {
            val seasonNum = seasonMatch.groupValues[1]
            return "Season $seasonNum - $fallback"
        }
        // Try "Title Ep N" pattern (e.g. "Some Title Ep 3")
        val epMatch = EP_PATTERN_REGEX.find(trimmed)
        if (epMatch != null) {
            return "Episode ${epMatch.groupValues[1]}"
        }
        // Try "Title N" pattern (e.g. "Enjo Kouhai 1")
        val numMatch = TRAILING_NUMBER_REGEX.find(trimmed)
        if (numMatch != null) {
            val beforeNumber = trimmed.substring(0, numMatch.range.first)
            // Only extract if the text before the number matches the series name
            if (beforeNumber.trim().equals(seriesName, ignoreCase = true)) {
                return "Episode ${numMatch.groupValues[1]}"
            }
        }
        // No recognizable pattern — use the raw name
        return trimmed
    }

    // ── Anime Details ──────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, pageHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        return SAnime.create().apply {
            title = getTitle(document.selectFirst("h1")?.text().orEmpty())
            thumbnail_url = document.selectFirst("img[src*=/images/covers/]")?.attr("abs:src")
            author = document.selectFirst("a[href^=/browse/brands/] strong")?.text().orEmpty()
            description = document.select("div[data-expand-content] p").joinToString("\n\n") { it.text() }
            status = SAnime.UNKNOWN
            genre = document.select("a[href^=/browse/tags/]").joinToString { it.text() }
            initialized = true
            setUrlWithoutDomain(document.location())
        }
    }

    // ── Video List ─────────────────────────────────────────────────────

    /**
     * The player obtains its streams from an encrypted handshake: the request
     * token and the `x-token` response header are AES-256-GCM blobs keyed with
     * SHA-256 of a constant baked into the site's JS. The handshake needs the
     * usual signature headers plus a CSRF token from [CSRF_TOKEN_URL].
     */
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val slug = extractSlugFromUrl(episode.url)
        val sources = fetchHandshake(slug).sources
            .filter { it.kind != "promotion" && it.src.isNotBlank() }
        val playerHeaders = videoHeaders()
        return sources.map { source ->
            val url = if (source.src.startsWith("http")) source.src else baseUrl + source.src
            Video(url, source.label ?: "${source.height ?: "unknown"}p", url, headers = playerHeaders)
        }
    }

    private suspend fun fetchHandshake(slug: String): HandshakeData {
        var csrf = getCsrfToken(forceRefresh = false)
        repeat(2) { attempt ->
            val payload = buildJsonObject {
                put("timestamp_unix", System.currentTimeMillis() / 1000L)
                put("directive", "htv_player_handshake")
                put("slug", slug)
            }.toString()
            val body = buildJsonObject { put("token", HandshakeCrypto.encrypt(payload)) }
                .toString().toRequestBody(JSON_MEDIA_TYPE)

            val signature = ensureSignatureProvider().getSignature()
            val requestHeaders = headers.newBuilder().apply {
                SignatureHeaders.build(signature).forEach { (key, value) -> set(key, value) }
                set("x-csrf-token", csrf)
            }.build()

            client.newCall(POST("$AUTHED_API_BASE_URL/api/v11/handshake", requestHeaders, body)).await().use { response ->
                val responseBody = response.body.string()
                if (response.isSuccessful) {
                    val token = response.header("x-token") ?: throw Exception("Handshake response had no stream token")
                    return HandshakeCrypto.decrypt(token).parseAs<HandshakeData>()
                }
                if (attempt == 0 && "CSRF_ERROR" in responseBody) {
                    csrf = getCsrfToken(forceRefresh = true)
                    return@repeat
                }
                throw Exception("Handshake failed: HTTP ${response.code} ${responseBody.take(200)}")
            }
        }
        throw Exception("Handshake failed: CSRF token rejected")
    }

    @Volatile
    private var csrfToken: String? = null

    @Volatile
    private var csrfTokenExpiresAt = 0L

    private suspend fun getCsrfToken(forceRefresh: Boolean): String {
        val cached = csrfToken
        if (!forceRefresh && cached != null && System.currentTimeMillis() / 1000L < csrfTokenExpiresAt - 60) {
            return cached
        }
        val response = client.newCall(GET(CSRF_TOKEN_URL, headers)).await().bodyString().parseAs<CsrfTokenResponse>()
        csrfToken = response.csrfToken
        csrfTokenExpiresAt = response.csrfTokenExpiresAt ?: (System.currentTimeMillis() / 1000L + 600)
        return response.csrfToken
    }

    private object HandshakeCrypto {
        private const val KEY_SEED = "htv-insecure-handshake-v1"
        private const val AAD = "htv-insecure-v1"
        private const val TAG_BYTES = 16

        private val key by lazy {
            SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray()), "AES")
        }
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun encrypt(plaintext: String): String {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
                updateAAD(AAD.toByteArray())
            }
            val sealed = cipher.doFinal(plaintext.toByteArray())
            val envelope = buildJsonObject {
                put("v", 1)
                put("alg", "AES-256-GCM")
                put("iv", encoder.encodeToString(iv))
                put("tag", encoder.encodeToString(sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)))
                put("data", encoder.encodeToString(sealed.copyOfRange(0, sealed.size - TAG_BYTES)))
            }.toString()
            return encoder.encodeToString(envelope.toByteArray())
        }

        fun decrypt(token: String): String {
            val envelope = String(decoder.decode(token)).parseAs<HandshakeEnvelope>()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, decoder.decode(envelope.iv)))
                updateAAD(AAD.toByteArray())
            }
            return String(cipher.doFinal(decoder.decode(envelope.data) + decoder.decode(envelope.tag)))
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { QUALITY_RESOLUTION_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ── Episode List ───────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/videos/hentai/${anime.url.substringAfterLast("/")}", pageHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val currentSlug = response.request.url.pathSegments.last()
        val currentName = document.selectFirst("h1")?.text().orEmpty()
        val currentSeriesName = getTitle(currentName)
        val titleFormat = preferences.getString(PREF_EP_TITLE_FORMAT_KEY, PREF_EP_TITLE_FORMAT_DEFAULT) ?: PREF_EP_TITLE_FORMAT_DEFAULT
        val releaseDates = cachedSearchHits.orEmpty().associate { it.slug to it.releasedAtUnix }

        // The "More from <franchise>" section lists every video of the franchise, which can
        // span several series; keep only the ones belonging to the series being viewed.
        val franchise = document.select("h2")
            .firstOrNull { it.text().startsWith("More from") }
            ?.closest("section")
            ?.select("[data-video-href][data-video-name]")
            .orEmpty()
            .map { FranchiseVideo(it.attr("data-video-href").substringAfterLast("/"), it.attr("data-video-name"), it.attr("data-video-id")) }
            .distinctBy { it.slug }
        val currentId = document.selectFirst("[data-video-slug=$currentSlug][data-video-id]")?.attr("data-video-id").orEmpty()
        val seriesVideos = franchise.filter { getTitle(it.name) == currentSeriesName }
            .ifEmpty { listOf(FranchiseVideo(currentSlug, currentName, currentId)) }

        return seriesVideos.mapIndexed { idx, video ->
            SEpisode.create().apply {
                episode_number = idx + 1f
                name = formatEpisodeTitle(video.name, currentSeriesName, idx, titleFormat)
                date_upload = (releaseDates[video.slug] ?: 0L) * 1000
                // Kept in the pre-2026 API format so existing libraries keep their watch history.
                val hvidParam = video.id.takeIf(String::isNotEmpty)?.let { "&hvid=$it" } ?: ""
                url = "$baseUrl/api/v8/video?id=${video.slug}$hvidParam"
            }
        }.reversed()
    }

    private class FranchiseVideo(val slug: String, val name: String, val id: String)

    // ── URL Helpers ───────────────────────────────────────────────────

    /** Extract the video slug from an episode URL (`…/api/v8/video?id=<slug>&hvid=…` or `…/videos/hentai/<slug>`). */
    private fun extractSlugFromUrl(url: String): String = if ("id=" in url) {
        url.substringAfter("id=").substringBefore("&")
    } else {
        url.substringAfterLast("/").substringBefore("?")
    }

    // ── Filters ────────────────────────────────────────────────────────

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Type to search; separate with , or ;  ·  prefix - to exclude a tag."),
        AnimeFilter.Header("Tag & brand suggestions refresh from the site after the first browse/search."),
        TagFilter(dynamicTagNames.ifEmpty { fallbackTagNames }),
        BrandFilter(dynamicBrandNames.ifEmpty { fallbackBrandNames }),
        SortFilter(sortableList.map { it.first }.toTypedArray()),
        TagInclusionMode(),
    )

    // Autocomplete inputs. Tags support "-" exclusion (filtering is client-side,
    // so exclusion is fully honoured); brands are inclusion-only.
    private class TagFilter(suggestions: List<String>) : AnimeFilter.AutoComplete("Tags", "e.g. milf, vanilla; -netorare", suggestions = suggestions)

    private class BrandFilter(suggestions: List<String>) : AnimeFilter.AutoComplete("Brands", "e.g. pink pineapple, queen bee", suggestions = suggestions)

    private class TagInclusionMode : AnimeFilter.Select<String>("Included tags mode", arrayOf("And", "Or"), 0)

    // Simple holders backing the bundled fallback dictionaries below.
    private class Tag(val id: String, name: String) : AnimeFilter.TriState(name)
    private class Brand(val id: String, name: String) : AnimeFilter.CheckBox(name)

    // Fallback suggestion dictionaries, used until the first catalogue fetch populates the dynamic lists.
    private val fallbackTagNames by lazy { getTags().map { it.id } }
    private val fallbackBrandNames by lazy { getBrands().map { it.id } }

    private fun tokenize(state: String): List<String> = state.split(',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        val includedTags = mutableListOf<String>()
        val blackListedTags = mutableListOf<String>()
        val brands = mutableListOf<String>()
        var tagsMode = "AND"
        var orderBy = "likes"
        var ordering = "desc"
        filters.forEach { filter ->
            when (filter) {
                is TagFilter -> {
                    tokenize(filter.state).forEach { token ->
                        if (token.startsWith("-")) {
                            token.drop(1).trim().takeIf { it.isNotEmpty() }
                                ?.let { blackListedTags.add(it.lowercase(Locale.US)) }
                        } else {
                            includedTags.add(token.lowercase(Locale.US))
                        }
                    }
                }

                is TagInclusionMode -> {
                    tagsMode = filter.values[filter.state].uppercase(Locale.US)
                }

                is SortFilter -> {
                    if (filter.state != null) {
                        val query = sortableList[filter.state!!.index].second
                        val value = when (filter.state!!.ascending) {
                            true -> "asc"
                            false -> "desc"
                        }
                        ordering = value
                        orderBy = query
                    }
                }

                is BrandFilter -> {
                    tokenize(filter.state).forEach { token ->
                        token.removePrefix("-").trim().takeIf { it.isNotEmpty() }
                            ?.let { brands.add(it.lowercase(Locale.US)) }
                    }
                }

                else -> {}
            }
        }
        return SearchParameters(includedTags.toList(), blackListedTags.toList(), brands.toList(), tagsMode, orderBy, ordering)
    }

    private fun getBrands() = listOf(
        Brand("37c-Binetsu", "37c-binetsu"),
        Brand("Adult Source Media", "adult-source-media"),
        Brand("Ajia-Do", "ajia-do"),
        Brand("Almond Collective", "almond-collective"),
        Brand("Alpha Polis", "alpha-polis"),
        Brand("Ameliatie", "ameliatie"),
        Brand("Amour", "amour"),
        Brand("Animac", "animac"),
        Brand("Antechinus", "antechinus"),
        Brand("APPP", "appp"),
        Brand("Arms", "arms"),
        Brand("Bishop", "bishop"),
        Brand("Blue Eyes", "blue-eyes"),
        Brand("BOMB! CUTE! BOMB!", "bomb-cute-bomb"),
        Brand("Bootleg", "bootleg"),
        Brand("BreakBottle", "breakbottle"),
        Brand("BugBug", "bugbug"),
        Brand("Bunnywalker", "bunnywalker"),
        Brand("Celeb", "celeb"),
        Brand("Central Park Media", "central-park-media"),
        Brand("ChiChinoya", "chichinoya"),
        Brand("Chocolat", "chocolat"),
        Brand("ChuChu", "chuchu"),
        Brand("Circle Tribute", "circle-tribute"),
        Brand("CoCoans", "cocoans"),
        Brand("Collaboration Works", "collaboration-works"),
        Brand("Comet", "comet"),
        Brand("Comic Media", "comic-media"),
        Brand("Cosmos", "cosmos"),
        Brand("Cranberry", "cranberry"),
        Brand("Crimson", "crimson"),
        Brand("D3", "d3"),
        Brand("Daiei", "daiei"),
        Brand("demodemon", "demodemon"),
        Brand("Digital Works", "digital-works"),
        Brand("Discovery", "discovery"),
        Brand("Dollhouse", "dollhouse"),
        Brand("EBIMARU-DO", "ebimaru-do"),
        Brand("Echo", "echo"),
        Brand("ECOLONUN", "ecolonun"),
        Brand("Edge", "edge"),
        Brand("Erozuki", "erozuki"),
        Brand("evee", "evee"),
        Brand("FINAL FUCK 7", "final-fuck-7"),
        Brand("Five Ways", "five-ways"),
        Brand("Friends Media Station", "friends-media-station"),
        Brand("Front Line", "front-line"),
        Brand("fruit", "fruit"),
        Brand("Godoy", "godoy"),
        Brand("GOLD BEAR", "gold-bear"),
        Brand("gomasioken", "gomasioken"),
        Brand("Green Bunny", "green-bunny"),
        Brand("Groover", "groover"),
        Brand("Hoods Entertainment", "hoods-entertainment"),
        Brand("Hot Bear", "hot-bear"),
        Brand("Hykobo", "hykobo"),
        Brand("IRONBELL", "ironbell"),
        Brand("Ivory Tower", "ivory-tower"),
        Brand("J.C.", "j-c"),
        Brand("Jellyfish", "jellyfish"),
        Brand("Jewel", "jewel"),
        Brand("Jumondo", "jumondo"),
        Brand("kate_sai", "kate_sai"),
        Brand("KENZsoft", "kenzsoft"),
        Brand("King Bee", "king-bee"),
        Brand("Kitty Media", "kitty-media"),
        Brand("Knack", "knack"),
        Brand("Kuril", "kuril"),
        Brand("L.", "l"),
        Brand("Lemon Heart", "lemon-heart"),
        Brand("Lilix", "lilix"),
        Brand("Lune Pictures", "lune-pictures"),
        Brand("Magic Bus", "magic-bus"),
        Brand("Magin Label", "magin-label"),
        Brand("Majin Petit", "majin-petit"),
        Brand("Marigold", "marigold"),
        Brand("Mary Jane", "mary-jane"),
        Brand("MediaBank", "mediabank"),
        Brand("Media Blasters", "media-blasters"),
        Brand("Metro Notes", "metro-notes"),
        Brand("Milky", "milky"),
        Brand("MiMiA Cute", "mimia-cute"),
        Brand("Moon Rock", "moon-rock"),
        Brand("Moonstone Cherry", "moonstone-cherry"),
        Brand("Mousou Senka", "mousou-senka"),
        Brand("MS Pictures", "ms-pictures"),
        Brand("Muse", "muse"),
        Brand("N43", "n43"),
        Brand("Nihikime no Dozeu", "nihikime-no-dozeu"),
        Brand("Nikkatsu Video", "nikkatsu-video"),
        Brand("nur", "nur"),
        Brand("NuTech Digital", "nutech-digital"),
        Brand("Obtain Future", "obtain-future"),
        Brand("Otodeli", "otodeli"),
        Brand("@ OZ", "oz"),
        Brand("Pashmina", "pashmina"),
        Brand("Passione", "passione"),
        Brand("Peach Pie", "peach-pie"),
        Brand("Pinkbell", "pinkbell"),
        Brand("Pink Pineapple", "pink-pineapple"),
        Brand("Pix", "pix"),
        Brand("Pixy Soft", "pixy-soft"),
        Brand("Pocomo Premium", "pocomo-premium"),
        Brand("PoRO", "poro"),
        Brand("Project No.9", "project-no-9"),
        Brand("Pumpkin Pie", "pumpkin-pie"),
        Brand("Queen Bee", "queen-bee"),
        Brand("Rabbit Gate", "rabbit-gate"),
        Brand("sakamotoJ", "sakamotoj"),
        Brand("Sakura Purin", "sakura-purin"),
        Brand("SANDWICHWORKS", "sandwichworks"),
        Brand("Schoolzone", "schoolzone"),
        Brand("seismic", "seismic"),
        Brand("SELFISH", "selfish"),
        Brand("Seven", "seven"),
        Brand("Shadow Prod. Co.", "shadow-prod-co"),
        Brand("Shelf", "shelf"),
        Brand("Shinyusha", "shinyusha"),
        Brand("ShoSai", "shosai"),
        Brand("Showten", "showten"),
        Brand("SoftCell", "softcell"),
        Brand("Soft on Demand", "soft-on-demand"),
        Brand("SPEED", "speed"),
        Brand("STARGATE3D", "stargate3d"),
        Brand("Studio 9 Maiami", "studio-9-maiami"),
        Brand("Studio Akai Shohosen", "studio-akai-shohosen"),
        Brand("Studio Deen", "studio-deen"),
        Brand("Studio Fantasia", "studio-fantasia"),
        Brand("Studio FOW", "studio-fow"),
        Brand("studio GGB", "studio-ggb"),
        Brand("Studio Houkiboshi", "studio-houkiboshi"),
        Brand("Studio Zealot", "studio-zealot"),
        Brand("Suiseisha", "suiseisha"),
        Brand("Suzuki Mirano", "suzuki-mirano"),
        Brand("SYLD", "syld"),
        Brand("TDK Core", "tdk-core"),
        Brand("t japan", "t-japan"),
        Brand("TNK", "tnk"),
        Brand("TOHO", "toho"),
        Brand("Toranoana", "toranoana"),
        Brand("T-Rex", "t-rex"),
        Brand("Triangle", "triangle"),
        Brand("Trimax", "trimax"),
        Brand("TYS Work", "tys-work"),
        Brand("U-Jin", "u-jin"),
        Brand("Umemaro-3D", "umemaro-3d"),
        Brand("Union Cho", "union-cho"),
        Brand("Valkyria", "valkyria"),
        Brand("Vanilla", "vanilla"),
        Brand("White Bear", "white-bear"),
        Brand("X City", "x-city"),
        Brand("yosino", "yosino"),
        Brand("Y.O.U.C.", "y-o-u-c"),
        Brand("ZIZ", "ziz"),
    )

    private fun getTags() = listOf(
        Tag("3D", "3D"),
        Tag("AHEGAO", "AHEGAO"),
        Tag("ANAL", "ANAL"),
        Tag("BDSM", "BDSM"),
        Tag("BIG BOOBS", "BIG BOOBS"),
        Tag("BLOW JOB", "BLOW JOB"),
        Tag("BONDAGE", "BONDAGE"),
        Tag("BOOB JOB", "BOOB JOB"),
        Tag("CENSORED", "CENSORED"),
        Tag("COMEDY", "COMEDY"),
        Tag("COSPLAY", "COSPLAY"),
        Tag("CREAMPIE", "CREAMPIE"),
        Tag("DARK SKIN", "DARK SKIN"),
        Tag("FACIAL", "FACIAL"),
        Tag("FANTASY", "FANTASY"),
        Tag("FILMED", "FILMED"),
        Tag("FOOT JOB", "FOOT JOB"),
        Tag("FUTANARI", "FUTANARI"),
        Tag("GANGBANG", "GANGBANG"),
        Tag("GLASSES", "GLASSES"),
        Tag("HAND JOB", "HAND JOB"),
        Tag("HAREM", "HAREM"),
        Tag("HD", "HD"),
        Tag("HORROR", "HORROR"),
        Tag("INCEST", "INCEST"),
        Tag("INFLATION", "INFLATION"),
        Tag("LACTATION", "LACTATION"),
        Tag("LOLI", "LOLI"),
        Tag("MAID", "MAID"),
        Tag("MASTURBATION", "MASTURBATION"),
        Tag("MILF", "MILF"),
        Tag("MIND BREAK", "MIND BREAK"),
        Tag("MIND CONTROL", "MIND CONTROL"),
        Tag("MONSTER", "MONSTER"),
        Tag("NEKOMIMI", "NEKOMIMI"),
        Tag("NTR", "NTR"),
        Tag("NURSE", "NURSE"),
        Tag("ORGY", "ORGY"),
        Tag("PLOT", "PLOT"),
        Tag("POV", "POV"),
        Tag("PREGNANT", "PREGNANT"),
        Tag("PUBLIC SEX", "PUBLIC SEX"),
        Tag("RAPE", "RAPE"),
        Tag("REVERSE RAPE", "REVERSE RAPE"),
        Tag("RIMJOB", "RIMJOB"),
        Tag("SCAT", "SCAT"),
        Tag("SCHOOL GIRL", "SCHOOL GIRL"),
        Tag("SHOTA", "SHOTA"),
        Tag("SOFTCORE", "SOFTCORE"),
        Tag("SWIMSUIT", "SWIMSUIT"),
        Tag("TEACHER", "TEACHER"),
        Tag("TENTACLE", "TENTACLE"),
        Tag("THREESOME", "THREESOME"),
        Tag("TOYS", "TOYS"),
        Tag("TRAP", "TRAP"),
        Tag("TSUNDERE", "TSUNDERE"),
        Tag("UGLY BASTARD", "UGLY BASTARD"),
        Tag("UNCENSORED", "UNCENSORED"),
        Tag("VANILLA", "VANILLA"),
        Tag("VIRGIN", "VIRGIN"),
        Tag("WATERSPORTS", "WATERSPORTS"),
        Tag("X-RAY", "X-RAY"),
        Tag("YAOI", "YAOI"),
        Tag("YURI", "YURI"),
    )

    private val sortableList = listOf(
        Pair("Uploads", "created_at_unix"),
        Pair("Views", "views"),
        Pair("Likes", "likes"),
        Pair("Release", "released_at_unix"),
        Pair("Alphabetical", "title_sortable"),
    )

    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Sort", sortables, Selection(2, false))

    // ── Preferences ────────────────────────────────────────────────────

    companion object {
        private const val TAG = "Hanime"
        private const val DEFAULT_CDN_BASE_URL = "https://guest.freeanimehentai.net"
        private const val AUTHED_API_BASE_URL = "https://auth.hanime.tv"
        private const val CSRF_TOKEN_URL = "https://ct.hanime.tv/csrf-token"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SIG_PROVIDER_KEY = "signature_provider"
        private const val PREF_SIG_PROVIDER_DEFAULT = "native"
        private val SIG_PROVIDER_LIST = arrayOf("native", "webview", "wasm")

        private const val PREF_CENSORED_KEY = "censored_filter"
        private const val PREF_CENSORED_DEFAULT = "all"
        private val CENSORED_LIST = arrayOf("all", "uncensored", "censored")

        private const val PREF_CACHE_TTL_KEY = "cache_duration"
        private const val PREF_CACHE_TTL_DEFAULT = "10"
        private val CACHE_TTL_LIST = arrayOf("1", "5", "10", "30")

        private const val PREF_CUSTOM_CDN_KEY = "custom_cdn"
        private const val PREF_CUSTOM_CDN_DEFAULT = ""

        private const val PREF_EP_TITLE_FORMAT_KEY = "episode_title_format"
        private val EP_TITLE_FORMAT_ENTRIES = listOf("Clean (Episode N)", "Full (Series Name N)")
        private val EP_TITLE_FORMAT_LIST = listOf("clean", "full")
        private val PREF_EP_TITLE_FORMAT_DEFAULT = EP_TITLE_FORMAT_LIST.first()

        // Hoisted Regex constants — compiled once, reused on every call

        /** Matches HTML tags for description sanitization. */
        private val HTML_TAG_REGEX by lazy { Regex("<[^>]*>") }

        /** Matches a trailing episode suffix: a space followed by 1–3 digits at end of string. */
        private val EPISODE_SUFFIX_REGEX by lazy { Regex("""\s(\d{1,3})$""") }

        /** Matches "Season N" at end of a title (case-insensitive). */
        private val SEASON_PATTERN_REGEX by lazy { Regex("""\s+Season\s+(\d{1,3})$""", RegexOption.IGNORE_CASE) }

        /** Matches "Ep N" at end of a title (case-insensitive). */
        private val EP_PATTERN_REGEX by lazy { Regex("""\s+Ep\s+(\d{1,3})$""", RegexOption.IGNORE_CASE) }

        /** Matches a trailing number at end of a title: one or more spaces then 1–3 digits. */
        private val TRAILING_NUMBER_REGEX by lazy { Regex("""\s+(\d{1,3})$""") }

        /** Matches compound prefixes before a number (e.g. "x 3", "- 3", "× 3") that should NOT be stripped. */
        private val PREFIX_REGEX by lazy { Regex("""[\s\-×x]$""", RegexOption.IGNORE_CASE) }

        /** Extracts numeric quality value from a quality label like "1080p". */
        private val QUALITY_RESOLUTION_REGEX by lazy { Regex("""(\d+)p""") }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Preferred Quality
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITY_LIST.toList(),
            entryValues = QUALITY_LIST.toList(),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        // Signature Provider
        screen.addListPreference(
            key = PREF_SIG_PROVIDER_KEY,
            title = "Signature provider",
            entries = listOf("Direct SHA-256 computation (Recommended)", "WebView", "Chicory WASM Runtime (Experimental)"),
            entryValues = SIG_PROVIDER_LIST.toList(),
            default = PREF_SIG_PROVIDER_DEFAULT,
            summary = "%s",
        ) { _ ->
            signatureProvider?.close()
            signatureProvider = null
            signatureProviderMode = null
        }

        // Censored Content Filter
        screen.addListPreference(
            key = PREF_CENSORED_KEY,
            title = "Censored content filter",
            entries = listOf("Show All", "Uncensored Only", "Censored Only"),
            entryValues = CENSORED_LIST.toList(),
            default = PREF_CENSORED_DEFAULT,
            summary = "%s",
        )

        // Search Cache Duration
        screen.addListPreference(
            key = PREF_CACHE_TTL_KEY,
            title = "Search cache duration",
            entries = listOf("1 minute", "5 minutes", "10 minutes", "30 minutes"),
            entryValues = CACHE_TTL_LIST.toList(),
            default = PREF_CACHE_TTL_DEFAULT,
            summary = "%s",
        )

        // Custom CDN Domain
        screen.addEditTextPreference(
            key = PREF_CUSTOM_CDN_KEY,
            default = PREF_CUSTOM_CDN_DEFAULT,
            title = "Custom CDN domain",
            summary = "Leave empty for default: $DEFAULT_CDN_BASE_URL",
            dialogMessage = "Enter custom CDN domain URL (leave empty for default: $DEFAULT_CDN_BASE_URL)",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || it.toHttpUrlOrNull() != null },
            validationMessage = { "Must be a valid HTTP/HTTPS URL or empty" },
        )

        // Episode Title Format
        screen.addListPreference(
            key = PREF_EP_TITLE_FORMAT_KEY,
            title = "Episode title format",
            entries = EP_TITLE_FORMAT_ENTRIES,
            entryValues = EP_TITLE_FORMAT_LIST,
            default = PREF_EP_TITLE_FORMAT_DEFAULT,
            summary = "%s",
        )
    }
}
