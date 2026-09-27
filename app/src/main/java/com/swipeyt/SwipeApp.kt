package com.swipeyt

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.swipeyt.data.auth.AuthStore
import com.swipeyt.data.innertube.InnerTube
import com.swipeyt.data.local.UserStore
import com.swipeyt.data.repo.PlaylistRepository
import com.swipeyt.domain.Recommender
import com.swipeyt.player.PreviewPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val auth = AuthStore(context)
    val userStore = UserStore(context)
    val innerTube = InnerTube(http, auth)
    val playlists = PlaylistRepository(innerTube, userStore)
    val recommender = Recommender(innerTube, userStore)
    val player = PreviewPlayer(context, innerTube, appScope)
}

class SwipeApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .crossfade(true)
            .build()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SwipeApp).container
