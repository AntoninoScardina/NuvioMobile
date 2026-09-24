package com.nuvio.app.features.watchparty

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext

@SuppressLint("StaticFieldLeak")
internal object WatchPartyAndroidContext {
    var appContext: Context? = null
}

internal actual fun createWatchPartyTransport(): WatchPartyTransport {
    val context = WatchPartyAndroidContext.appContext
        ?: return UnsupportedWatchPartyTransport("Context Android non disponibile")
    return WebViewWatchPartyTransport(context)
}

internal actual fun watchPartyDeviceName(): String {
    val context = WatchPartyAndroidContext.appContext
    val name = context?.let {
        runCatching { Settings.Global.getString(it.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
    }
    return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
}

@Composable
internal actual fun WatchPartyPlatformEffect() {
    val context = LocalContext.current.applicationContext
    SideEffect { WatchPartyAndroidContext.appContext = context }
}
