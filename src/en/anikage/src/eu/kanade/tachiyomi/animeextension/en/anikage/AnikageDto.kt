package eu.kanade.tachiyomi.animeextension.en.anikage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NextAiringEpisode(
    val episode: Int,
    val airingAt: Long,
    val timeUntilAiring: Long,
)

@Serializable
data class CoverImage(
    val medium: String,
    val large: String,
    val extraLarge: String,
)

@Serializable
data class Title(
    val romaji: String,
    val english: String?,
)

@Serializable
data class Result(
    val slug: String,
    @SerialName("anilistId") val aniListId: Int,
    val title: Title,
    val coverImage: CoverImage,
    val type: String? = null,
    val format: String,
    val status: String,
    val totalEpisodes: Int? = null,
    val currentEpisode: Int? = null,
    val averageScore: Int? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val nextAiringEpisode: NextAiringEpisode? = null,

)

// The browse endpoint returns a flat { count, data } page instead of the
// old { page, perPage, total, hasNextPage, results } shape. There is no
// explicit hasNextPage flag anymore; callers infer it from page fullness.
@Serializable
data class AnikageResponse(
    val count: Int = 0,
    val data: List<Result> = emptyList(),
)

// The per-anime episode list endpoint now wraps the array in an object
// instead of returning a bare JSON array, and episodes no longer carry an
// "id"/"updatedAt" (the fields were renamed/dropped server-side).
@Serializable
data class EpisodeListResponse(
    val total: Int? = null,
    val episodes: List<EpisodeResult> = emptyList(),
)

@Serializable
data class EpisodeResult(
    val number: Int,
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val isFiller: Boolean = false,
    val rating: Float? = null,
)

// "headers" is now an object (not a string) and "embeds"/"stale" are no
// longer always present; none of these three are consumed anywhere in this
// source, so they are dropped rather than fought with type-wise.
@Serializable
data class EpisodeSource(
    val sources: List<SourceData> = emptyList(),
    val subtitles: List<SubtitleData> = emptyList(),
    val intro: TimeStamp? = null,
    val outro: TimeStamp? = null,
    val cached: Boolean = false,
)

@Serializable
data class SourceData(
    val url: String,
    val quality: String,
    val isM3U8: Boolean? = null,
    val type: String? = null, // softsub, only present on some providers
) {
    fun episodeSourceUrl(): String = listOfNotNull(
        "https://prox.anikage.cc",
        isM3U8?.let { "m3u8" } ?: "stream",
        url,
    ).joinToString("/")
}

@Serializable
data class SubtitleData(
    val file: String,
    val label: String,
    val kind: String,
    val default: Boolean,
)

@Serializable
data class Embed(
    val url: String,
    val type: String,
    val server: String,
)

@Serializable
data class TimeStamp(
    val start: Int,
    val end: Int,
)
