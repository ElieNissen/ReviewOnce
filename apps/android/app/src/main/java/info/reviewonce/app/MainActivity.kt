package info.reviewonce.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONTokener

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var collectionStore: LocalCollectionStore
    private lateinit var collectionImporter: LetterboxdCollectionImporter
    private lateinit var syncController: LetterboxdSyncController

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectionStore = LocalCollectionStore(this)
        collectionImporter = LetterboxdCollectionImporter(collectionStore)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 247, 245))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 10, 12, 10)
        }
        status = TextView(this).apply {
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }
        val reviewOnceButton = Button(this).apply {
            text = "ReviewOnce"
            setOnClickListener { webView.loadUrl(REVIEW_ONCE_URL) }
        }
        val letterboxdButton = Button(this).apply {
            text = "Connexion Letterboxd"
            setOnClickListener { webView.loadUrl(LETTERBOXD_SIGN_IN_URL) }
        }
        val refreshCollectionButton = Button(this).apply {
            text = "Actualiser"
            setOnClickListener { refreshLocalCollection() }
        }
        controls.addView(reviewOnceButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(letterboxdButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(refreshCollectionButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString + " ReviewOnceAndroid/0.1.0"
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    if (syncController.handleResult(request.url)) return true
                    if (scheme == "reviewonce") {
                        handleSyncRequest()
                        return true
                    }
                    return scheme != "https"
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (syncController.onPageFinished(url)) return
                    updateSessionStatus()
                    CookieManager.getInstance().flush()
                }

                override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                    callback.backToSafety(true)
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        syncController = LetterboxdSyncController(
            webView = webView,
            store = collectionStore,
            onStatus = { message -> runOnUiThread { status.text = message } },
            onFinished = { success, failures -> runOnUiThread {
                status.text = "$success synchronisation(s) réussie(s)${if (failures > 0) " · $failures en attente" else ""}"
                webView.loadUrl(REVIEW_ONCE_URL)
            } },
        )

        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        updateSessionStatus()
        webView.loadUrl(savedInstanceState?.getString(STATE_URL) ?: REVIEW_ONCE_URL)
    }

    private fun updateSessionStatus() {
        val hasLetterboxdCookies = !CookieManager.getInstance().getCookie(LETTERBOXD_URL).isNullOrBlank()
        status.text = if (hasLetterboxdCookies) {
            "Session Letterboxd enregistrée sur cet appareil"
        } else {
            "Connecte Letterboxd une fois pour préparer la synchronisation directe"
        }
    }

    private fun refreshLocalCollection() {
        if (!webView.url.orEmpty().startsWith(REVIEW_ONCE_URL)) webView.loadUrl(REVIEW_ONCE_URL)
        webView.postDelayed({
            webView.evaluateJavascript("localStorage.getItem('senssync-lb') || ''") { raw ->
                val username = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                if (username.isBlank()) {
                    status.text = "Renseigne d’abord ton profil Letterboxd dans ReviewOnce"
                    return@evaluateJavascript
                }
                collectionImporter.import(
                    username = username,
                    onProgress = { message -> runOnUiThread { status.text = message } },
                    onComplete = { result -> runOnUiThread {
                        status.text = result.fold(
                            onSuccess = { count -> "$count films Letterboxd enregistrés localement" },
                            onFailure = { error -> error.message ?: "Lecture Letterboxd impossible" },
                        )
                    } },
                )
            }
        }, 500)
    }

    private fun handleSyncRequest() {
        webView.evaluateJavascript("localStorage.getItem('reviewonce-android-payload') || ''") { raw ->
            val payload = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            val actions = runCatching { SyncAction.parse(payload) }.getOrElse {
                status.text = "File de synchronisation invalide"
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
                        status.text = "Connecte-toi d’abord à Letterboxd"
                        webView.loadUrl(LETTERBOXD_SIGN_IN_URL)
                    } else {
                        syncController.start(actions)
                    }
                }
                .show()
        }
    }

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
        updateSessionStatus()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
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
    }
}
