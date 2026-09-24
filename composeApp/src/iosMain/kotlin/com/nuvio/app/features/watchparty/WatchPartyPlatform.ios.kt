package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable
import platform.UIKit.UIDevice

internal actual fun createWatchPartyTransport(): WatchPartyTransport =
    UnsupportedWatchPartyTransport("Watch Party non ancora disponibile su iOS")

internal actual fun watchPartyDeviceName(): String = UIDevice.currentDevice.name

@Composable
internal actual fun WatchPartyPlatformEffect() = Unit
