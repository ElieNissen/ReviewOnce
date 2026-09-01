package info.reviewonce.app

import org.json.JSONArray
import org.json.JSONObject

data class SyncAction(
    val key: String,
    val tmdbId: String,
    val title: String,
    val rating10: Int?,
    val watchedDate: String?,
    val review: String?,
    val missing: Set<String>,
    val watchlist: Boolean,
) {
    companion object {
        fun parse(payload: String): List<SyncAction> {
            val root = JSONObject(payload)
            val actions = root.getJSONArray("actions")
            return buildList {
                for (index in 0 until actions.length()) add(actions.getJSONObject(index).toAction())
            }
        }

        private fun JSONObject.toAction(): SyncAction {
            val missingJson = optJSONArray("missing") ?: JSONArray()
            val missing = buildSet { for (index in 0 until missingJson.length()) add(missingJson.getString(index)) }
            return SyncAction(
                key = getString("key"),
                tmdbId = getString("tmdbId"),
                title = getString("title"),
                rating10 = if (isNull("rating10")) null else optInt("rating10"),
                watchedDate = optString("watchedDate").ifBlank { null },
                review = optString("review").ifBlank { null },
                missing = missing,
                watchlist = optBoolean("watchlist"),
            )
        }
    }
}
