package info.reviewonce.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var letterboxdView: WebView
    private lateinit var collectorView: WebView
    private lateinit var letterboxdOverlay: FrameLayout
    private lateinit var collectionStore: LocalCollectionStore
    private lateinit var collectionImporter: LetterboxdCollectionImporter
    private lateinit var exportImporter: LetterboxdExportImporter
    private lateinit var syncController: LetterboxdSyncController
    private lateinit var letterboxdTitle: TextView
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private var connectingToLetterboxd = false
    private var exportingLetterboxd = false
    private var exportDownloadId: Long? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (completedId == exportDownloadId) finishExportDownload(completedId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectionStore = LocalCollectionStore(this)
        exportImporter = LetterboxdExportImporter(collectionStore)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString + " ReviewOnceAndroid/0.2.2"
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }
        val browserUserAgent = webView.settings.userAgentString.replace(Regex("\\s+ReviewOnceAndroid/\\S+"), "")
        letterboxdView = createLetterboxdWebView(browserUserAgent)
        collectorView = createLetterboxdWebView(browserUserAgent).apply {
            alpha = 0f
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        collectionImporter = LetterboxdCollectionImporter(
            webView = collectorView,
            store = collectionStore,
        )
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0,
        )
        letterboxdView.setDownloadListener { url, userAgent, _, mimeType, _ ->
            if (exportingLetterboxd && url.startsWith(LETTERBOXD_URL)) {
                downloadLetterboxdExport(url, userAgent, mimeType)
            }
        }
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
                        "connect" -> connectLetterboxd(uri.getQueryParameter("username").orEmpty())
                        "export" -> openLetterboxdExport()
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

        letterboxdView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" || request.url.host?.endsWith("letterboxd.com") != true

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (url.startsWith(LETTERBOXD_URL)) handleLetterboxdPage(url)
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
            setAcceptThirdPartyCookies(letterboxdView, false)
            setAcceptThirdPartyCookies(collectorView, false)
        }
        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(collectorView, FrameLayout.LayoutParams(dp(1), dp(1), Gravity.BOTTOM or Gravity.END))
        }
        letterboxdOverlay = buildLetterboxdOverlay()
        root.addView(letterboxdOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
        // Letterboxd is only a temporary authentication surface. Every app launch
        // starts from ReviewOnce, even if Android killed the activity mid-login.
        webView.loadUrl(REVIEW_ONCE_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createLetterboxdWebView(userAgent: String) = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString = userAgent
    }

    private fun buildLetterboxdOverlay(): FrameLayout {
        letterboxdTitle = TextView(this).apply {
            text = "Connexion à Letterboxd"
            textSize = 16f
            setTextColor(Color.rgb(245, 247, 248))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
        }
        val closeButton = Button(this).apply {
            text = "Fermer"
            textSize = 14f
            minWidth = dp(72)
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(45, 52, 58))
            setOnClickListener { cancelLetterboxdConnection() }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 29, 33))
            addView(letterboxdTitle, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(closeButton, LinearLayout.LayoutParams(dp(88), dp(48)).apply {
                marginEnd = dp(8)
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 29, 33))
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(letterboxdView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        return FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(24, 29, 33))
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars =
                    insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                }
                insets
            }
        }
    }

    private fun showLetterboxdOverlay(title: String) {
        letterboxdTitle.text = title
        letterboxdOverlay.visibility = View.VISIBLE
        letterboxdOverlay.bringToFront()
        letterboxdOverlay.requestApplyInsets()
    }

    private fun hideLetterboxdOverlay() {
        letterboxdView.stopLoading()
        letterboxdOverlay.visibility = View.GONE
    }

    private fun connectLetterboxd(usernameHint: String) {
        connectingToLetterboxd = true
        if (usernameHint.isNotBlank()) {
            preferences.edit().putString(PENDING_LETTERBOXD_USERNAME, usernameHint).apply()
        }
        showLetterboxdOverlay("Connexion à Letterboxd")
        letterboxdView.loadUrl(LETTERBOXD_SIGN_IN_URL)
    }

    private fun handleLetterboxdPage(url: String) {
        if (exportingLetterboxd) {
            showLetterboxdOverlay("Importer la bibliothèque")
            if (!isSignInPage(url) && !url.startsWith(LETTERBOXD_DATA_URL)) {
                letterboxdView.loadUrl(LETTERBOXD_DATA_URL)
            }
            return
        }
        if (!connectingToLetterboxd) return
        if (isSignInPage(url)) {
            letterboxdView.evaluateJavascript(CAPTURE_LOGIN_USERNAME_SCRIPT, null)
            return
        }
        letterboxdView.evaluateJavascript(READ_ACCOUNT_SCRIPT) { raw ->
            val username = decodeJavascriptString(raw)
                .ifBlank { preferences.getString(PENDING_LETTERBOXD_USERNAME, "").orEmpty() }
            if (username.isBlank()) return@evaluateJavascript
            preferences.edit()
                .putString(LETTERBOXD_USERNAME, username)
                .putBoolean(LETTERBOXD_CONNECTED, true)
                .remove(PENDING_LETTERBOXD_USERNAME)
                .apply()
            connectingToLetterboxd = false
            hideLetterboxdOverlay()
            webView.postDelayed({ sendSession() }, CALLBACK_DELAY_MS)
        }
    }

    private fun cancelLetterboxdConnection() {
        connectingToLetterboxd = false
        exportingLetterboxd = false
        hideLetterboxdOverlay()
        webView.postDelayed({ sendSession() }, CALLBACK_DELAY_MS)
    }

    private fun isSignInPage(url: String): Boolean =
        url.contains("/sign-in", ignoreCase = true) || url.contains("/account/login", ignoreCase = true)

    private fun sendSession() {
        val username = preferences.getString(LETTERBOXD_USERNAME, "").orEmpty()
        sendNativeEvent(
            type = "session",
            username = username,
            connected = preferences.getBoolean(LETTERBOXD_CONNECTED, false) && username.isNotBlank(),
            collectionCount = collectionStore.countEntries(),
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
                            collectionCount = count,
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

    private fun openLetterboxdExport() {
        exportingLetterboxd = true
        showLetterboxdOverlay("Importer la bibliothèque")
        letterboxdView.loadUrl(LETTERBOXD_DATA_URL)
    }

    private fun downloadLetterboxdExport(
        url: String,
        userAgent: String,
        mimeType: String,
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType.ifBlank { "application/zip" })
            setTitle("Bibliothèque Letterboxd")
            setDescription("Import dans ReviewOnce")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(
                this@MainActivity,
                Environment.DIRECTORY_DOWNLOADS,
                "letterboxd-export-${System.currentTimeMillis()}.zip",
            )
            addRequestHeader("User-Agent", userAgent)
            addRequestHeader("Referer", LETTERBOXD_DATA_URL)
            CookieManager.getInstance().getCookie(LETTERBOXD_URL)?.let { addRequestHeader("Cookie", it) }
        }
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        exportDownloadId = manager.enqueue(request)
        letterboxdTitle.text = "Import en cours…"
    }

    private fun finishExportDownload(downloadId: Long) {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                returnToReviewOnce("collection-error", "Le téléchargement Letterboxd a échoué. Réessaie depuis l’application.")
                return
            }
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val result = runCatching {
                contentResolver.openInputStream(Uri.parse(localUri)).use { input ->
                    requireNotNull(input) { "Fichier téléchargé introuvable." }
                    exportImporter.import(input)
                }
            }
            result.fold(
                onSuccess = { count -> returnToReviewOnce("collection-complete", "$count films Letterboxd importés", count) },
                onFailure = { error -> returnToReviewOnce("collection-error", error.message ?: "Export Letterboxd illisible.") },
            )
        }
    }

    private fun returnToReviewOnce(type: String, message: String, count: Int? = null) {
        exportingLetterboxd = false
        exportDownloadId = null
        hideLetterboxdOverlay()
        webView.postDelayed({
            sendNativeEvent(
                type = type,
                message = message,
                collection = if (type == "collection-complete") collectionStore.collectionJson() else null,
                collectionCount = count,
            )
        }, CALLBACK_DELAY_MS)
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
        sendNativeEvent(
            type = "collection",
            collection = collectionStore.collectionJson(),
            collectionCount = collectionStore.countEntries(),
        )
    }

    private fun sendNativeEvent(
        type: String,
        message: String = "",
        username: String = "",
        connected: Boolean? = null,
        collection: String? = null,
        collectionCount: Int? = null,
    ) {
        if (!webView.url.orEmpty().startsWith(REVIEW_ONCE_URL)) return
        val payload = JSONObject().apply {
            put("type", type)
            if (message.isNotBlank()) put("message", message)
            if (username.isNotBlank()) put("username", username)
            if (connected != null) put("connected", connected)
            if (collection != null) put("collection", JSONTokener(collection).nextValue())
            if (collectionCount != null) put("collectionCount", collectionCount)
        }
        webView.evaluateJavascript(
            "window.__reviewOnceNativeEvent && window.__reviewOnceNativeEvent(${JSONObject.quote(payload.toString())})",
            null,
        )
    }

    private fun decodeJavascriptString(raw: String): String =
        runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        letterboxdView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        letterboxdView.onResume()
        if (webView.url.orEmpty().startsWith(REVIEW_ONCE_URL)) webView.postDelayed({ sendSession() }, 300)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (letterboxdOverlay.visibility == View.VISIBLE) {
            cancelLetterboxdConnection()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(downloadReceiver)
        collectionImporter.close()
        webView.destroy()
        letterboxdView.destroy()
        collectorView.destroy()
        collectionStore.close()
        super.onDestroy()
    }

    companion object {
        private const val REVIEW_ONCE_URL = "https://senssync-films.hushed-plume-0999.chatgpt.site/"
        private const val LETTERBOXD_URL = "https://letterboxd.com/"
        private const val LETTERBOXD_SIGN_IN_URL = "https://letterboxd.com/sign-in/"
        private const val LETTERBOXD_DATA_URL = "https://letterboxd.com/settings/data/"
        private const val PREFERENCES = "reviewonce-local"
        private const val LETTERBOXD_USERNAME = "letterboxd-username"
        private const val PENDING_LETTERBOXD_USERNAME = "pending-letterboxd-username"
        private const val LETTERBOXD_CONNECTED = "letterboxd-connected"
        private const val CALLBACK_DELAY_MS = 900L
        private val CAPTURE_LOGIN_USERNAME_SCRIPT = """
            (() => {
              const input = document.querySelector(
                'input[name="username"], input[autocomplete="username"], input#username'
              );
              if (!input || input.dataset.reviewOnceCapture === '1') return;
              input.dataset.reviewOnceCapture = '1';
              const save = () => {
                const username = input.value.trim();
                if (username) localStorage.setItem('reviewonce-login-username', username);
              };
              input.addEventListener('input', save);
              input.addEventListener('change', save);
              input.form?.addEventListener('submit', save, true);
              save();
            })()
        """.trimIndent()
        private val READ_ACCOUNT_SCRIPT = """
            (() => {
              const captured = localStorage.getItem('reviewonce-login-username');
              if (captured) return captured;
              const selectors = [
                '.navitem-profile a[href]',
                'a[href$="/activity/"]',
                'a[href$="/films/"][data-person]',
                'a[href*="/profile/"][href]'
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
