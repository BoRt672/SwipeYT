package com.swipeyt.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.swipeyt.AppContainer
import kotlinx.coroutines.launch

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

@Composable
fun LoginScreen(container: AppContainer) {
    var showWebView by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun completeLogin(cookie: String, visitorData: String?, dataSyncId: String?) {
        if (finishing) return
        finishing = true
        container.auth.update {
            it.copy(cookie = cookie, visitorData = visitorData ?: it.visitorData, dataSyncId = dataSyncId)
        }
        scope.launch {
            try {
                if (visitorData == null) container.innerTube.refreshSessionIds()
                val info = container.innerTube.accountInfo()
                container.auth.update { it.copy(accountName = info?.name, accountPhotoUrl = info?.photoUrl) }
                container.playlists.ensurePlaylistId()
            } catch (e: Exception) {
                error = e.message
            } finally {
                finishing = false
            }
        }
    }

    if (showWebView && !finishing) {
        LoginWebView(
            onBack = { showWebView = false },
            onSession = { cookie, visitor, dataSync -> completeLogin(cookie, visitor, dataSync) },
        )
    } else {
        Welcome(
            busy = finishing,
            error = error,
            onSignIn = { showWebView = true },
        )
    }
}

@Composable
private fun Welcome(busy: Boolean, error: String?, onSignIn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surface))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(148.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(32.dp))
            Text("Swipe YT", style = MaterialTheme.typography.displayMedium, color = colors.onSurface)
            Spacer(Modifier.height(12.dp))
            Text(
                "Discover music one swipe at a time. Right to keep, left to skip — everything you love lands in a " +
                    "\"Swipe YT\" playlist in your YouTube Music library.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            if (busy) {
                LoadingIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Setting up your account…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Filled.Favorite, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Text("Sign in with YouTube Music", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error, color = colors.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginWebView(
    onBack: () -> Unit,
    onSession: (cookie: String, visitorData: String?, dataSyncId: String?) -> Unit,
) {
    var progressVisible by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Sign in to YouTube Music",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (progressVisible) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                progressVisible = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                progressVisible = false
                                if (captured || url == null || !url.startsWith("https://music.youtube.com")) return
                                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
                                if (!cookie.contains("SAPISID")) return
                                captured = true
                                view.evaluateJavascript(
                                    "(function(){try{return JSON.stringify({v:ytcfg.get('VISITOR_DATA'),d:ytcfg.get('DATASYNC_ID')})}catch(e){return null}})()",
                                ) { result ->
                                    val json = result?.takeIf { it != "null" }?.trim('"')?.replace("\\\"", "\"")
                                    val visitor = json?.let { Regex("\"v\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                                    val dataSync = json?.let { Regex("\"d\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                                        ?.substringBefore("||")?.takeIf { it.isNotBlank() }
                                    onSession(cookie, visitor, dataSync)
                                }
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
            )
        }
    }
}
