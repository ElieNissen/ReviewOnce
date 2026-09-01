package info.reviewonce.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
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

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var collectionStore: LocalCollectionStore

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectionStore = LocalCollectionStore(this)

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
        controls.addView(reviewOnceButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(letterboxdButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    return if (scheme == "https") false else true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
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
