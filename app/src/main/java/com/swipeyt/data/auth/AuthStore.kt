package com.swipeyt.data.auth

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthState(
    val cookie: String? = null,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val accountName: String? = null,
    val accountPhotoUrl: String? = null,
) {
    val isLoggedIn: Boolean get() = !cookie.isNullOrBlank() && cookie.contains("SAPISID")
}

/** Persists the YouTube Music web session captured from the login WebView. */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AuthState(
            cookie = prefs.getString(KEY_COOKIE, null),
            visitorData = prefs.getString(KEY_VISITOR, null),
            dataSyncId = prefs.getString(KEY_DATASYNC, null),
            accountName = prefs.getString(KEY_NAME, null),
            accountPhotoUrl = prefs.getString(KEY_PHOTO, null),
        )
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()
    val current: AuthState get() = _state.value

    fun update(transform: (AuthState) -> AuthState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit {
            putString(KEY_COOKIE, next.cookie)
            putString(KEY_VISITOR, next.visitorData)
            putString(KEY_DATASYNC, next.dataSyncId)
            putString(KEY_NAME, next.accountName)
            putString(KEY_PHOTO, next.accountPhotoUrl)
        }
    }

    fun clear() = update { AuthState() }

    private companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_VISITOR = "visitor_data"
        const val KEY_DATASYNC = "data_sync_id"
        const val KEY_NAME = "account_name"
        const val KEY_PHOTO = "account_photo"
    }
}
