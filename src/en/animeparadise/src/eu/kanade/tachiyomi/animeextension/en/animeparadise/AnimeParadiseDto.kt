package eu.kanade.tachiyomi.animeextension.en.animeparadise

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AnimeListResponse(
    val data: List<AnimeObject>,
    val pagination: Pagination? = null,
) {
    @Serializable
    data class Pagination(
        val hasNext: Boolean = false,
    )

    @Serializable
    data class AnimeObject(
        @SerialName("_id")
        val id: String,
        val title: String,
        val link: String,
        val posterImage: ImageObject,
    ) {
        @Serializable
        data class ImageObject(
            val original: String? = null,
            val large: String? = null,
            val medium: String? = null,
            val small: String? = null,
        )

        fun toSAnime(json: Json): SAnime = SAnime.create().apply {
            title = this@AnimeObject.title
            thumbnail_url = posterImage.original ?: posterImage.large ?: posterImage.medium ?: posterImage.small ?: ""
            url = json.encodeToString(LinkData(slug = link, id = id))
        }
    }
}

@Serializable
data class LinkData(
    val slug: String,
    val id: String,
)

@Serializable
data class AnimeDetails(
    val data: DataObject,
) {
    @Serializable
    data class DataObject(
        val synopsys: String? = null,
        val genres: List<String>? = null,
        val type: String? = null,
        val animeSeason: Season? = null,
        val alternativeTitle: AlternativeTitle? = null,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            description = buildString {
                synopsys?.let { append(it) }
                listOfNotNull(
                    type?.let { "Type: $it" },
                    animeSeason?.takeIf { it.season != null || it.year != null }
                        ?.let { "Season: ${listOfNotNull(it.season, it.year).joinToString(" ")}" },
                    alternativeTitle?.english?.let { "English: $it" },
                    alternativeTitle?.native?.let { "Native: $it" },
                ).takeIf { it.isNotEmpty() }?.let { append("\n\n", it.joinToString("\n")) }
            }
            genre = genres?.joinToString(", ")
        }
    }

    @Serializable
    data class Season(
        val season: String? = null,
        val year: Int? = null,
    )

    @Serializable
    data class AlternativeTitle(
        val english: String? = null,
        val native: String? = null,
    )
}

@Serializable
data class EpisodeListResponse(
    val data: List<EpisodeObject>,
) {
    @Serializable
    data class EpisodeObject(
        val uid: String,
        val origin: String,
        val number: String? = null,
        val title: String? = null,
    ) {
        fun toSEpisode(): SEpisode = SEpisode.create().apply {
            episode_number = number?.toFloatOrNull() ?: 1F
            name = (number?.let { "Ep. $number" } ?: "Episode") + (title?.let { " - $it" } ?: "")
            url = "/watch/$uid?origin=$origin"
        }
    }
}

@Serializable
data class EpisodeActionResult(
    val episode: EpisodeData,
) {
    @Serializable
    data class EpisodeData(
        val streamLink: String? = null,
        val subData: List<Subtitle>? = null,
    )

    @Serializable
    data class Subtitle(
        val src: String,
        val label: String,
        val type: String? = null,
    )
}
