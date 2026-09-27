package com.swipeyt.data.innertube

import com.swipeyt.domain.Artist
import com.swipeyt.domain.Track
import kotlinx.serialization.json.JsonElement

private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
private val SEPARATOR = Regex("^\\s*[•·]\\s*$")
private val TYPE_LABELS = setOf("song", "video", "episode", "single", "album", "ep")

/** Byline text such as `Artist • Album • 3:45` split into its typed parts. */
private data class Byline(val artists: List<Artist>, val album: String?, val durationSec: Int?)

private fun parseByline(runs: List<JsonElement>): Byline {
    val groups = ArrayList<MutableList<JsonElement>>().apply { add(ArrayList()) }
    for (run in runs) {
        val text = run["text"].string ?: continue
        if (SEPARATOR.matches(text)) groups.add(ArrayList()) else groups.last().add(run)
    }
    val nonEmpty = groups.filter { it.isNotEmpty() }

    fun browseId(run: JsonElement) = run["navigationEndpoint"]["browseEndpoint"]["browseId"].string

    val explicitArtists = runs
        .filter { browseId(it)?.startsWith("UC") == true }
        .map { Artist(it["text"].string.orEmpty(), browseId(it)) }

    val album = runs.firstOrNull { browseId(it)?.startsWith("MPRE") == true }?.get("text").string
    val duration = runs.mapNotNull { it["text"].string }.firstOrNull { DURATION.matches(it) }?.let(::parseDuration)

    val artists = if (explicitArtists.isNotEmpty()) {
        explicitArtists
    } else {
        val candidates = nonEmpty.filter { group ->
            val text = group.joinToString("") { it["text"].string.orEmpty() }.trim()
            !DURATION.matches(text) &&
                !text.contains("view", ignoreCase = true) &&
                !text.contains("play", ignoreCase = true) &&
                text.lowercase() !in TYPE_LABELS &&
                group.none { browseId(it)?.startsWith("MPRE") == true }
        }
        candidates.firstOrNull()
            ?.map { Artist(it["text"].string.orEmpty().trim(), browseId(it)) }
            ?.filter { it.name.isNotBlank() && it.name != "&" && it.name != "," }
            .orEmpty()
    }
    return Byline(artists, album, duration)
}

fun parseDuration(text: String): Int? {
    val parts = text.split(":").map { it.trim().toIntOrNull() ?: return null }
    return parts.fold(0) { acc, p -> acc * 60 + p }
}

private fun bestThumbnail(thumbnails: List<JsonElement>): String? =
    thumbnails.maxByOrNull { it["width"].int ?: 0 }?.get("url").string

/** `musicResponsiveListItemRenderer` — list rows used by search, home "Quick picks" and playlists. */
fun parseResponsiveListItem(item: JsonElement): Track? {
    val flex = item["flexColumns"].array.map { it["musicResponsiveListItemFlexColumnRenderer"]["text"] }
    val titleRun = flex.getOrNull(0)["runs"][0]
    val videoId = item["playlistItemData"]["videoId"].string
        ?: titleRun["navigationEndpoint"]["watchEndpoint"]["videoId"].string
        ?: item["overlay"].findFirst("watchEndpoint")["videoId"].string
        ?: return null
    val title = titleRun["text"].string ?: return null

    val bylineRuns = flex.drop(1).flatMap { it["runs"].array }
    val byline = parseByline(bylineRuns)
    val fixedDuration = item["fixedColumns"][0]["musicResponsiveListItemFixedColumnRenderer"]["text"].runsText
        ?.let(::parseDuration)

    return Track(
        videoId = videoId,
        title = title,
        artists = byline.artists,
        album = byline.album,
        thumbnailUrl = bestThumbnail(item["thumbnail"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"].array),
        durationSec = fixedDuration ?: byline.durationSec,
        setVideoId = item["playlistItemData"]["playlistSetVideoId"].string,
    )
}

/** `playlistPanelVideoRenderer` — queue rows returned by the `next` endpoint (radio / mixes). */
fun parsePlaylistPanelVideo(item: JsonElement): Track? {
    val renderer = item["playlistPanelVideoRenderer"]
        ?: item["playlistPanelVideoWrapperRenderer"]["primaryRenderer"]["playlistPanelVideoRenderer"]
        ?: return null
    val videoId = renderer["videoId"].string ?: return null
    val title = renderer["title"].runsText ?: return null
    val byline = parseByline(renderer["longBylineText"]["runs"].array)
    return Track(
        videoId = videoId,
        title = title,
        artists = byline.artists,
        album = byline.album,
        thumbnailUrl = bestThumbnail(renderer["thumbnail"]["thumbnails"].array),
        durationSec = renderer["lengthText"].runsText?.let(::parseDuration) ?: byline.durationSec,
    )
}

fun JsonElement?.collectResponsiveTracks(): List<Track> =
    findAll("musicResponsiveListItemRenderer").mapNotNull(::parseResponsiveListItem).distinctBy { it.videoId }

fun JsonElement?.collectPanelTracks(): List<Track> =
    (findAll("playlistPanelRenderer") + findAll("playlistPanelContinuation"))
        .flatMap { it["contents"].array }
        .mapNotNull(::parsePlaylistPanelVideo)
        .distinctBy { it.videoId }
