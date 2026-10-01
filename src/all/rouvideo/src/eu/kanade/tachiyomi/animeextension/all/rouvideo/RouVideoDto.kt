package eu.kanade.tachiyomi.animeextension.all.rouvideo

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideo.Companion.resolutionDesc
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

internal object RouVideoDto {
    fun List<Video>.toAnimePage(): AnimesPage = AnimesPage(
        map { video -> video.toSAnime() },
        false,
    )

    @Serializable
    data class Video(
        val id: String,
        @SerialName("vid")
        val code: String? = null,
        val name: String,
        val description: String? = null,
        val ref: String? = null,
        val tags: List<String>,
        val createdAt: String, // "2025-01-14T23:18:27.933Z"
        val viewCount: Int,
        val likeCount: Int? = null, // not available in search & relatedVideos
        val duration: Float, // in seconds
        val coverImageUrl: String,
        val nameZh: String? = null,
        val tagZh: List<String>? = null,
        val sources: List<Source>? = null, // not available in details
    ) {
        private val desc = StringBuilder().apply {
            sources?.firstOrNull()?.let { append("${resolutionDesc(it.resolution.toString())}\n") }
            append("Duration: ${formatDuration(duration.toInt())}\n")
            append("View: $viewCount")
            likeCount?.let { append(" - Like: $likeCount") }
            ref?.let { append("\nRef: $it") }
            description?.let { append("\n\n$description") }
        }.toString()

        private val majorCategory = tags.firstOrNull()

        fun toSAnime(): SAnime = SAnime.create().apply {
            url = id
            title = name
            thumbnail_url = coverImageUrl
            artist = majorCategory
            author = majorCategory
            description = desc
            genre = (listOfNotNull(code) + tags).joinToString()
            status = SAnime.COMPLETED
            initialized = true
        }

        fun toEpisode(): SEpisode = SEpisode.create().apply {
            name = id
            url = id
            date_upload = createdAt.toDate()
            episode_number = 1f
        }

        fun getTagList(): Set<Tag> = tags.map { Tag(it, it) }.toSet()
    }

    fun formatDuration(seconds: Int): String {
        val duration = seconds.seconds
        val hours = duration.inWholeHours
        val minutes = duration.inWholeMinutes % 60
        val remainingSeconds = duration.inWholeSeconds % 60

        return "$hours:$minutes:$remainingSeconds"
    }

    @Serializable
    data class Ev(
        val d: String,
        val k: Int,
    ) {
        fun decodeToJson(): String = Base64.decode(d, Base64.DEFAULT)
            .map { ((it.toInt() and 0xFF) - k).toChar() }
            .joinToString("")
    }

    @Serializable
    data class PlayInfo(
        val videoUrl: String,
        val thumbVTTUrl: String? = null,
    )

    /* Not available in details */
    @Serializable
    data class Source(
        val id: String? = null, // not available in relatedVideos
        val videoId: String? = null, // not available in relatedVideos
        val resolution: Int,
        val folder: String? = null, // not available in relatedVideos
    )

    private val DATE_FORMATTER by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun parseDate(value: String): Long = runCatching { DATE_FORMATTER.parse(value.trim())?.time }.getOrNull() ?: 0L

    private fun String.toDate(): Long = parseDate(this)
}
