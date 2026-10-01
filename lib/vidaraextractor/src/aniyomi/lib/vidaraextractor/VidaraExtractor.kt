package aniyomi.lib.vidaraextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Extractor for Vidara embeds (`/e/<filecode>`, `/v/<filecode>`).
 *
 * The page itself posts `{filecode, device}` to `/api/stream` and plays the returned HLS URL.
 */
class VidaraExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = "Vidara - "): List<Video> {
        val httpUrl = url.toHttpUrl()
        val filecode = httpUrl.pathSegments.lastOrNull { it.isNotBlank() } ?: return emptyList()
        val origin = "${httpUrl.scheme}://${httpUrl.host}"

        val apiHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", origin)
            .build()
        val body = """{"filecode":"$filecode","device":"web"}""".toJsonBody()
        val stream = client.newCall(POST("$origin/api/stream", apiHeaders, body)).execute()
            .parseAs<StreamDto>()

        val playlistUrl = stream.streamingUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        val subtitles = stream.subtitles.orEmpty().mapNotNull { sub ->
            val file = sub.file?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(if (file.startsWith("http")) file else "$origin$file", sub.language ?: "Subtitle")
        }

        val videoHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()
        return playlistUtils.extractFromHls(
            playlistUrl,
            referer = "$origin/",
            masterHeaders = videoHeaders,
            videoHeaders = videoHeaders,
            videoNameGen = { prefix + it },
            subtitleList = subtitles,
        )
    }

    @Serializable
    private class StreamDto(
        @SerialName("streaming_url") val streamingUrl: String? = null,
        val subtitles: List<SubtitleDto>? = null,
    )

    @Serializable
    private class SubtitleDto(
        @SerialName("file_path") val file: String? = null,
        val language: String? = null,
    )
}
