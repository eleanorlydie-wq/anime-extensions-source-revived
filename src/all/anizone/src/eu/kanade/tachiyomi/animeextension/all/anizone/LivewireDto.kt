package eu.kanade.tachiyomi.animeextension.all.anizone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class LivewireDto(
    val components: List<ComponentDto>,
) {
    @Serializable
    class ComponentDto(
        val snapshot: String,
        val effects: EffectsDto,
    ) {
        @Serializable
        class EffectsDto(
            val html: String = "",
            val dispatches: List<DispatchDto> = emptyList(),
        )
    }

    @Serializable
    class DispatchDto(
        val name: String,
        val params: JsonElement? = null,
    )
}

@Serializable
class ItemsPageDto(
    val items: List<ItemDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
class ItemDto(
    val slug: String,
    val url: String,
    val cover: String? = null,
    @SerialName("main_title") val mainTitle: String? = null,
    @SerialName("title_list") val titleList: Map<String, String?>? = null,
    @SerialName("air_date") val airDate: String? = null,
)

@Serializable
class PlayerDto(
    val src: String,
    val subtitles: List<SubtitleDto> = emptyList(),
) {
    @Serializable
    class SubtitleDto(
        val title: String? = null,
        val language: String? = null,
        val file: String,
    )
}
