package com.swipeyt.domain

import com.swipeyt.data.innertube.InnerTube
import com.swipeyt.data.local.Swipe
import com.swipeyt.data.local.UserStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * On-device recommendation engine.
 *
 * Candidate generation leans on YouTube Music's own collaborative filtering (personalised home
 * feed, "My Mix" playlists and per-track radios seeded from what the user liked here), plus genre
 * playlists chosen at onboarding for cold start. Ranking is a local Thompson-sampling bandit over
 * artist affinity with diversity and novelty terms, so the queue adapts after every swipe without
 * any server-side state.
 */
class Recommender(
    private val yt: InnerTube,
    private val store: UserStore,
    private val random: Random = Random.Default,
) {
    enum class Source(val weight: Double) {
        HOME_PICKS(1.0),
        HOME_MIX(1.1),
        RADIO_LIKED(1.3),
        RADIO_ARTIST(1.2),
        GENRE(0.85),
        ARTIST_SEED(1.0),
    }

    data class Candidate(val track: Track, val source: Source)

    private data class ArtistStat(var likes: Double = 0.0, var dislikes: Double = 0.0) {
        val n: Double get() = likes + dislikes
    }

    private val mutex = Mutex()
    private val pool = LinkedHashMap<String, Candidate>()
    private val shownThisSession = HashSet<String>()
    private val recentArtists = ArrayDeque<String>()
    private val usedRadioSeeds = HashMap<String, String?>() // seed videoId -> continuation token
    private val usedPlaylists = HashSet<String>()
    private var homeFetchedAt = 0L

    val poolSize: Int get() = pool.size

    /** Pops the best-ranked candidate, refilling from the network when the pool runs low. */
    suspend fun next(): Candidate? = mutex.withLock {
        if (pool.size < MIN_POOL) {
            try {
                refill()
            } catch (e: IOException) {
                if (pool.isEmpty()) throw e
            }
        }
        pruneSwiped()
        val stats = artistStats()
        val exploration = store.current.settings.exploration.toDouble()
        val best = pool.values.maxByOrNull { score(it, stats, exploration) }
        if (best != null) {
            pool.remove(best.track.videoId)
            shownThisSession += best.track.videoId
            best.track.artistKeys.forEach { recentArtists.addLast(it) }
            while (recentArtists.size > RECENT_ARTIST_WINDOW) recentArtists.removeFirst()
        }
        best
    }

    /** Prefetch so the first card appears instantly and the deck never stalls. */
    suspend fun warmUp() = mutex.withLock { if (pool.size < MIN_POOL) refill() }

    /** Re-evaluates the pool for a track the user put back (undo). */
    suspend fun restore(candidate: Candidate) = mutex.withLock {
        shownThisSession.remove(candidate.track.videoId)
        pool[candidate.track.videoId] = candidate
    }

    // ---------------------------------------------------------------- ranking

    private fun score(c: Candidate, stats: Map<String, ArtistStat>, exploration: Double): Double {
        var s = c.source.weight

        val keys = c.track.artistKeys
        val known = keys.mapNotNull { stats[it] }
        val affinity = if (known.isEmpty()) {
            // Unknown artist: uniform prior, rewarded by the novelty term below.
            sampleBeta(1.0, 1.0)
        } else {
            known.map { sampleBeta(1.0 + it.likes, 1.0 + it.dislikes) }.average()
        }
        s += (affinity - 0.5) * 2.4

        if (known.isEmpty()) s += 0.35 + 0.7 * exploration
        else s += 0.4 * exploration * (1.0 / sqrt(1.0 + known.sumOf { it.n }))

        val recentHits = keys.count { it in recentArtists }
        if (recentHits > 0) {
            val lastFew = recentArtists.takeLast(3)
            s -= if (keys.any { it in lastFew }) 0.9 else 0.35
        }

        val strongDislike = known.any { it.n >= 3 && it.likes / it.n < 0.25 }
        if (strongDislike) s -= 1.2 * (1.0 - exploration)

        s += random.nextDouble() * 0.15
        return s
    }

    private fun artistStats(): Map<String, ArtistStat> {
        val previewMs = store.current.settings.previewSeconds * 1000.0
        val stats = HashMap<String, ArtistStat>()
        val now = System.currentTimeMillis()
        for (swipe in store.current.swipes) {
            val listened = min(1.0, swipe.listenedMs / max(previewMs, 1.0))
            // Recent swipes count more, decaying with a ~60 day half-life.
            val recency = 0.5.pow((now - swipe.timestamp) / (60.0 * 24 * 3600 * 1000))
            val weight = recency * if (swipe.liked) 1.0 + 0.5 * listened else 1.0 + 0.5 * (1.0 - listened)
            for (key in swipe.track.artistKeys) {
                val st = stats.getOrPut(key) { ArtistStat() }
                if (swipe.liked) st.likes += weight else st.dislikes += weight
            }
        }
        return stats
    }

    // ---------------------------------------------------------------- candidate generation

    private suspend fun refill() {
        val swipes = store.current.swipes
        val likes = swipes.filter { it.liked }
        val settings = store.current.settings
        val attempts = ArrayList<suspend () -> Unit>()

        if (likes.isEmpty()) {
            attempts += { fetchHome() }
            attempts += { fetchGenre() }
            attempts += { fetchArtistSeed() }
        } else {
            val roll = random.nextDouble()
            when {
                roll < 0.45 -> attempts += { fetchRadioFromLike(likes, Source.RADIO_LIKED) }
                roll < 0.65 -> attempts += { fetchRadioFromTopArtist(likes) }
                roll < 0.85 -> attempts += { fetchHome() }
                else -> attempts += { if (settings.seedGenres.isNotEmpty()) fetchGenre() else fetchArtistSeed() }
            }
            attempts += { fetchRadioFromLike(likes, Source.RADIO_LIKED) }
            attempts += { fetchHome() }
        }

        var lastError: IOException? = null
        for (attempt in attempts) {
            if (pool.size >= TARGET_POOL) break
            try {
                attempt()
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (pool.isEmpty()) throw lastError ?: IOException("Could not load recommendations")
    }

    private fun offer(tracks: List<Track>, source: Source) {
        val swipes = store.current.swipes
        val swiped = swipes.mapTo(HashSet()) { it.track.videoId }
        val seenVersions = HashSet<String>()
        pool.values.forEach { seenVersions += versionKey(it.track) }
        swipes.forEach { seenVersions += versionKey(it.track) }
        for (t in tracks) {
            if (t.videoId in swiped || t.videoId in shownThisSession || t.videoId in pool) continue
            if (t.thumbnailUrl == null) continue
            if (!seenVersions.add(versionKey(t))) continue
            pool[t.videoId] = Candidate(t, source)
        }
    }

    private fun versionKey(t: Track): String =
        t.title.lowercase().replace(Regex("[\\(\\[].*?[\\)\\]]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() +
            "|" + (t.artistKeys.firstOrNull() ?: "")

    private fun pruneSwiped() {
        val swiped = store.current.swipes.mapTo(HashSet()) { it.track.videoId }
        pool.keys.retainAll { it !in swiped }
    }

    private suspend fun fetchHome() {
        val now = System.currentTimeMillis()
        if (now - homeFetchedAt < HOME_TTL_MS) {
            throw IOException("home already fresh")
        }
        val feed = yt.home()
        homeFetchedAt = now
        offer(feed.quickPicks, Source.HOME_PICKS)
        val mix = feed.mixes.filter { it.playlistId !in usedPlaylists }.randomOrNull(random)
        if (mix != null) {
            usedPlaylists += mix.playlistId
            offer(yt.queue(playlistId = mix.playlistId).items, Source.HOME_MIX)
        }
    }

    private suspend fun fetchRadioFromLike(likes: List<Swipe>, source: Source) {
        val now = System.currentTimeMillis()
        val fresh = likes.filter { it.track.videoId !in usedRadioSeeds }
        val seed = if (fresh.isNotEmpty()) {
            // Recency-weighted sampling: newest likes are the strongest signal of current mood.
            val weights = fresh.map { exp(-(now - it.timestamp) / (7.0 * 24 * 3600 * 1000)) + 0.05 }
            fresh[weightedIndex(weights)]
        } else {
            likes.filter { usedRadioSeeds[it.track.videoId] != null }.randomOrNull(random)
                ?: likes.randomOrNull(random) ?: return
        }
        fetchRadio(seed.track.videoId, source)
    }

    private suspend fun fetchRadio(videoId: String, source: Source) {
        val page = if (videoId in usedRadioSeeds) {
            val token = usedRadioSeeds[videoId] ?: return
            yt.queueContinuation(token)
        } else {
            yt.radio(videoId)
        }
        usedRadioSeeds[videoId] = page.continuation
        offer(page.items.filter { it.videoId != videoId }, source)
    }

    private suspend fun fetchRadioFromTopArtist(likes: List<Swipe>) {
        val stats = artistStats()
        val topArtist = stats.entries
            .filter { it.value.likes >= 1.0 }
            .maxByOrNull { sampleBeta(1.0 + it.value.likes, 1.0 + it.value.dislikes) }
            ?.key ?: return fetchRadioFromLike(likes, Source.RADIO_LIKED)
        val seed = likes.filter { topArtist in it.track.artistKeys }.randomOrNull(random) ?: return
        fetchRadio(seed.track.videoId, Source.RADIO_ARTIST)
    }

    private suspend fun fetchGenre() {
        val genre = store.current.settings.seedGenres.randomOrNull(random) ?: throw IOException("no genres")
        val playlists = yt.genrePlaylists(genre.params).filter { it.playlistId !in usedPlaylists }
        val playlist = playlists.randomOrNull(random) ?: throw IOException("no playlists for ${genre.name}")
        usedPlaylists += playlist.playlistId
        offer(yt.queue(playlistId = playlist.playlistId).items.shuffled(random), Source.GENRE)
    }

    private suspend fun fetchArtistSeed() {
        val artist = store.current.settings.seedArtists.randomOrNull(random) ?: throw IOException("no artists")
        val top = yt.searchSongs(artist).firstOrNull() ?: throw IOException("no results for $artist")
        fetchRadio(top.videoId, Source.ARTIST_SEED)
    }

    private fun weightedIndex(weights: List<Double>): Int {
        var r = random.nextDouble() * weights.sum()
        weights.forEachIndexed { i, w ->
            r -= w
            if (r <= 0) return i
        }
        return weights.lastIndex
    }

    // ---------------------------------------------------------------- sampling

    private fun sampleBeta(alpha: Double, beta: Double): Double {
        val x = sampleGamma(alpha)
        val y = sampleGamma(beta)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }

    /** Marsaglia & Tsang; valid for shape >= 1, which always holds with our Beta(1 + a, 1 + b) priors. */
    private fun sampleGamma(shape: Double): Double {
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian()
                v = 1.0 + c * x
            } while (v <= 0)
            v = v * v * v
            val u = random.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(): Double {
        val u1 = max(random.nextDouble(), 1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    private companion object {
        const val MIN_POOL = 12
        const val TARGET_POOL = 40
        const val RECENT_ARTIST_WINDOW = 8
        const val HOME_TTL_MS = 10 * 60 * 1000L
    }
}
