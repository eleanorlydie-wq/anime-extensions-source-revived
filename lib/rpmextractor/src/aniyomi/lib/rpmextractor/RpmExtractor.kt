package aniyomi.lib.rpmextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractor for the RPM player family (rpmvid, rpmshare, rpmstream, upns.one, ...).
 *
 * `GET /api/v1/video?id=<id>` returns the player config as hex-encoded AES-128-CBC.
 */
class RpmExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    /**
     * @param siteHost host of the page embedding the player, sent as the `r` (referrer) parameter.
     * @param prefix text prepended to each video name.
     */
    fun videosFromUrl(url: String, siteHost: String, prefix: String = "RPM:"): List<Video> {
        val httpUrl = url.toHttpUrl()
        val id = httpUrl.fragment?.takeIf { it.isNotBlank() }
            ?: httpUrl.queryParameter("id")
            ?: return emptyList()
        val origin = "${httpUrl.scheme}://${httpUrl.host}"

        val apiUrl = "$origin/api/v1/video".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("w", "1280")
            .addQueryParameter("h", "720")
            .addQueryParameter("r", siteHost)
            .build()
        // The API rejects non-browser user agents (e.g. "okhttp/x") with "Request is invalid".
        val apiHeaders = headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()
        val encrypted = client.newCall(GET(apiUrl, apiHeaders)).execute().use {
            it.body.string().trim()
        }

        if (!encrypted.matches(HEX_REGEX)) return emptyList()
        val config = decrypt(encrypted).parseAs<ConfigDto>()
        val playlist = config.cfNative?.takeIf { it.isNotBlank() }
            ?: config.source?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val videoHeaders = headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()
        return playlistUtils.extractFromHls(
            playlist,
            referer = "$origin/",
            masterHeaders = videoHeaders,
            videoHeaders = videoHeaders,
            videoNameGen = { prefix + it },
        )
    }

    private fun decrypt(hex: String): String {
        val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY.toByteArray(), "AES"),
            IvParameterSpec(IV.toByteArray()),
        )
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    @Serializable
    private class ConfigDto(
        val source: String? = null,
        val cfNative: String? = null,
    )

    companion object {
        private const val KEY = "kiemtienmua911ca"
        private const val IV = "1234567890oiuytr"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    }
}
