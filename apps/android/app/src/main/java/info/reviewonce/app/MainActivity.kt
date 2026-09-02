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
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var collectionStore: LocalCollectionStore
    private lateinit var collectionImporter: LetterboxdCollectionImporter
    private lateinit var exportImporter: LetterboxdExportImporter
    private lateinit var syncController: LetterboxdSyncController
    private lateinit var cancelConnectionButton: Button
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
            settings.userAgentString = settings.userAgentString + " ReviewOnceAndroid/0.2.1"
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }
        collectionImporter = LetterboxdCollectionImporter(
            store = collectionStore,
            userAgent = webView.settings.userAgentString,
        )
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0,
        )
        webView.setDownloadListener { url, userAgent, _, mimeType, _ ->
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
                if (url.startsWith(LETTERBOXD_URL)) handleLetterboxdPage(url)
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

        cancelConnectionButton = Button(this).apply {
            text = "Retour à ReviewOnce"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 40, 45))
            visibility = View.GONE
            setOnClickListener { cancelLetterboxdConnection() }
        }
        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(cancelConnectionButton, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.TOP or Gravity.END,
            ).apply { setMargins(dp(8), dp(8), dp(8), 0) })
        }
        setContentView(root)
        // Letterboxd is only a temporary authentication surface. Every app launch
        // starts from ReviewOnce, even if Android killed the activity mid-login.
        webView.loadUrl(REVIEW_ONCE_URL)
    }

    private fun connectLetterboxd(usernameHint: String) {
        connectingToLetterboxd = true
        if (usernameHint.isNotBlank()) {
            preferences.edit().putString(PENDING_LETTERBOXD_USERNAME, usernameHint).apply()
        }
        cancelConnectionButton.visibility = View.VISIBLE
        webView.loadUrl(LETTERBOXD_SIGN_IN_URL)
    }

    private fun handleLetterboxdPage(url: String) {
        if (exportingLetterboxd) {
            cancelConnectionButton.visibility = View.VISIBLE
            if (!isSignInPage(url) && !url.startsWith(LETTERBOXD_DATA_URL)) {
                webView.loadUrl(LETTERBOXD_DATA_URL)
            }
            return
        }
        if (!connectingToLetterboxd) return
        cancelConnectionButton.visibility = View.VISIBLE
        if (isSignInPage(url)) {
            webView.evaluateJavascript(CAPTURE_LOGIN_USERNAME_SCRIPT, null)
            return
        }
        webView.evaluateJavascript(READ_ACCOUNT_SCRIPT) { raw ->
            val username = decodeJavascriptString(raw)
                .ifBlank { preferences.getString(PENDING_LETTERBOXD_USERNAME, "").orEmpty() }
            if (username.isBlank()) return@evaluateJavascript
            preferences.edit()
                .putString(LETTERBOXD_USERNAME, username)
                .putBoolean(LETTERBOXD_CONNECTED, true)
                .remove(PENDING_LETTERBOXD_USERNAME)
                .apply()
            connectingToLetterboxd = false
            cancelConnectionButton.visibility = View.GONE
            webView.loadUrl(REVIEW_ONCE_URL)
            webView.postDelayed({ sendSession() }, CALLBACK_DELAY_MS)
        }
    }

    private fun cancelLetterboxdConnection() {
        connectingToLetterboxd = false
        exportingLetterboxd = false
        cancelConnectionButton.visibility = View.GONE
        webView.loadUrl(REVIEW_ONCE_URL)
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
        cancelConnectionButton.text = "Retour à ReviewOnce"
        cancelConnectionButton.visibility = View.VISIBLE
        webView.loadUrl(LETTERBOXD_DATA_URL)
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
        cancelConnectionButton.text = "Import en cours…"
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
        cancelConnectionButton.text = "Retour à ReviewOnce"
        cancelConnectionButton.visibility = View.GONE
        webView.loadUrl(REVIEW_ONCE_URL)
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
            cancelLetterboxdConnection()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(downloadReceiver)
        webView.destroy()
        collectionImporter.close()
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
