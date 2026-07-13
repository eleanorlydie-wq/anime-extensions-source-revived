package eu.kanade.tachiyomi.animeextension.es.animenix

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy

class Animenix :
    AnimeStream(
        "es",
        "Animenix",
        "https://animenix.com",
    ) {

    override val preferences by getPreferencesLazy()

    override val prefQualityDefault = "1080p"
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p")

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = listOf(
            "Voe",
            "VidGuard",
            "YourUpload",
            "Filemoon",
            "StreamWish",
        )
    }

    // ============================ Video Links =============================
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val youruploadExtractor by lazy { YourUploadExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(headers = headers, client = client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override suspend fun getVideoList(url: String, name: String): List<Video> = when {
        url.contains("voe") -> voeExtractor.videosFromUrl(url)
        url.contains("vgfplay") || url.contains("vembed") || url.contains("vidguard") || url.contains("listeamed") ->
            vidGuardExtractor.videosFromUrl(url)
        url.contains("yourupload") -> youruploadExtractor.videoFromUrl(url, headers)
        url.contains("filemoon") -> filemoonExtractor.videosFromUrl(url)
        url.contains("wishembed") || url.contains("cdnwish") || url.contains("flaswish") ||
            url.contains("sfastwish") || url.contains("streamwish") || url.contains("asnwish") ->
            streamWishExtractor.videosFromUrl(url)
        else -> universalExtractor.videosFromUrl(url, headers)
    }

    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.videoSortPref
        val server = preferences.serverPref
        return sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVER_LIST,
            entryValues = SERVER_LIST,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
    }
}
