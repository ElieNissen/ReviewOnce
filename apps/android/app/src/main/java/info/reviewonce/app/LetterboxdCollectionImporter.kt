package info.reviewonce.app

import android.webkit.CookieManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class LetterboxdCollectionImporter(
    private val store: LocalCollectionStore,
    private val userAgent: String,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val importing = AtomicBoolean(false)
    private val networkUserAgent = userAgent.replace(ANDROID_UA_SUFFIX, "")

    fun import(username: String, onProgress: (String) -> Unit, onComplete: (Result<Int>) -> Unit) {
        if (!importing.compareAndSet(false, true)) {
            onComplete(Result.failure(IllegalStateException("Une actualisation est déjà en cours.")))
            return
        }
        executor.execute {
            val result = try {
                runCatching {
                    require(USERNAME.matches(username)) { "Nom de profil Letterboxd invalide" }
                    val entries = linkedMapOf<String, LocalLetterboxdEntry>()
                    scan("$BASE/$username/films/", "collection", onProgress) { document ->
                        parsePosters(document).forEach { merge(entries, it) }
                    }
                    scan("$BASE/$username/watchlist/", "watchlist", onProgress) { document ->
                        parsePosters(document).forEach { merge(entries, it.copy(inWatchlist = true)) }
                    }
                    scan("$BASE/$username/films/reviews/", "critiques", onProgress) { document ->
                        parsePosters(document).forEach { merge(entries, it.copy(hasReview = true)) }
                    }
                    scan("$BASE/$username/films/diary/", "journal", onProgress) { document ->
                        parseDiary(document).forEach { merge(entries, it) }
                    }
                    check(entries.isNotEmpty()) { "Aucun film trouvé. Vérifie le profil Letterboxd." }
                    store.replaceCollection(entries.values)
                    entries.size
                }
            } finally {
                importing.set(false)
            }
            onComplete(result)
        }
    }

    fun close() = executor.shutdownNow()

    private fun scan(baseUrl: String, label: String, onProgress: (String) -> Unit, consume: (Document) -> Unit) {
        var previousUrl = BASE
        for (page in 1..MAX_PAGES) {
            onProgress("Lecture $label · page $page")
            val url = if (page == 1) baseUrl else "${baseUrl}page/$page/"
            val document = fetchWithRetry(url, previousUrl) { seconds ->
                onProgress("Letterboxd temporise · nouvel essai dans ${seconds}s")
            }
            consume(document)
            if (!hasNextPage(document)) break
            previousUrl = url
            Thread.sleep(REQUEST_DELAY_MS + Random.nextLong(0, REQUEST_JITTER_MS))
        }
    }

    private fun fetchWithRetry(url: String, referer: String, onRetry: (Long) -> Unit): Document {
        for (attempt in 0..RETRY_DELAYS_SECONDS.size) {
            try {
                return fetch(url, referer)
            } catch (error: LetterboxdHttpException) {
                if (error.status !in RETRYABLE_STATUSES || attempt == RETRY_DELAYS_SECONDS.size) {
                    throw IllegalStateException(
                        if (error.status in RETRYABLE_STATUSES) {
                            "Letterboxd limite encore la lecture. Attends quelques minutes puis réessaie."
                        } else {
                            "Letterboxd a répondu ${error.status}"
                        },
                    )
                }
                val delay = RETRY_DELAYS_SECONDS[attempt]
                onRetry(delay)
                Thread.sleep(delay * 1_000)
            }
        }
        error("Lecture Letterboxd impossible")
    }

    private fun fetch(url: String, referer: String): Document {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", networkUserAgent)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        connection.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Upgrade-Insecure-Requests", "1")
        connection.setRequestProperty("Sec-Fetch-Site", "same-origin")
        connection.setRequestProperty("Sec-Fetch-Mode", "navigate")
        connection.setRequestProperty("Sec-Fetch-Dest", "document")
        CookieManager.getInstance().getCookie(BASE)?.let { connection.setRequestProperty("Cookie", it) }
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            throw LetterboxdHttpException(status)
        }
        connection.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(BASE, it) }
        val document = connection.inputStream.bufferedReader().use { Jsoup.parse(it.readText(), url) }
        connection.disconnect()
        check(document.selectFirst("form[action*='sign-in'], input[name='password']") == null) {
            "Ta session Letterboxd a expiré. Reconnecte ton compte."
        }
        return document
    }

    private fun parsePosters(document: Document): List<LocalLetterboxdEntry> = document
        .select("[data-item-slug], [data-film-slug]")
        .mapNotNull(::posterEntry)
        .distinctBy { it.slug }

    private fun posterEntry(element: Element): LocalLetterboxdEntry? {
        val slug = element.attr("data-item-slug").ifBlank { element.attr("data-film-slug") }
        if (slug.isBlank()) return null
        val rawTitle = element.attr("data-item-name").ifBlank {
            element.attr("data-film-name").ifBlank { element.attr("data-item-full-display-name") }
        }
        val year = element.attr("data-item-year").ifBlank {
            element.attr("data-film-release-year").ifBlank { YEAR.find(rawTitle)?.value.orEmpty() }
        }
        val title = rawTitle.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim().ifBlank { slug }
        val classes = generateSequence(element) { it.parent() }.take(4).joinToString(" ") { it.className() }
        val rating = RATING.find(classes)?.groupValues?.get(1)?.toIntOrNull()
        return LocalLetterboxdEntry(slug = slug, title = title, year = year.ifBlank { null }, rating10 = rating)
    }

    private fun parseDiary(document: Document): List<LocalLetterboxdEntry> = document
        .select("tr[data-film-slug], .diary-entry-row, .diary-entry")
        .mapNotNull { row ->
            val poster = row.selectFirst("[data-item-slug], [data-film-slug]") ?: row
            val base = posterEntry(poster) ?: return@mapNotNull null
            val date = row.selectFirst("time[datetime]")?.attr("datetime")?.take(10)
                ?: row.attr("data-viewing-date").take(10).ifBlank { null }
            base.copy(watchedDate = date)
        }
        .distinctBy { it.slug }

    private fun merge(target: MutableMap<String, LocalLetterboxdEntry>, next: LocalLetterboxdEntry) {
        val current = target[next.slug]
        target[next.slug] = if (current == null) next else current.copy(
            title = if (current.title == current.slug) next.title else current.title,
            year = current.year ?: next.year,
            rating10 = current.rating10 ?: next.rating10,
            watchedDate = current.watchedDate ?: next.watchedDate,
            hasReview = current.hasReview || next.hasReview,
            inWatchlist = current.inWatchlist || next.inWatchlist,
            verifiedAt = System.currentTimeMillis(),
        )
    }

    private fun hasNextPage(document: Document) = document.selectFirst("a[rel=next], .paginate-next a, a.next") != null

    companion object {
        private const val BASE = "https://letterboxd.com"
        private const val MAX_PAGES = 200
        private const val REQUEST_DELAY_MS = 1_500L
        private const val REQUEST_JITTER_MS = 700L
        private val RETRY_DELAYS_SECONDS = longArrayOf(5, 20, 60)
        private val RETRYABLE_STATUSES = setOf(403, 429, 500, 502, 503, 504)
        private val ANDROID_UA_SUFFIX = Regex("\\s+ReviewOnceAndroid/\\S+")
        private val USERNAME = Regex("^[A-Za-z0-9_-]{2,40}$")
        private val YEAR = Regex("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)")
        private val RATING = Regex("(?:^|\\s)rated-(10|[1-9])(?:\\s|$)")
    }

    private class LetterboxdHttpException(val status: Int) : IllegalStateException()
}
