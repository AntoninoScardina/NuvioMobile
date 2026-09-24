package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.nuvio.app.features.watchparty.WatchPartyManager
import com.nuvio.app.features.watchparty.WatchPartyMedia
import com.nuvio.app.features.watchparty.WatchPartyPlayer
import com.nuvio.app.features.watchparty.WatchPartyPlayerDialog
import kotlinx.coroutines.delay

/** Il player di Nuvio (ExoPlayer o libmpv) visto dal Watch Party. */
internal class RuntimeWatchPartyPlayer(
    private val runtime: PlayerScreenRuntime,
) : WatchPartyPlayer {
    override val positionMs: Long
        get() = runtime.playbackSnapshot.positionMs

    override val isPlaying: Boolean
        get() = runtime.shouldPlay

    override val isBuffering: Boolean
        get() = runtime.playbackSnapshot.isLoading

    override fun play() {
        if (runtime.shouldPlay) return
        runtime.shouldPlay = true
        runtime.playerController?.play()
    }

    override fun pause() {
        if (!runtime.shouldPlay) return
        runtime.shouldPlay = false
        runtime.playerController?.pause()
    }

    override fun seekTo(positionMs: Long) {
        runtime.playerController?.seekTo(positionMs.coerceAtLeast(0L))
        runtime.scheduleProgressSyncAfterSeek()
    }

    override fun setPlaybackSpeed(speed: Float) {
        runtime.playerController?.setPlaybackSpeed(speed)
    }
}

/** Stream corrente condivisibile con gli altri, o null (torrent/P2P o URL locali). */
internal fun PlayerScreenRuntime.watchPartyMedia(): WatchPartyMedia? {
    val url = activeSourceUrl
    if (url.isBlank() || activeTorrentInfoHash != null || !url.startsWith("http", ignoreCase = true)) return null
    if (url.contains("://127.0.0.1") || url.contains("://localhost")) return null
    return WatchPartyMedia(
        url = url,
        headers = activeSourceHeaders,
        title = args.title,
        subtitle = activeEpisodeTitle,
        contentId = args.parentMetaId,
        contentType = args.parentMetaType,
        videoId = activeVideoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
        poster = args.poster,
        backdrop = args.background,
        logo = args.logo,
        streamName = activeStreamTitle,
    )
}

/** Collega il player aperto alla stanza Watch Party e mostra il relativo pannello. */
@Composable
internal fun PlayerScreenRuntime.BindWatchParty() {
    val session = WatchPartyManager.session
    val adapter = remember(this) { RuntimeWatchPartyPlayer(this) }

    LaunchedEffect(adapter) {
        var attachedUrl: String? = null
        while (true) {
            val snapshot = playbackSnapshot
            val ready = !snapshot.isLoading && snapshot.durationMs > 0L && errorMessage == null
            val media = if (ready) watchPartyMedia() else null
            if (media != null && media.url != attachedUrl) {
                session.attachPlayer(adapter, media)
                attachedUrl = media.url
            }
            delay(1_000)
        }
    }
    DisposableEffect(adapter) {
        onDispose {
            session.detachPlayer(adapter)
            WatchPartyManager.closePanel()
        }
    }

    val panelVisible by WatchPartyManager.panelVisible.collectAsState()
    if (panelVisible) {
        WatchPartyPlayerDialog(
            canShare = watchPartyMedia() != null,
            onDismiss = WatchPartyManager::closePanel,
        )
    }
}
