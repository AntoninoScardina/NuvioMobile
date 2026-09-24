package com.nuvio.app.features.watchparty

import kotlinx.serialization.Serializable

/** Stream condiviso dall'host: i guest aprono esattamente lo stesso flusso (URL + header). */
@Serializable
data class WatchPartyMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String? = null,
    val subtitle: String? = null,
    val contentId: String? = null,
    val contentType: String? = null,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val streamName: String? = null,
)

/**
 * Unico messaggio scambiato sul canale dati VDO.Ninja.
 *
 * type:
 *  - HELLO         — presentazione (name, host)
 *  - MEDIA         — host → guest: stream da aprire + stato di riproduzione
 *  - STATE         — host → guest: posizione/play periodici e ad ogni cambio
 *  - REQUEST_STATE — guest → host: chiede MEDIA/STATE (appena entrato o appena aperto il player)
 *  - CMD           — guest → host: play/pause/seek fatti dal guest
 *  - BYE           — uscita volontaria
 */
@Serializable
data class WatchPartyWire(
    val type: String,
    val version: Int = WatchPartyProtocol.VERSION,
    val name: String? = null,
    val host: Boolean? = null,
    val media: WatchPartyMedia? = null,
    val positionMs: Long? = null,
    val playing: Boolean? = null,
    val action: String? = null,
)

object WatchPartyProtocol {
    const val VERSION = 1

    const val HELLO = "HELLO"
    const val MEDIA = "MEDIA"
    const val STATE = "STATE"
    const val REQUEST_STATE = "REQUEST_STATE"
    const val CMD = "CMD"
    const val BYE = "BYE"

    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_SEEK = "seek"

    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6

    fun generateCode(): String = buildString {
        repeat(CODE_LENGTH) { append(CODE_ALPHABET.random()) }
    }

    fun normalizeCode(input: String): String? {
        val code = input.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != CODE_LENGTH || code.any { it !in CODE_ALPHABET }) return null
        return code
    }

    fun roomFor(code: String): String = "nuviowatchparty$code"

    fun passwordFor(code: String): String = "nuvio-wp-$code"
}
