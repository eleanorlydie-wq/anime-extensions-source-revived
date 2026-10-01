package aniyomi.lib.okruextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.commonEmptyHeaders
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    private fun fixQuality(quality: String): String {
        val qualities = listOf(
            Pair("ultra", "2160p"),
            Pair("quad", "1440p"),
            Pair("full", "1080p"),
            Pair("hd", "720p"),
            Pair("sd", "480p"),
            Pair("low", "360p"),
            Pair("lowest", "240p"),
            Pair("mobile", "144p"),
        )
        return qualities.find { it.first == quality }?.second ?: quality
    }

    suspend fun videosFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val videoString = document.selectFirst("div[data-options]")
            ?.attr("data-options")
            ?: return emptyList<Video>()

        parseMetadata(videoString)?.let { metadata ->
            val videos = videosFromMetadata(metadata, prefix, fixQualities)
            if (videos.isNotEmpty()) return videos
        }

        return when {
            "ondemandHls" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandHls")
                playlistUtils.extractFromHls(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }
            "ondemandDash" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandDash")
                playlistUtils.extractFromDash(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }
            else -> videosFromJson(videoString, prefix, fixQualities)
        }
    }

    /**
     * The player config is `{"flashvars":{"metadata": ...}}`, where `metadata` is either an
     * object (current) or a JSON-encoded string (legacy).
     */
    private fun parseMetadata(dataOptions: String): JsonObject? = runCatching {
        val metadata = Json.parseToJsonElement(dataOptions).jsonObject["flashvars"]
            ?.jsonObject?.get("metadata")
        when {
            metadata is JsonObject -> metadata
            metadata is JsonPrimitive && metadata.isString ->
                Json.parseToJsonElement(metadata.content).jsonObject
            else -> null
        }
    }.getOrNull()

    private fun videosFromMetadata(metadata: JsonObject, prefix: String, fixQualities: Boolean): List<Video> {
        fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        metadata["ondemandHls"].str()?.let { hls ->
            return playlistUtils.extractFromHls(hls, videoNameGen = { "Okru:$it".addPrefix(prefix) })
        }
        metadata["hlsManifestUrl"].str()?.let { hls ->
            return playlistUtils.extractFromHls(hls, videoNameGen = { "Okru:$it".addPrefix(prefix) })
        }
        metadata["ondemandDash"].str()?.let { dash ->
            return playlistUtils.extractFromDash(dash, videoNameGen = { "Okru:$it".addPrefix(prefix) })
        }

        return runCatching { metadata["videos"]?.jsonArray }.getOrNull().orEmpty().reversed().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val videoUrl = obj["url"].str()?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val name = obj["name"].str() ?: return@mapNotNull null
            val quality = if (fixQualities) fixQuality(name) else name
            Video(videoUrl, "Okru:$quality".addPrefix(prefix), videoUrl)
        }
    }

    private fun String.addPrefix(prefix: String) = prefix.takeIf(String::isNotBlank)
        ?.let { "$prefix $this" }
        ?: this

    private fun String.extractLink(attr: String) = substringAfter("$attr\\\":\\\"")
        .substringBefore("\\\"")
        .replace("\\\\u0026", "&")

    private fun videosFromJson(videoString: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")

        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull { data ->
            val videoUrl = data.extractLink("url")
            val quality = data.substringBefore("\\\"").let {
                if (fixQualities) fixQuality(it) else it
            }
            val videoQuality = "Okru:$quality".addPrefix(prefix)

            if (videoUrl.startsWith("https://")) {
                Video(videoUrl, videoQuality, videoUrl)
            } else {
                null
            }
        }
    }
}
