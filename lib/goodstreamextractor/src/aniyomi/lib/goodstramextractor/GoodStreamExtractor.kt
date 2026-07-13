package aniyomi.lib.goodstramextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class GoodStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, name: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val videos = mutableListOf<Video>()

        // Current player markup (verified live, e.g. goodstream.one/embed-<id>.html):
        //   jwplayer("vplayer").setup({ ... sources: [{file:"https://hls2.<host>/.../master.m3u8?..."}], ... })
        // Note there's no space after "file:" and the value isn't comma-terminated (it's followed
        // by "}]"), and the URL itself can contain uppercase letters/commas (e.g. the
        // "_,l,n,h,.urlset/" segment), so we scope the match to the `sources:` array specifically
        // (avoiding the sibling `tracks:`/subtitle `file:` entries) and only require "not a quote".
        val sourcesRegex = Regex("sources\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        val fileRegex = Regex("file\\s*:\\s*\"([^\"]+)\"")

        doc.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("sources")) {
                sourcesRegex.find(data)?.groupValues?.get(1)?.let { sourcesBlock ->
                    fileRegex.findAll(sourcesBlock).forEach { match ->
                        val link = match.groupValues[1]
                        videos.add(
                            Video(
                                url = link,
                                quality = name,
                                videoUrl = link,
                                headers = headers,
                            ),
                        )
                    }
                }
            }
        }

        return videos
    }
}
