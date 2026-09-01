package info.reviewonce.app

import android.net.Uri
import android.webkit.WebView
import org.json.JSONObject
import java.util.ArrayDeque

class LetterboxdSyncController(
    private val webView: WebView,
    private val store: LocalCollectionStore,
    private val onStatus: (String) -> Unit,
    private val onFinished: (Int, Int) -> Unit,
) {
    private val queue = ArrayDeque<SyncAction>()
    private var active: SyncAction? = null
    private var successCount = 0
    private var failureCount = 0

    fun start(actions: List<SyncAction>) {
        queue.clear()
        successCount = 0
        failureCount = 0
        actions.filterNot { store.hasCompletedAction(it.key) }.forEach(queue::addLast)
        next()
    }

    fun onPageFinished(url: String): Boolean {
        val action = active ?: return false
        if (!url.startsWith("https://letterboxd.com/film/")) return false
        onStatus("Synchronisation de ${action.title}")
        webView.evaluateJavascript(script(action), null)
        return true
    }

    fun handleResult(uri: Uri): Boolean {
        if (uri.scheme != RESULT_SCHEME) return false
        val action = active ?: return true
        if (uri.getQueryParameter("status") == "success") {
            store.markActionCompleted(action.key, action.tmdbId)
            successCount++
        } else {
            failureCount++
            onStatus(uri.getQueryParameter("message") ?: "Échec pour ${action.title}")
        }
        active = null
        next()
        return true
    }

    private fun next() {
        val next = if (queue.isEmpty()) null else queue.removeFirst()
        if (next == null) {
            onFinished(successCount, failureCount)
            return
        }
        if (next.watchlist) {
            failureCount++
            onStatus("Watchlist non activée dans ce prototype")
            next()
            return
        }
        if (!next.missing.contains("film")) {
            failureCount++
            onStatus("${next.title} existe déjà : mise à jour laissée en attente pour éviter un doublon")
            next()
            return
        }
        active = next
        webView.loadUrl("https://letterboxd.com/tmdb/${next.tmdbId}/")
    }

    private fun script(action: SyncAction): String {
        val actionKey = JSONObject.quote(action.key)
        val rating = action.rating10?.coerceIn(0, 10)?.toString() ?: "0"
        val date = JSONObject.quote(action.watchedDate.orEmpty())
        val review = JSONObject.quote(action.review.orEmpty())
        return """
            (async function(){
              const report=(status,message)=>{location.href='reviewonce-result://sync?status='+encodeURIComponent(status)+'&key='+encodeURIComponent($actionKey)+'&message='+encodeURIComponent(message||'')};
              try {
                const form=document.querySelector('form.js-diary-entry-form');
                const uid=window.__BXD_DATA&&window.__BXD_DATA.viewingable&&window.__BXD_DATA.viewingable.uid;
                const csrf=window.supermodelCSRF;
                if(!form||!uid||!csrf){report('error','Formulaire Letterboxd introuvable ou session expirée');return;}
                form.querySelector('[name="__csrf"]').value=csrf;
                form.querySelector('[name="viewingableUid"]').value=uid;
                const date=$date;
                if(date){form.querySelector('[name="specifiedDate"]').checked=true;form.querySelector('[name="viewingDateStr"]').value=date;}
                form.querySelector('[name="rating"]').value='$rating';
                form.querySelector('[name="review"]').value=$review;
                const response=await fetch(form.action,{method:'POST',body:new FormData(form),credentials:'include',headers:{'X-Requested-With':'XMLHttpRequest'}});
                const body=await response.text();
                if(!response.ok){report('error','Letterboxd a refusé l’enregistrement ('+response.status+')');return;}
                if(/sign[ -]?in|log[ -]?in/i.test(response.url)||/name=["']username/i.test(body)){report('error','Connexion Letterboxd requise');return;}
                report('success','');
              } catch(error) { report('error',String(error&&error.message||error)); }
            })();
        """.trimIndent()
    }

    companion object {
        const val RESULT_SCHEME = "reviewonce-result"
    }
}
