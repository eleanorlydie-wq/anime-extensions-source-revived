package eu.kanade.tachiyomi.animeextension.tr.animeler.dto

import kotlinx.serialization.Serializable

@Serializable
data class SourceUrlDto(
    val success: Boolean = false,
    val url: String? = null,
)
