package com.nuvio.app.features.watchparty

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Trasporto VDO.Ninja: una WebView invisibile carica l'SDK ufficiale (assets/watchparty/)
 * e usa solo il canale dati WebRTC. L'origine https fittizia serve a ottenere un secure
 * context (crypto.subtle, richiesto dall'SDK per la cifratura del signaling).
 */
class WebViewWatchPartyTransport(context: Context) : WatchPartyTransport {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    private var webView: WebView? = null
    private var listener: WatchPartyTransport.Listener? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun join(room: String, password: String, label: String, listener: WatchPartyTransport.Listener) {
        this.listener = listener
        val view = runCatching { WebView(appContext) }.getOrElse {
            listener.onError("WebView non disponibile su questo dispositivo")
            return
        }
        webView = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "js: ${message.message()}")
                return true
            }
        }
        view.addJavascriptInterface(Bridge(), "NuvioWatchParty")
        pending += "window.wp.join(${quote(room)}, ${quote(password)}, ${quote(label)});"
        view.loadDataWithBaseURL(BASE_URL, buildHtml(), "text/html", "utf-8", null)
    }

    override fun send(json: String, targetUuid: String?) {
        val target = targetUuid?.let(::quote) ?: "null"
        run("window.wp.send(${quote(json)}, $target);")
    }

    override fun leave() {
        val view = webView ?: return
        webView = null
        listener = null
        ready = false
        pending.clear()
        view.evaluateJavascript("window.wp && window.wp.leave();", null)
        // Lascia il tempo all'SDK di salutare gli altri peer prima di distruggere la WebView.
        main.postDelayed({
            view.removeJavascriptInterface("NuvioWatchParty")
            view.destroy()
        }, 1_500)
    }

    private fun run(script: String) {
        val view = webView ?: return
        if (ready) view.evaluateJavascript(script, null) else pending += script
    }

    private fun onEvent(raw: String) {
        val event = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val l = listener ?: return
        fun str(key: String) = (event[key] as? JsonPrimitive)?.contentOrNull
        when (str("type")) {
            "ready" -> {
                ready = true
                val view = webView ?: return
                pending.forEach { view.evaluateJavascript(it, null) }
                pending.clear()
            }
            "joined" -> l.onJoined()
            "peerJoined" -> str("uuid")?.let(l::onPeerJoined)
            "peerLeft" -> str("uuid")?.let(l::onPeerLeft)
            "message" -> {
                val uuid = str("uuid") ?: return
                val data = event["data"] as? JsonObject ?: return
                l.onMessage(uuid, data.toString())
            }
            "error" -> l.onError(str("message") ?: "Errore di connessione")
            "signaling" -> Log.d(TAG, "signaling ${event["state"]?.jsonPrimitive?.contentOrNull}")
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onEvent(raw: String) {
            main.post { this@WebViewWatchPartyTransport.onEvent(raw) }
        }
    }

    private fun buildHtml(): String {
        val sdk = asset("watchparty/vdoninja-sdk.min.js")
        val bridge = asset("watchparty/watchparty-bridge.js")
        return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>" +
            "<script>$sdk</script><script>$bridge</script></body></html>"
    }

    private fun asset(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }

    private fun quote(value: String): String = JsonPrimitive(value).toString()

    private companion object {
        const val TAG = "WatchParty"
        const val BASE_URL = "https://watchparty.nuvio.app/"
    }
}
