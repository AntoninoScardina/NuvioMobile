package com.nuvio.app.features.watchparty

/**
 * Canale P2P verso gli altri partecipanti. Su Android è una WebView invisibile che esegue
 * l'SDK VDO.Ninja (signaling ospitato da VDO.Ninja, dati via WebRTC data channel).
 * Tutte le callback del listener arrivano sul main thread.
 */
interface WatchPartyTransport {
    fun join(room: String, password: String, label: String, listener: Listener)
    fun send(json: String, targetUuid: String? = null)
    fun leave()

    interface Listener {
        fun onJoined()
        fun onPeerJoined(uuid: String)
        fun onPeerLeft(uuid: String)
        fun onMessage(uuid: String, json: String)
        fun onError(message: String)
    }
}

/** Il player attualmente aperto, visto dal Watch Party. Chiamato solo dal main thread. */
interface WatchPartyPlayer {
    val positionMs: Long
    /** Intenzione di riprodurre (playWhenReady): resta true anche mentre bufferizza. */
    val isPlaying: Boolean
    val isBuffering: Boolean
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    /** Usata dai guest per recuperare piccoli scarti senza salti (1.0 = normale). */
    fun setPlaybackSpeed(speed: Float)
}
