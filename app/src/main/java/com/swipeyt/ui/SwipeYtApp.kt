package com.swipeyt.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipeyt.AppContainer
import com.swipeyt.ui.discover.DiscoverScreen
import com.swipeyt.ui.login.LoginScreen
import com.swipeyt.ui.onboarding.OnboardingScreen
import com.swipeyt.ui.playlist.PlaylistScreen
import com.swipeyt.ui.settings.SettingsScreen

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Discover("Discover", Icons.Outlined.Explore, Icons.Filled.Explore),
    Playlist("Swipe YT", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Filled.QueueMusic),
    Settings("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

private enum class Gate { Login, Onboarding, Main }

@Composable
fun SwipeYtApp(container: AppContainer) {
    val auth by container.auth.state.collectAsStateWithLifecycle()
    val userData by container.userStore.data.collectAsStateWithLifecycle()

    val gate = when {
        !auth.isLoggedIn -> Gate.Login
        !userData.settings.onboardingDone -> Gate.Onboarding
        else -> Gate.Main
    }

    AnimatedContent(
        targetState = gate,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "gate",
    ) { target ->
        when (target) {
            Gate.Login -> LoginScreen(container)
            Gate.Onboarding -> OnboardingScreen(container, onDone = {
                container.userStore.updateSettings { it.copy(onboardingDone = true) }
            })
            Gate.Main -> MainScaffold(container)
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer) {
    var tab by rememberSaveable { mutableStateOf(Tab.Discover) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Discover -> DiscoverScreen(container)
                Tab.Playlist -> PlaylistScreen(container)
                Tab.Settings -> SettingsScreen(container)
            }
        }
    }
}
