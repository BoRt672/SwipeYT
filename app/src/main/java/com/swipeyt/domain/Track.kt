package com.swipeyt.domain

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val name: String,
    val browseId: String? = null,
)

@Serializable
data class Track(
    val videoId: String,
    val title: String,
    val artists: List<Artist>,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Int? = null,
    /** Present only for items read from a playlist; required to remove the item. */
    val setVideoId: String? = null,
) {
    val artistLine: String get() = artists.joinToString(", ") { it.name }.ifBlank { "Unknown artist" }

    /** Stable keys used by the recommender for artist affinity. */
    val artistKeys: List<String>
        get() = artists.map { it.browseId ?: it.name.lowercase().trim() }.filter { it.isNotBlank() }
}

fun String.hiResThumbnail(size: Int = 1080): String =
    replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")
        .replace(Regex("=s\\d+"), "=s$size")
