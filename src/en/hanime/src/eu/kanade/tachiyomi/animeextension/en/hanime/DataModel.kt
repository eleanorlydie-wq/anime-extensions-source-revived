package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchHvsResponse(
    val data: List<HitsModel> = emptyList(),
)

@Serializable
data class HitsModel(
    val id: Long? = null,
    val name: String = "",
    @SerialName("search_titles")
    val searchTitles: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val views: Long? = null,
    val interests: Long? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val brand: String? = null,
    @SerialName("brand_id")
    val brandId: Long? = null,
    @SerialName("duration_in_ms")
    val durationInMs: Long? = null,
    @SerialName("is_censored")
    val isCensored: Boolean? = false,
    val rating: Long? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val downloads: Long? = null,
    @SerialName("monthly_rank")
    val monthlyRank: Long? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("released_at")
    val releasedAt: String? = null,
    @SerialName("created_at_unix")
    val createdAtUnix: Long? = null,
    @SerialName("released_at_unix")
    val releasedAtUnix: Long? = null,
    val score: String? = null,
)

@Serializable
data class CsrfTokenResponse(
    @SerialName("csrf_token")
    val csrfToken: String,
    @SerialName("csrf_token_expires_at")
    val csrfTokenExpiresAt: Long? = null,
)

@Serializable
data class HandshakeEnvelope(
    val iv: String,
    val tag: String,
    val data: String,
)

@Serializable
data class HandshakeData(
    val sources: List<HandshakeSource> = emptyList(),
)

@Serializable
data class HandshakeSource(
    val src: String = "",
    val height: Int? = null,
    val label: String? = null,
    val kind: String? = null,
)
