package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.time.TimeSource

enum class WatchPartyRole { HOST, GUEST }

enum class WatchPartyStatus { IDLE, CONNECTING, CONNECTED, ERROR }

data class WatchPartyState(
    val status: WatchPartyStatus = WatchPartyStatus.IDLE,
    val role: WatchPartyRole? = null,
    val code: String? = null,
    val participants: List<String> = emptyList(),
    val hostPresent: Boolean = false,
    val error: String? = null,
) {
    val isActive: Boolean get() = status == WatchPartyStatus.CONNECTING || status == WatchPartyStatus.CONNECTED
}

/**
 * Stanza Watch Party: l'host è l'autorità sulla riproduzione, i guest si allineano.
 * Tutti possono dare play/pausa/seek: i comandi dei guest passano dall'host (CMD) che li
 * applica e ridistribuisce lo stato (STATE). Tutti i metodi vanno chiamati dal main thread.
 */
class WatchPartySession(
    private val transportFactory: () -> WatchPartyTransport,
    private val deviceName: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val clock = TimeSource.Monotonic.markNow()

    private val _state = MutableStateFlow(WatchPartyState())
    val state: StateFlow<WatchPartyState> = _state.asStateFlow()

    /** Stream che il guest deve aprire: l'app naviga al player e poi chiama [consumeMediaRequest]. */
    private val _mediaRequest = MutableStateFlow<WatchPartyMedia?>(null)
    val mediaRequest: StateFlow<WatchPartyMedia?> = _mediaRequest.asStateFlow()

    private var transport: WatchPartyTransport? = null
    private var tickerJob: Job? = null
    private val peerNames = linkedMapOf<String, String>()
    private var hostUuid: String? = null

    private var player: WatchPartyPlayer? = null
    private var playerMedia: WatchPartyMedia? = null
    private var hostMedia: WatchPartyMedia? = null

    private var lastPosition = 0L
    private var lastPlaying = false
    private var lastTickAt = 0L
    private var suppressUntil = 0L
    private var ignoreHostStateUntil = 0L
    private var lastStateSentAt = 0L

    private var hostPosition = 0L
    private var hostPlaying = false
    private var hostStateAt = -1L

    // Guest: dopo un salto aspetta che il player riparta prima di correggere di nuovo, e misura
    // quanto ci mette (seekLeadMs) per saltare già un po' più avanti la volta successiva.
    private var awaitingSettle = false
    private var lastSeekAt = 0L
    private var lastSeekTarget = 0L
    private var settledAt = 0L
    private var seekLeadMs = 0L
    private var currentSpeed = 1f

    private fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    fun createRoom(): String {
        val code = WatchPartyProtocol.generateCode()
        start(code, WatchPartyRole.HOST)
        return code
    }

    /** Ritorna false se il codice non è valido. */
    fun joinRoom(rawCode: String): Boolean {
        val code = WatchPartyProtocol.normalizeCode(rawCode) ?: return false
        start(code, WatchPartyRole.GUEST)
        return true
    }

    fun leaveRoom() {
        player?.let { setSpeed(it, 1f) }
        transport?.let { t ->
            runCatching { t.send(encode(WatchPartyWire(type = WatchPartyProtocol.BYE))) }
            t.leave()
        }
        transport = null
        tickerJob?.cancel()
        tickerJob = null
        peerNames.clear()
        hostUuid = null
        hostMedia = null
        hostStateAt = -1L
        awaitingSettle = false
        seekLeadMs = 0L
        _mediaRequest.value = null
        _state.value = WatchPartyState()
    }

    fun consumeMediaRequest() {
        _mediaRequest.value = null
    }

    /** Il player chiama questo appena è pronto a riprodurre [media]. */
    fun attachPlayer(player: WatchPartyPlayer, media: WatchPartyMedia) {
        this.player = player
        this.playerMedia = media
        currentSpeed = 1f
        awaitingSettle = false
        resetBaseline(player)
        if (!_state.value.isActive) return
        when (_state.value.role) {
            WatchPartyRole.HOST -> broadcastMedia()
            WatchPartyRole.GUEST -> {
                if (sameStream(media, hostMedia)) applyHostState(force = true)
                sendToHost(WatchPartyWire(type = WatchPartyProtocol.REQUEST_STATE))
            }
            null -> Unit
        }
    }

    fun detachPlayer(player: WatchPartyPlayer) {
        if (this.player !== player) return
        setSpeed(player, 1f)
        this.player = null
        this.playerMedia = null
    }

    /** Per il pulsante "Watch Party" nel player: l'host crea la stanza dal player aperto. */
    val attachedMedia: WatchPartyMedia? get() = playerMedia

    private fun start(code: String, role: WatchPartyRole) {
        leaveRoom()
        _state.value = WatchPartyState(status = WatchPartyStatus.CONNECTING, role = role, code = code)
        val t = transportFactory()
        transport = t
        t.join(
            room = WatchPartyProtocol.roomFor(code),
            password = WatchPartyProtocol.passwordFor(code),
            label = deviceName(),
            listener = TransportListener(t),
        )
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private inner class TransportListener(private val owner: WatchPartyTransport) : WatchPartyTransport.Listener {
        private val current get() = transport === owner

        override fun onJoined() {
            if (!current) return
            _state.update { it.copy(status = WatchPartyStatus.CONNECTED, error = null) }
        }

        override fun onPeerJoined(uuid: String) {
            if (!current) return
            peerNames.getOrPut(uuid) { "…" }
            publishParticipants()
            val isHost = _state.value.role == WatchPartyRole.HOST
            send(WatchPartyWire(type = WatchPartyProtocol.HELLO, name = deviceName(), host = isHost), uuid)
            if (isHost) sendMediaTo(uuid)
        }

        override fun onPeerLeft(uuid: String) {
            if (!current) return
            peerNames.remove(uuid)
            if (uuid == hostUuid) hostUuid = null
            publishParticipants()
        }

        override fun onMessage(uuid: String, json: String) {
            if (!current) return
            val msg = runCatching { this@WatchPartySession.json.decodeFromString(WatchPartyWire.serializer(), json) }
                .getOrNull() ?: return
            handleMessage(uuid, msg)
        }

        override fun onError(message: String) {
            if (!current) return
            _state.update { it.copy(status = WatchPartyStatus.ERROR, error = message) }
        }
    }

    private fun handleMessage(uuid: String, msg: WatchPartyWire) {
        val role = _state.value.role ?: return
        when (msg.type) {
            WatchPartyProtocol.HELLO -> {
                peerNames[uuid] = msg.name?.take(40)?.ifBlank { null } ?: "Ospite"
                if (msg.host == true && role == WatchPartyRole.GUEST) {
                    hostUuid = uuid
                    sendToHost(WatchPartyWire(type = WatchPartyProtocol.REQUEST_STATE))
                }
                publishParticipants()
            }
            WatchPartyProtocol.BYE -> {
                peerNames.remove(uuid)
                if (uuid == hostUuid) hostUuid = null
                publishParticipants()
            }
            WatchPartyProtocol.REQUEST_STATE -> if (role == WatchPartyRole.HOST) sendMediaTo(uuid)
            WatchPartyProtocol.CMD -> if (role == WatchPartyRole.HOST) applyGuestCommand(msg)
            WatchPartyProtocol.MEDIA -> if (role == WatchPartyRole.GUEST) {
                hostUuid = uuid
                val media = msg.media ?: return
                hostMedia = media
                recordHostState(msg)
                if (!sameStream(media, playerMedia)) {
                    _mediaRequest.value = media
                } else {
                    applyHostState(force = true)
                }
                publishParticipants()
            }
            WatchPartyProtocol.STATE -> if (role == WatchPartyRole.GUEST) {
                hostUuid = uuid
                recordHostState(msg)
                if (now() >= ignoreHostStateUntil) applyHostState(force = false)
            }
        }
    }

    // ---- Host ----

    private fun sendMediaTo(uuid: String?) {
        val p = player
        val media = playerMedia ?: return
        send(
            WatchPartyWire(
                type = WatchPartyProtocol.MEDIA,
                media = media,
                positionMs = p?.positionMs,
                playing = p?.isPlaying,
            ),
            uuid,
        )
    }

    private fun broadcastMedia() = sendMediaTo(null)

    private fun broadcastState() {
        val p = player ?: return
        if (playerMedia == null) return
        lastStateSentAt = now()
        send(WatchPartyWire(type = WatchPartyProtocol.STATE, positionMs = p.positionMs, playing = p.isPlaying))
    }

    private fun applyGuestCommand(msg: WatchPartyWire) {
        val p = player ?: return
        suppressUntil = now() + SUPPRESS_MS
        when (msg.action) {
            WatchPartyProtocol.ACTION_PLAY -> {
                msg.positionMs?.let { if (abs(it - p.positionMs) > SEEK_THRESHOLD_MS) p.seekTo(it) }
                p.play()
            }
            WatchPartyProtocol.ACTION_PAUSE -> {
                p.pause()
                msg.positionMs?.let { if (abs(it - p.positionMs) > SEEK_THRESHOLD_MS) p.seekTo(it) }
            }
            WatchPartyProtocol.ACTION_SEEK -> msg.positionMs?.let { p.seekTo(it) }
        }
        resetBaseline(p, expectedPosition = msg.positionMs, expectedPlaying = when (msg.action) {
            WatchPartyProtocol.ACTION_PLAY -> true
            WatchPartyProtocol.ACTION_PAUSE -> false
            else -> null
        })
        broadcastState()
    }

    // ---- Guest ----

    private fun recordHostState(msg: WatchPartyWire) {
        val position = msg.positionMs ?: return
        hostPosition = position
        hostPlaying = msg.playing ?: hostPlaying
        hostStateAt = now()
    }

    private fun estimatedHostPosition(): Long =
        if (hostPlaying) hostPosition + (now() - hostStateAt) else hostPosition

    private fun applyHostState(force: Boolean) {
        val p = player ?: return
        if (hostStateAt < 0 || !sameStream(playerMedia, hostMedia)) return
        val t = now()
        if (!force && (p.isBuffering || awaitingSettle || t - settledAt < SETTLE_MS)) return

        if (p.isPlaying != hostPlaying) {
            if (hostPlaying) p.play() else p.pause()
            suppressUntil = t + SUPPRESS_MS
            resetBaseline(p, expectedPlaying = hostPlaying)
        }

        val target = estimatedHostPosition()
        val drift = target - p.positionMs
        when {
            abs(drift) > SEEK_THRESHOLD_MS || ((force || !hostPlaying) && abs(drift) > SPEED_START_MS) -> {
                setSpeed(p, 1f)
                val seekTarget = target + if (hostPlaying) seekLeadMs else 0L
                p.seekTo(seekTarget)
                lastSeekAt = t
                lastSeekTarget = seekTarget
                awaitingSettle = true
                suppressUntil = t + SUPPRESS_MS
                resetBaseline(p, expectedPosition = seekTarget, expectedPlaying = hostPlaying)
            }
            hostPlaying && abs(drift) > SPEED_START_MS ->
                setSpeed(p, 1f + (drift / SPEED_GAIN_MS).coerceIn(-MAX_SPEED_DELTA, MAX_SPEED_DELTA))
            !hostPlaying || abs(drift) < SPEED_STOP_MS -> setSpeed(p, 1f)
        }
    }

    /** Guest: il salto è "assestato" quando il player ha ripreso a scorrere oltre il punto di arrivo. */
    private fun checkSeekSettled(p: WatchPartyPlayer, t: Long) {
        if (!awaitingSettle) return
        val elapsed = t - lastSeekAt
        val resumed = !p.isBuffering && (!hostPlaying || p.positionMs >= lastSeekTarget + 200)
        if (resumed && elapsed >= 500) {
            if (hostPlaying) {
                seekLeadMs = if (seekLeadMs == 0L) elapsed else (seekLeadMs + elapsed) / 2
                seekLeadMs = seekLeadMs.coerceAtMost(MAX_SEEK_LEAD_MS)
            }
            awaitingSettle = false
            settledAt = t
        } else if (elapsed > SETTLE_TIMEOUT_MS) {
            awaitingSettle = false
            settledAt = t
        }
    }

    private fun setSpeed(p: WatchPartyPlayer, speed: Float) {
        if (speed == currentSpeed) return
        currentSpeed = speed
        p.setPlaybackSpeed(speed)
    }

    private fun sendToHost(msg: WatchPartyWire) {
        send(msg, hostUuid)
    }

    // ---- Rilevamento azioni locali (polling, indipendente dal motore del player) ----

    private fun tick() {
        val p = player ?: return
        if (!_state.value.isActive) return
        val t = now()
        val position = p.positionMs
        val playing = p.isPlaying
        val elapsed = t - lastTickAt

        if (_state.value.role == WatchPartyRole.GUEST) checkSeekSettled(p, t)

        if (t < suppressUntil || awaitingSettle) {
            resetBaseline(p)
        } else {
            val playChanged = playing != lastPlaying
            val expected = lastPosition + if (lastPlaying) elapsed else 0L
            val seeked = position < lastPosition - SEEK_DETECT_MS || position > expected + SEEK_DETECT_MS
            lastPosition = position
            lastPlaying = playing
            lastTickAt = t
            if (playChanged || seeked) onLocalAction(playChanged, seeked, position, playing)
        }

        when (_state.value.role) {
            WatchPartyRole.HOST -> if (t - lastStateSentAt >= STATE_INTERVAL_MS && peerNames.isNotEmpty()) broadcastState()
            WatchPartyRole.GUEST -> if (t >= ignoreHostStateUntil) applyHostState(force = false)
            null -> Unit
        }
    }

    private fun onLocalAction(playChanged: Boolean, seeked: Boolean, position: Long, playing: Boolean) {
        when (_state.value.role) {
            WatchPartyRole.HOST -> broadcastState()
            WatchPartyRole.GUEST -> {
                ignoreHostStateUntil = now() + GUEST_GRACE_MS
                val action = when {
                    playChanged && playing -> WatchPartyProtocol.ACTION_PLAY
                    playChanged -> WatchPartyProtocol.ACTION_PAUSE
                    else -> WatchPartyProtocol.ACTION_SEEK
                }
                sendToHost(WatchPartyWire(type = WatchPartyProtocol.CMD, action = action, positionMs = position))
                if (playChanged && seeked) {
                    sendToHost(WatchPartyWire(type = WatchPartyProtocol.CMD, action = WatchPartyProtocol.ACTION_SEEK, positionMs = position))
                }
            }
            null -> Unit
        }
    }

    private fun resetBaseline(p: WatchPartyPlayer, expectedPosition: Long? = null, expectedPlaying: Boolean? = null) {
        lastPosition = expectedPosition ?: p.positionMs
        lastPlaying = expectedPlaying ?: p.isPlaying
        lastTickAt = now()
    }

    // ---- Utility ----

    private fun publishParticipants() {
        val role = _state.value.role
        _state.update {
            it.copy(
                participants = peerNames.values.toList(),
                hostPresent = role == WatchPartyRole.HOST || (hostUuid != null && peerNames.containsKey(hostUuid)),
            )
        }
    }

    private fun send(msg: WatchPartyWire, targetUuid: String? = null) {
        transport?.send(encode(msg), targetUuid)
    }

    private fun encode(msg: WatchPartyWire): String = json.encodeToString(WatchPartyWire.serializer(), msg)

    private fun sameStream(a: WatchPartyMedia?, b: WatchPartyMedia?): Boolean =
        a != null && b != null && a.url == b.url

    private companion object {
        const val TICK_MS = 500L
        const val STATE_INTERVAL_MS = 2_000L
        const val SEEK_THRESHOLD_MS = 3_000L
        const val SPEED_START_MS = 400L
        const val SPEED_STOP_MS = 120L
        const val SPEED_GAIN_MS = 12_000f
        const val MAX_SPEED_DELTA = 0.10f
        const val SETTLE_MS = 1_500L
        const val SETTLE_TIMEOUT_MS = 20_000L
        const val MAX_SEEK_LEAD_MS = 8_000L
        const val SEEK_DETECT_MS = 2_500L
        const val SUPPRESS_MS = 1_500L
        const val GUEST_GRACE_MS = 2_500L
    }
}
