package info.reviewonce.app

import java.io.InputStream
import java.util.zip.ZipInputStream

class LetterboxdExportImporter(private val store: LocalCollectionStore) {
    fun import(input: InputStream): Int {
        val entries = linkedMapOf<String, LocalLetterboxdEntry>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                if (!item.isDirectory && item.name.endsWith(".csv", ignoreCase = true)) {
                    parseCsv(item.name.substringAfterLast('/').lowercase(), zip.readBytes().toString(Charsets.UTF_8), entries)
                }
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "L’export Letterboxd ne contient aucun film." }
        store.replaceCollection(entries.values)
        return entries.size
    }

    private fun parseCsv(
        fileName: String,
        content: String,
        target: MutableMap<String, LocalLetterboxdEntry>,
    ) {
        val rows = csvRows(content)
        if (rows.size < 2) return
        val headers = rows.first().map { it.removePrefix("\uFEFF").trim() }
        rows.drop(1).forEach { values ->
            val row = headers.mapIndexed { index, header -> header to values.getOrElse(index) { "" } }.toMap()
            val uri = row["Letterboxd URI"].orEmpty()
            val slug = uri.substringAfter("/film/", "").substringBefore('/').ifBlank {
                row["Name"].orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            }
            if (slug.isBlank()) return@forEach
            val rating = row["Rating"]?.toDoubleOrNull()?.let { (it * 2).toInt() }
            val watchedDate = row["Watched Date"].orEmpty().takeIf { it.isNotBlank() }
            val next = LocalLetterboxdEntry(
                slug = slug,
                title = row["Name"].orEmpty().ifBlank { slug },
                year = row["Year"].orEmpty().takeIf { it.isNotBlank() },
                rating10 = rating,
                watchedDate = watchedDate,
                hasReview = fileName == "reviews.csv",
                inWatchlist = fileName == "watchlist.csv",
            )
            target[slug] = merge(target[slug], next)
        }
    }

    private fun merge(current: LocalLetterboxdEntry?, next: LocalLetterboxdEntry): LocalLetterboxdEntry =
        if (current == null) next else current.copy(
            title = if (current.title == current.slug) next.title else current.title,
            year = current.year ?: next.year,
            rating10 = current.rating10 ?: next.rating10,
            watchedDate = current.watchedDate ?: next.watchedDate,
            hasReview = current.hasReview || next.hasReview,
            inWatchlist = current.inWatchlist || next.inWatchlist,
            verifiedAt = System.currentTimeMillis(),
        )

    private fun csvRows(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                quoted && char == '"' && content.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row.add(field.toString())
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && content.getOrNull(index + 1) == '\n') index++
                    row.add(field.toString())
                    field.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }
}
