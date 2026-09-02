package info.reviewonce.app

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Reads Letterboxd through Chromium with the user's local session. */
class LetterboxdCollectionImporter(
    private val webView: WebView,
    private val store: LocalCollectionStore,
) {
    private data class Section(val path: String, val label: String, val kind: String)

    private val sections = listOf(
        Section("films/", "films", "films"),
        Section("watchlist/", "watchlist", "watchlist"),
        Section("films/reviews/", "critiques", "reviews"),
        Section("films/diary/", "journal", "diary"),
    )
    private val entries = linkedMapOf<String, LocalLetterboxdEntry>()
    private var username = ""
    private var sectionIndex = 0
    private var page = 1
    private var importing = false
    private var onProgress: ((String) -> Unit)? = null
    private var onComplete: ((Result<Int>) -> Unit)? = null

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host?.endsWith("letterboxd.com") != true

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (!importing) return
                CookieManager.getInstance().flush()
                if (isSignInUrl(url)) {
                    finish(Result.failure(IllegalStateException("Reconnecte ton compte Letterboxd pour continuer.")))
                    return
                }
                if (!url.startsWith(BASE)) {
                    finish(Result.failure(IllegalStateException("Letterboxd n’a pas ouvert la bibliothèque attendue.")))
                    return
                }
                view.postDelayed({ extractCurrentPage() }, PAGE_SETTLE_MS)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (importing && request.isForMainFrame && errorResponse.statusCode >= 400) {
                    finish(Result.failure(IllegalStateException(
                        if (errorResponse.statusCode == 403 || errorResponse.statusCode == 429)
                            "Letterboxd demande une vérification dans son navigateur."
                        else "Letterboxd a répondu ${errorResponse.statusCode}."
                    )))
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (importing && request.isForMainFrame) {
                    finish(Result.failure(IllegalStateException("ReviewOnce n’arrive pas à joindre Letterboxd. Vérifie ta connexion.")))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun import(username: String, onProgress: (String) -> Unit, onComplete: (Result<Int>) -> Unit) {
        if (importing) {
            onComplete(Result.failure(IllegalStateException("Une actualisation est déjà en cours.")))
            return
        }
        if (!USERNAME.matches(username)) {
            onComplete(Result.failure(IllegalArgumentException("Nom de profil Letterboxd invalide.")))
            return
        }
        if (CookieManager.getInstance().getCookie(BASE).isNullOrBlank()) {
            onComplete(Result.failure(IllegalStateException("Reconnecte ton compte Letterboxd pour continuer.")))
            return
        }
        this.username = username
        this.onProgress = onProgress
        this.onComplete = onComplete
        entries.clear()
        sectionIndex = 0
        page = 1
        importing = true
        loadCurrentPage()
    }

    fun close() {
        importing = false
        onProgress = null
        onComplete = null
        webView.stopLoading()
    }

    private fun loadCurrentPage() {
        if (!importing) return
        if (sectionIndex >= sections.size) {
            if (entries.isEmpty()) {
                finish(Result.failure(IllegalStateException("Aucun film trouvé dans cette bibliothèque Letterboxd.")))
            } else {
                store.replaceCollection(entries.values)
                finish(Result.success(entries.size))
            }
            return
        }
        val section = sections[sectionIndex]
        onProgress?.invoke("Récupération de ta bibliothèque · ${section.label} ${page}")
        val suffix = if (page == 1) section.path else "${section.path}page/$page/"
        webView.loadUrl("$BASE/$username/$suffix")
    }

    private fun extractCurrentPage() {
        if (!importing) return
        val kind = sections[sectionIndex].kind
        webView.evaluateJavascript(EXTRACT_PAGE_SCRIPT.replace("__KIND__", JSONObject.quote(kind))) { raw ->
            if (!importing) return@evaluateJavascript
            runCatching { parsePage(raw) }.fold(
                onSuccess = { hasNext ->
                    if (hasNext && page < MAX_PAGES) page += 1 else { sectionIndex += 1; page = 1 }
                    webView.postDelayed({ loadCurrentPage() }, REQUEST_DELAY_MS)
                },
                onFailure = { error ->
                    finish(Result.failure(IllegalStateException(
                        if (error.message == "Blocked") "Letterboxd demande une vérification dans son navigateur."
                        else "Letterboxd a changé la présentation de sa bibliothèque."
                    )))
                },
            )
        }
    }

    private fun parsePage(raw: String): Boolean {
        val encoded = JSONTokener(raw).nextValue() as? String ?: error("Invalid JavaScript response")
        val result = JSONObject(encoded)
        if (result.optBoolean("blocked")) error("Blocked")
        val items = result.optJSONArray("items") ?: JSONArray()
        for (index in 0 until items.length()) merge(items.getJSONObject(index).toEntry())
        return result.optBoolean("next")
    }

    private fun JSONObject.toEntry(): LocalLetterboxdEntry {
        val slug = getString("slug")
        return LocalLetterboxdEntry(
            slug = slug,
            title = optString("title").ifBlank { slug },
            year = optString("year").ifBlank { null },
            rating10 = if (has("rating") && !isNull("rating")) optInt("rating") else null,
            watchedDate = optString("date").take(10).ifBlank { null },
            hasReview = optBoolean("review"),
            inWatchlist = optBoolean("watchlist"),
        )
    }

    private fun merge(next: LocalLetterboxdEntry) {
        val current = entries[next.slug]
        entries[next.slug] = if (current == null) next else current.copy(
            title = if (current.title == current.slug) next.title else current.title,
            year = current.year ?: next.year,
            rating10 = current.rating10 ?: next.rating10,
            watchedDate = current.watchedDate ?: next.watchedDate,
            hasReview = current.hasReview || next.hasReview,
            inWatchlist = current.inWatchlist || next.inWatchlist,
            verifiedAt = System.currentTimeMillis(),
        )
    }

    private fun finish(result: Result<Int>) {
        if (!importing) return
        importing = false
        webView.stopLoading()
        val callback = onComplete
        onProgress = null
        onComplete = null
        callback?.invoke(result)
    }

    private fun isSignInUrl(url: String): Boolean =
        url.contains("/sign-in", ignoreCase = true) || url.contains("/account/login", ignoreCase = true)

    companion object {
        private const val BASE = "https://letterboxd.com"
        private const val MAX_PAGES = 200
        private const val PAGE_SETTLE_MS = 450L
        private const val REQUEST_DELAY_MS = 700L
        private val USERNAME = Regex("^[A-Za-z0-9_-]{2,40}$")

        private val EXTRACT_PAGE_SCRIPT = """
            (() => {
              const kind = __KIND__;
              const blocked = /just a moment|attention required|verify you are human/i.test(document.title + ' ' + document.body.innerText.slice(0, 300));
              const map = new Map();
              const add = (node, row) => {
                const slug = node.dataset.itemSlug || node.dataset.filmSlug || row?.dataset?.filmSlug || '';
                if (!slug) return;
                const rawTitle = node.dataset.itemName || node.dataset.filmName || node.dataset.itemFullDisplayName || node.querySelector('img')?.alt || slug;
                const title = rawTitle.replace(/\s*\(\d{4}\)\s*$/, '').trim();
                const year = node.dataset.itemYear || node.dataset.filmReleaseYear || (rawTitle.match(/\((\d{4})\)\s*$/)?.[1] || '');
                const scope = row || node.closest('li, tr, article') || node;
                const classes = [node.className, scope.className, ...Array.from(scope.querySelectorAll('[class*="rated-"]')).map(el => el.className)].join(' ');
                const rating = Number(classes.match(/(?:^|\s)rated-(10|[1-9])(?:\s|$)/)?.[1] || 0) || null;
                const date = scope.querySelector('time[datetime]')?.getAttribute('datetime')?.slice(0, 10) || scope.dataset.viewingDate || '';
                map.set(slug, {slug, title, year, rating, date, review: kind === 'reviews', watchlist: kind === 'watchlist'});
              };
              if (kind === 'diary') {
                document.querySelectorAll('tr[data-film-slug], .diary-entry-row, .diary-entry').forEach(row => add(row.querySelector('[data-item-slug], [data-film-slug]') || row, row));
              } else {
                document.querySelectorAll('[data-item-slug], [data-film-slug]').forEach(node => add(node, null));
              }
              return JSON.stringify({blocked, items: Array.from(map.values()), next: Boolean(document.querySelector('a[rel="next"], .paginate-next a, a.next'))});
            })()
        """.trimIndent()
    }
}
