package info.reviewonce.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var collectionStore: LocalCollectionStore
    private lateinit var collectionImporter: LetterboxdCollectionImporter
    private lateinit var syncController: LetterboxdSyncController
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private var connectingToLetterboxd = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectionStore = LocalCollectionStore(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString + " ReviewOnceAndroid/0.2.0"
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }
        collectionImporter = LetterboxdCollectionImporter(
            store = collectionStore,
            userAgent = webView.settings.userAgentString,
        )
        syncController = LetterboxdSyncController(
            webView = webView,
            store = collectionStore,
            onStatus = { message -> runOnUiThread { sendNativeEvent("sync-progress", message = message) } },
            onFinished = { success, failures -> runOnUiThread {
                webView.loadUrl(REVIEW_ONCE_URL)
                webView.postDelayed({
                    sendNativeEvent(
                        type = "sync-complete",
                        message = "$success synchronisation(s) réussie(s)${if (failures > 0) " · $failures en attente" else ""}",
                    )
                }, CALLBACK_DELAY_MS)
            } },
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (syncController.handleResult(uri)) return true
                if (uri.scheme == "reviewonce") {
                    when (uri.host) {
                        "session" -> sendSession()
                        "connect" -> connectLetterboxd()
                        "refresh" -> refreshLocalCollection(uri.getQueryParameter("username").orEmpty())
                        "sync" -> handleSyncRequest()
                        "collection" -> sendLocalCollection()
                    }
                    return true
                }
                return uri.scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (syncController.onPageFinished(url)) return
                CookieManager.getInstance().flush()
                if (url.startsWith(LETTERBOXD_URL)) detectLetterboxdAccount()
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse,
            ) {
                callback.backToSafety(true)
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        setContentView(webView)
        webView.loadUrl(savedInstanceState?.getString(STATE_URL) ?: REVIEW_ONCE_URL)
    }

    private fun connectLetterboxd() {
        connectingToLetterboxd = true
        webView.loadUrl(LETTERBOXD_SIGN_IN_URL)
    }

    private fun detectLetterboxdAccount() {
        webView.evaluateJavascript(DETECT_ACCOUNT_SCRIPT) { raw ->
            val username = decodeJavascriptString(raw)
            if (username.isBlank()) return@evaluateJavascript
            preferences.edit().putString(LETTERBOXD_USERNAME, username).apply()
            if (connectingToLetterboxd) {
                connectingToLetterboxd = false
                webView.loadUrl(REVIEW_ONCE_URL)
                webView.postDelayed({ sendSession() }, CALLBACK_DELAY_MS)
            }
        }
    }

    private fun sendSession() {
        val username = preferences.getString(LETTERBOXD_USERNAME, "").orEmpty()
        val hasCookies = !CookieManager.getInstance().getCookie(LETTERBOXD_URL).isNullOrBlank()
        sendNativeEvent(
            type = "session",
            username = username,
            connected = hasCookies && username.isNotBlank(),
        )
    }

    private fun refreshLocalCollection(username: String) {
        if (username.isBlank()) {
            sendNativeEvent("collection-error", message = "Connecte d’abord ton compte Letterboxd.")
            return
        }
        preferences.edit().putString(LETTERBOXD_USERNAME, username).apply()
        collectionImporter.import(
            username = username,
            onProgress = { message -> runOnUiThread { sendNativeEvent("collection-progress", message = message) } },
            onComplete = { result -> runOnUiThread {
                result.fold(
                    onSuccess = { count ->
                        sendNativeEvent(
                            type = "collection-complete",
                            message = "$count films Letterboxd vérifiés",
                            collection = collectionStore.collectionJson(),
                        )
                    },
                    onFailure = { error ->
                        sendNativeEvent(
                            type = "collection-error",
                            message = error.message ?: "Lecture Letterboxd impossible",
                        )
                    },
                )
            } },
        )
    }

    private fun handleSyncRequest() {
        webView.evaluateJavascript("localStorage.getItem('reviewonce-android-payload') || ''") { raw ->
            val payload = decodeJavascriptString(raw)
            val actions = runCatching { SyncAction.parse(payload) }.getOrElse {
                sendNativeEvent("sync-error", message = "File de synchronisation invalide")
                return@evaluateJavascript
            }
            if (actions.isEmpty()) return@evaluateJavascript
            AlertDialog.Builder(this)
                .setTitle("Synchroniser ${actions.size} film(s) ?")
                .setMessage("ReviewOnce va utiliser ta session Letterboxd locale. Les films déjà traités ne seront pas publiés deux fois.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Synchroniser") { _, _ ->
                    val cookies = CookieManager.getInstance().getCookie(LETTERBOXD_URL)
                    if (cookies.isNullOrBlank()) {
                        sendNativeEvent("sync-error", message = "Connecte-toi d’abord à Letterboxd")
                    } else {
                        syncController.start(actions)
                    }
                }
                .show()
        }
    }

    private fun sendLocalCollection() {
        sendNativeEvent(type = "collection", collection = collectionStore.collectionJson())
    }

    private fun sendNativeEvent(
        type: String,
        message: String = "",
        username: String = "",
        connected: Boolean? = null,
        collection: String? = null,
    ) {
        if (!webView.url.orEmpty().startsWith(REVIEW_ONCE_URL)) return
        val payload = JSONObject().apply {
            put("type", type)
            if (message.isNotBlank()) put("message", message)
            if (username.isNotBlank()) put("username", username)
            if (connected != null) put("connected", connected)
            if (collection != null) put("collection", JSONTokener(collection).nextValue())
        }
        webView.evaluateJavascript(
            "window.__reviewOnceNativeEvent && window.__reviewOnceNativeEvent(${JSONObject.quote(payload.toString())})",
            null,
        )
    }

    private fun decodeJavascriptString(raw: String): String =
        runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_URL, webView.url ?: REVIEW_ONCE_URL)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (webView.url.orEmpty().startsWith(REVIEW_ONCE_URL)) webView.postDelayed({ sendSession() }, 300)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (webView.url.orEmpty().startsWith(LETTERBOXD_URL)) {
            connectingToLetterboxd = false
            webView.loadUrl(REVIEW_ONCE_URL)
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        collectionImporter.close()
        collectionStore.close()
        super.onDestroy()
    }

    companion object {
        private const val REVIEW_ONCE_URL = "https://senssync-films.hushed-plume-0999.chatgpt.site/"
        private const val LETTERBOXD_URL = "https://letterboxd.com/"
        private const val LETTERBOXD_SIGN_IN_URL = "https://letterboxd.com/sign-in/"
        private const val STATE_URL = "current_url"
        private const val PREFERENCES = "reviewonce-local"
        private const val LETTERBOXD_USERNAME = "letterboxd-username"
        private const val CALLBACK_DELAY_MS = 900L
        private val DETECT_ACCOUNT_SCRIPT = """
            (() => {
              const selectors = [
                '.navitem-profile a[href]',
                'a[href$="/activity/"]',
                'a[href$="/films/"][data-person]'
              ];
              for (const selector of selectors) {
                const link = document.querySelector(selector);
                if (!link) continue;
                const parts = new URL(link.href, location.origin).pathname.split('/').filter(Boolean);
                if (parts.length && !['film','films','activity','sign-in'].includes(parts[0])) return parts[0];
              }
              return '';
            })()
        """.trimIndent()
    }
}
