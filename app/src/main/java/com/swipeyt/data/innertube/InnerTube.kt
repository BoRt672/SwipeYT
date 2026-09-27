package com.swipeyt.data.innertube

import android.util.Log
import com.swipeyt.data.auth.AuthStore
import com.swipeyt.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

class InnerTubeException(message: String, val status: Int? = null) : IOException(message)

data class AccountInfo(val name: String, val photoUrl: String?, val channelHandle: String?)

data class PlaylistRef(val playlistId: String, val title: String, val thumbnailUrl: String?)

data class Page<T>(val items: List<T>, val continuation: String?)

data class StreamInfo(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val userAgent: String,
    val expiresAtMs: Long,
)

/**
 * Minimal client for YouTube Music's private InnerTube API. Everything runs on-device against
 * music.youtube.com using the cookies of the signed-in web session; no third-party servers.
 */
class InnerTube(
    private val http: OkHttpClient,
    private val auth: AuthStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------------------------------------------------------- transport

    private fun context(client: YtClient, withUser: Boolean): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", client.clientName)
            put("clientVersion", client.clientVersion)
            put("hl", "en")
            put("gl", java.util.Locale.getDefault().country.ifBlank { "US" })
            put("userAgent", client.userAgent)
            auth.current.visitorData?.let { put("visitorData", it) }
            client.osName?.let { put("osName", it) }
            client.osVersion?.let { put("osVersion", it) }
            client.deviceMake?.let { put("deviceMake", it) }
            client.deviceModel?.let { put("deviceModel", it) }
            client.androidSdkVersion?.let { put("androidSdkVersion", it) }
            put("utcOffsetMinutes", java.util.TimeZone.getDefault().rawOffset / 60_000)
        }
        putJsonObject("user") {
            put("lockedSafetyMode", false)
            if (withUser) auth.current.dataSyncId?.let { put("onBehalfOfUser", it) }
        }
        putJsonObject("request") {
            put("useSsl", true)
            putJsonArray("internalExperimentFlags") {}
        }
    }

    private fun sapisidHash(cookie: String): String? {
        val sapisid = Regex("(?:^|;\\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)").find(cookie)?.groupValues?.get(1)
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray())
        return "SAPISIDHASH ${ts}_${digest.joinToString("") { "%02x".format(it) }}"
    }

    suspend fun call(
        endpoint: String,
        body: JsonObject,
        client: YtClient = YtClient.WEB_REMIX,
        useAuth: Boolean = client.supportsLogin,
        query: Map<String, String> = emptyMap(),
    ): JsonElement = withContext(Dispatchers.IO) {
        val state = auth.current
        val authed = useAuth && state.isLoggedIn
        val payload = buildJsonObject {
            put("context", context(client, authed))
            body.forEach { (k, v) -> put(k, v) }
        }
        val url = "$API_BASE$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("key", client.apiKey)
            .addQueryParameter("prettyPrint", "false")
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("User-Agent", client.userAgent)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", client.clientId.toString())
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .apply {
                state.visitorData?.let { header("X-Goog-Visitor-Id", it) }
                if (authed) {
                    val cookie = state.cookie!!
                    header("Cookie", cookie)
                    sapisidHash(cookie)?.let { header("Authorization", it) }
                    header("X-Goog-AuthUser", "0")
                }
            }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw InnerTubeException("InnerTube $endpoint failed: HTTP ${response.code}", response.code)
            }
            json.parseToJsonElement(text)
        }
    }

    // ---------------------------------------------------------------- session

    /** Scrapes `ytcfg` from the web app to obtain visitor / account identifiers for the session. */
    suspend fun refreshSessionIds() = withContext(Dispatchers.IO) {
        val cookie = auth.current.cookie
        val request = Request.Builder()
            .url("$ORIGIN/")
            .header("User-Agent", YtClient.WEB_REMIX.userAgent)
            .apply { cookie?.let { header("Cookie", it) } }
            .build()
        val html = http.newCall(request).execute().use { it.body.string() }
        val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val dataSync = Regex("\"DATASYNC_ID\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?.substringBefore("||")?.takeIf { it.isNotBlank() }
        auth.update { it.copy(visitorData = visitor ?: it.visitorData, dataSyncId = dataSync) }
    }

    suspend fun accountInfo(): AccountInfo? {
        val response = call("account/account_menu", buildJsonObject {})
        val header = response.findFirst("activeAccountHeaderRenderer") ?: return null
        val name = header["accountName"].runsText ?: return null
        val photo = header["accountPhoto"]["thumbnails"].array.lastOrNull()?.get("url").string
        val handle = header["channelHandle"].runsText
        return AccountInfo(name, photo, handle)
    }

    // ---------------------------------------------------------------- discovery

    /** Personalised home feed: "Quick picks" songs plus the ids of algorithmic mixes shown there. */
    suspend fun home(): HomeFeed {
        val response = call("browse", buildJsonObject { put("browseId", "FEmusic_home") })
        val songs = response.collectResponsiveTracks()
        val mixes = response.findAll("musicTwoRowItemRenderer").mapNotNull { row ->
            val browseId = row["navigationEndpoint"]["browseEndpoint"]["browseId"].string ?: return@mapNotNull null
            val playlistId = browseId.removePrefix("VL")
            if (!playlistId.startsWith("RD")) return@mapNotNull null
            PlaylistRef(
                playlistId = playlistId,
                title = row["title"].runsText ?: return@mapNotNull null,
                thumbnailUrl = row["thumbnailRenderer"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"].array
                    .lastOrNull()?.get("url").string,
            )
        }.distinctBy { it.playlistId }
        return HomeFeed(songs, mixes)
    }

    data class HomeFeed(val quickPicks: List<Track>, val mixes: List<PlaylistRef>)

    /** Radio seeded by a track (`RDAMVM…`), the same queue YouTube Music builds for "Start radio". */
    suspend fun radio(videoId: String): Page<Track> = queue(videoId = videoId, playlistId = "RDAMVM$videoId")

    /** Any watch queue: a radio, a "My Mix" (`RDTMAK…`) or a regular playlist. */
    suspend fun queue(videoId: String? = null, playlistId: String? = null, params: String? = "wAEB"): Page<Track> {
        val response = call("next", buildJsonObject {
            videoId?.let { put("videoId", it) }
            playlistId?.let { put("playlistId", it) }
            params?.let { put("params", it) }
            put("isAudioOnly", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            put("enablePersistentPlaylistPanel", true)
        })
        return Page(response.collectPanelTracks(), response.findFirst("nextRadioContinuationData")["continuation"].string)
    }

    suspend fun queueContinuation(token: String): Page<Track> {
        val response = call("next", buildJsonObject {
            put("continuation", token)
            put("isAudioOnly", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        })
        return Page(response.collectPanelTracks(), response.findFirst("nextRadioContinuationData")["continuation"].string)
    }

    data class Genre(val name: String, val params: String)

    /** The "Moods & genres" catalogue: chips that expand into curated playlists. */
    suspend fun moodsAndGenres(): List<Genre> {
        val response = call("browse", buildJsonObject { put("browseId", "FEmusic_moods_and_genres") })
        return response.findAll("musicNavigationButtonRenderer").mapNotNull { button ->
            val endpoint = button["clickCommand"]["browseEndpoint"]
            if (endpoint["browseId"].string != "FEmusic_moods_and_genres_category") return@mapNotNull null
            Genre(button["buttonText"].runsText ?: return@mapNotNull null, endpoint["params"].string ?: return@mapNotNull null)
        }.distinctBy { it.params }
    }

    suspend fun genrePlaylists(params: String): List<PlaylistRef> {
        val response = call("browse", buildJsonObject {
            put("browseId", "FEmusic_moods_and_genres_category")
            put("params", params)
        })
        return response.findAll("musicTwoRowItemRenderer").mapNotNull { row ->
            val browseId = row["navigationEndpoint"]["browseEndpoint"]["browseId"].string ?: return@mapNotNull null
            if (!browseId.startsWith("VL")) return@mapNotNull null
            PlaylistRef(
                playlistId = browseId.removePrefix("VL"),
                title = row["title"].runsText ?: return@mapNotNull null,
                thumbnailUrl = row["thumbnailRenderer"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"].array
                    .lastOrNull()?.get("url").string,
            )
        }.distinctBy { it.playlistId }
    }

    suspend fun searchSongs(query: String): List<Track> {
        val response = call("search", buildJsonObject {
            put("query", query)
            put("params", SEARCH_SONGS_PARAMS)
        })
        return response.collectResponsiveTracks()
    }

    // ---------------------------------------------------------------- library / playlists

    suspend fun libraryPlaylists(): List<PlaylistRef> {
        val response = call("browse", buildJsonObject { put("browseId", "FEmusic_liked_playlists") })
        return response.findAll("musicTwoRowItemRenderer").mapNotNull { row ->
            val browseId = row["navigationEndpoint"]["browseEndpoint"]["browseId"].string ?: return@mapNotNull null
            if (!browseId.startsWith("VL")) return@mapNotNull null
            PlaylistRef(
                playlistId = browseId.removePrefix("VL"),
                title = row["title"].runsText ?: return@mapNotNull null,
                thumbnailUrl = row["thumbnailRenderer"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"].array
                    .lastOrNull()?.get("url").string,
            )
        }
    }

    suspend fun createPlaylist(title: String, description: String): String {
        val response = call("playlist/create", buildJsonObject {
            put("title", title)
            put("description", description)
            put("privacyStatus", "PRIVATE")
        })
        return response["playlistId"].string ?: throw InnerTubeException("playlist/create returned no playlistId")
    }

    /** Full contents of a user playlist, following continuations (both legacy and current formats). */
    suspend fun playlistTracks(playlistId: String): List<Track> {
        val tracks = ArrayList<Track>()
        var response = call("browse", buildJsonObject { put("browseId", "VL${playlistId.removePrefix("VL")}") })
        var guard = 0
        while (guard++ < 50) {
            tracks += response.collectResponsiveTracks()
            val legacyToken = response.findFirst("nextContinuationData")["continuation"].string
            val token = response.findFirst("continuationCommand")["token"].string
            response = when {
                legacyToken != null -> call(
                    "browse", buildJsonObject {}, query = mapOf("ctoken" to legacyToken, "continuation" to legacyToken, "type" to "next"),
                )
                token != null -> call("browse", buildJsonObject { put("continuation", token) })
                else -> break
            }
        }
        return tracks.distinctBy { it.setVideoId ?: it.videoId }
    }

    /** Returns the `setVideoId` of the inserted item (needed to remove it later). */
    suspend fun addToPlaylist(playlistId: String, videoId: String): String? {
        val response = call("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                add(buildJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                    put("dedupeOption", "DEDUPE_OPTION_SKIP")
                })
            }
        })
        requireSucceeded(response, "add")
        return response.findFirst("playlistEditVideoAddedResultData")["setVideoId"].string
    }

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String) {
        val response = call("browse/edit_playlist", buildJsonObject {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") {
                add(buildJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("removedVideoId", videoId)
                    put("setVideoId", setVideoId)
                })
            }
        })
        requireSucceeded(response, "remove")
    }

    private fun requireSucceeded(response: JsonElement, op: String) {
        val status = response["status"].string
        if (status != null && status != "STATUS_SUCCEEDED") {
            throw InnerTubeException("Playlist $op failed: $status")
        }
    }

    // ---------------------------------------------------------------- playback

    /**
     * Resolves a direct audio URL, trying clients in order. Clients that require the web signature
     * cipher return `signatureCipher` instead of `url`; those formats are skipped. Each URL is
     * probed with a range request past the first megabyte, because URLs that lack a PO token are
     * served only up to ~1 MB and then 403.
     */
    suspend fun resolveStream(videoId: String): StreamInfo {
        // Without a visitor id the player endpoint answers "sign in to confirm you're not a bot".
        if (auth.current.visitorData == null) runCatching { refreshSessionIds() }
        var lastError: Exception? = null
        for (client in YtClient.STREAM_CLIENTS) {
            try {
                val response = call(
                    "player",
                    buildJsonObject {
                        put("videoId", videoId)
                        put("contentCheckOk", true)
                        put("racyCheckOk", true)
                        putJsonObject("playbackContext") {
                            putJsonObject("contentPlaybackContext") {
                                put("html5Preference", "HTML5_PREF_WANTS")
                            }
                        }
                    },
                    client = client,
                    useAuth = client.supportsLogin,
                )
                val status = response["playabilityStatus"]["status"].string
                if (status != "OK") {
                    lastError = InnerTubeException("$videoId not playable via ${client.clientName}: $status")
                    continue
                }
                val format = response["streamingData"]["adaptiveFormats"].array
                    .filter { it["mimeType"].string?.startsWith("audio/") == true && it["url"].string != null }
                    .maxByOrNull { it["bitrate"].int ?: 0 }
                    ?: run {
                        lastError = InnerTubeException("No direct audio formats via ${client.clientName}")
                        continue
                    }
                val expiresIn = response["streamingData"]["expiresInSeconds"].string?.toLongOrNull() ?: 21_600
                val info = StreamInfo(
                    url = format["url"].string!!,
                    mimeType = format["mimeType"].string!!,
                    bitrate = format["bitrate"].int ?: 0,
                    userAgent = client.userAgent,
                    expiresAtMs = System.currentTimeMillis() + (expiresIn - 60) * 1000,
                )
                val length = format["contentLength"].string?.toLongOrNull() ?: 0L
                if (probe(info, length)) {
                    Log.d(TAG, "$videoId: stream via ${client.clientName} itag=${format["itag"].int}")
                    return info
                }
                lastError = InnerTubeException("Stream URL from ${client.clientName} rejected by CDN")
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: InnerTubeException("No stream for $videoId")
    }

    private suspend fun probe(info: StreamInfo, contentLength: Long): Boolean = withContext(Dispatchers.IO) {
        val offset = if (contentLength > PROBE_OFFSET + 1) PROBE_OFFSET else 0L
        val request = Request.Builder()
            .url(info.url)
            .header("User-Agent", info.userAgent)
            .header("Range", "bytes=$offset-${offset + 1}")
            .build()
        runCatching {
            http.newCall(request).execute().use {
                Log.d(TAG, "probe ${it.request.url.host} -> ${it.code}")
                it.code in 200..299
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "InnerTube"
        const val PROBE_OFFSET = 1_500_000L
        const val ORIGIN = "https://music.youtube.com"
        const val API_BASE = "$ORIGIN/youtubei/v1/"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val SEARCH_SONGS_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    }
}
