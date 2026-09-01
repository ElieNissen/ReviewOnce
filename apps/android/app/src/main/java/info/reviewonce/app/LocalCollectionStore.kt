package info.reviewonce.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalLetterboxdEntry(
    val slug: String,
    val tmdbId: String? = null,
    val title: String,
    val year: String? = null,
    val rating10: Int? = null,
    val watchedDate: String? = null,
    val hasReview: Boolean = false,
    val inWatchlist: Boolean = false,
    val verifiedAt: Long = System.currentTimeMillis(),
)

class LocalCollectionStore(context: Context) : SQLiteOpenHelper(context, "reviewonce.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE letterboxd_entries (
                slug TEXT PRIMARY KEY,
                tmdb_id TEXT,
                title TEXT NOT NULL,
                year TEXT,
                rating_10 INTEGER,
                watched_date TEXT,
                has_review INTEGER NOT NULL DEFAULT 0,
                in_watchlist INTEGER NOT NULL DEFAULT 0,
                verified_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX letterboxd_entries_tmdb ON letterboxd_entries(tmdb_id)")
        db.execSQL(
            """
            CREATE TABLE sync_actions (
                action_key TEXT PRIMARY KEY,
                tmdb_id TEXT NOT NULL,
                completed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS letterboxd_entries")
        db.execSQL("DROP TABLE IF EXISTS sync_actions")
        onCreate(db)
    }

    fun replaceCollection(entries: Collection<LocalLetterboxdEntry>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("letterboxd_entries", null, null)
            entries.forEach { entry -> writableDatabase.insertOrThrow("letterboxd_entries", null, entry.values()) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun countEntries(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM letterboxd_entries", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun hasCompletedAction(actionKey: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM sync_actions WHERE action_key = ? LIMIT 1",
        arrayOf(actionKey)
    ).use { it.moveToFirst() }

    fun markActionCompleted(actionKey: String, tmdbId: String) {
        val values = ContentValues().apply {
            put("action_key", actionKey)
            put("tmdb_id", tmdbId)
            put("completed_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("sync_actions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun LocalLetterboxdEntry.values() = ContentValues().apply {
        put("slug", slug)
        put("tmdb_id", tmdbId)
        put("title", title)
        put("year", year)
        put("rating_10", rating10)
        put("watched_date", watchedDate)
        put("has_review", if (hasReview) 1 else 0)
        put("in_watchlist", if (inWatchlist) 1 else 0)
        put("verified_at", verifiedAt)
    }
}
