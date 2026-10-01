package eu.kanade.tachiyomi.animeextension.es.hackstore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListingDto(
    val items: List<ItemDto> = emptyList(),
    val pagination: PaginationDto? = null,
    val total: Int? = null,
)

@Serializable
class PaginationDto(
    @SerialName("has_next") val hasNext: Boolean = false,
)

@Serializable
class ItemResponseDto(val item: ItemDto)

@Serializable
class GenreDto(val title: String = "")

@Serializable
class ItemDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val kind: String,
    val title: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String? = null,
    val status: String? = null,
    val genres: List<GenreDto> = emptyList(),
    @SerialName("original_title") val originalTitle: String? = null,
)

@Serializable
class SeasonsDto(val seasons: List<SeasonDto> = emptyList())

@Serializable
class SeasonDto(
    val season: Int,
    @SerialName("available_count") val availableCount: Int = 0,
    @SerialName("playable_count") val playableCount: Int = 0,
)

@Serializable
class SeasonDetailDto(val season: SeasonEpisodesDto)

@Serializable
class SeasonEpisodesDto(val episodes: List<EpisodeDto> = emptyList())

@Serializable
class EpisodeDto(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val playable: Boolean = false,
)

@Serializable
class PlaybackDto(val embeds: List<EmbedDto> = emptyList())

@Serializable
class EmbedDto(
    val url: String,
    val server: String? = null,
    val host: String? = null,
    val lang: String? = null,
    val quality: String? = null,
)
