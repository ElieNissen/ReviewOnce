package info.reviewonce.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalLetterboxdEntry(
    val tmdbId: String,
    val rating10: Int?,
    val watchedDate: String?,
    val reviewHash: String?,
    val inWatchlist: Boolean,
    val verifiedAt: Long,
)

class LocalCollectionStore(context: Context) : SQLiteOpenHelper(context, "reviewonce.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE letterboxd_entries (
                tmdb_id TEXT PRIMARY KEY,
                rating_10 INTEGER,
                watched_date TEXT,
                review_hash TEXT,
                in_watchlist INTEGER NOT NULL DEFAULT 0,
                verified_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsert(entry: LocalLetterboxdEntry) {
        writableDatabase.execSQL(
            """
            INSERT INTO letterboxd_entries
                (tmdb_id, rating_10, watched_date, review_hash, in_watchlist, verified_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(tmdb_id) DO UPDATE SET
                rating_10 = excluded.rating_10,
                watched_date = excluded.watched_date,
                review_hash = excluded.review_hash,
                in_watchlist = excluded.in_watchlist,
                verified_at = excluded.verified_at
            """.trimIndent(),
            arrayOf(entry.tmdbId, entry.rating10, entry.watchedDate, entry.reviewHash, if (entry.inWatchlist) 1 else 0, entry.verifiedAt)
        )
    }

    fun hasCompletedAction(actionKey: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM sync_actions WHERE action_key = ? LIMIT 1",
        arrayOf(actionKey)
    ).use { it.moveToFirst() }

    fun markActionCompleted(actionKey: String, tmdbId: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO sync_actions (action_key, tmdb_id, completed_at) VALUES (?, ?, ?)",
            arrayOf(actionKey, tmdbId, System.currentTimeMillis())
        )
    }
}
