package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal expect fun createWatchPartyTransport(): WatchPartyTransport

internal expect fun watchPartyDeviceName(): String

/** Punto d'accesso unico al Watch Party: sessione condivisa + visibilità di pannello e dialog. */
object WatchPartyManager {
    val session: WatchPartySession by lazy {
        WatchPartySession(
            transportFactory = ::createWatchPartyTransport,
            deviceName = ::watchPartyDeviceName,
        )
    }

    private val _panelVisible = MutableStateFlow(false)
    val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()

    private val _joinDialogVisible = MutableStateFlow(false)
    val joinDialogVisible: StateFlow<Boolean> = _joinDialogVisible.asStateFlow()

    fun openPanel() {
        _panelVisible.value = true
    }

    fun closePanel() {
        _panelVisible.value = false
    }

    fun openJoinDialog() {
        _joinDialogVisible.value = true
    }

    fun closeJoinDialog() {
        _joinDialogVisible.value = false
    }
}

/** Trasporto per le piattaforme dove il Watch Party non è disponibile. */
internal class UnsupportedWatchPartyTransport(private val reason: String) : WatchPartyTransport {
    override fun join(room: String, password: String, label: String, listener: WatchPartyTransport.Listener) {
        listener.onError(reason)
    }

    override fun send(json: String, targetUuid: String?) = Unit

    override fun leave() = Unit
}
