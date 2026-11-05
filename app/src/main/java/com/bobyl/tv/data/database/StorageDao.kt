package com.bobyl.tv.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

internal data class CacheEvictionEntry(val key: String, val sizeBytes: Long)

/** Synchronous statements are intentionally used only inside IO-owned facades/transactions. */
@Dao
internal interface StorageDao {
    @Query("SELECT value FROM storage_meta WHERE `key` = :key") fun meta(key: String): String?
    @Upsert fun putMeta(row: StorageMeta)
    @Upsert fun putFilms(rows: List<FilmRow>)
    @Query("SELECT * FROM films WHERE provider = :provider AND filmKey = :filmKey") fun film(provider: String, filmKey: String): FilmRow?
    @Upsert fun putDetails(row: FilmDetailsRow)
    @Query("SELECT * FROM film_details WHERE provider = :provider AND filmKey = :filmKey") fun details(provider: String, filmKey: String): FilmDetailsRow?

    @Query("SELECT * FROM watch_history WHERE provider = :provider ORDER BY updated DESC, rank DESC") fun history(provider: String): List<HistoryRow>
    @Query("SELECT * FROM watch_history WHERE provider = :provider AND recordId = :id LIMIT 1") fun historyRecord(provider: String, id: String): HistoryRow?
    @Query("SELECT * FROM watch_history WHERE provider = :provider AND filmKey = :filmKey ORDER BY updated DESC, rank DESC") fun historyFilm(provider: String, filmKey: String): List<HistoryRow>
    @Query("SELECT COUNT(*) FROM watch_history WHERE provider = :provider") fun historyCount(provider: String): Int
    @Query("SELECT COALESCE(MAX(rank), 0) FROM watch_history WHERE provider = :provider") fun historyRank(provider: String): Long
    @Upsert fun putHistory(row: HistoryRow)
    @Query("DELETE FROM watch_history WHERE provider = :provider AND recordId != :keep AND recordId NOT IN (SELECT recordId FROM watch_history WHERE provider = :provider AND recordId != :keep ORDER BY updated DESC, rank DESC LIMIT 499)")
    fun trimHistory(provider: String, keep: String)
    @Query("UPDATE watch_history SET watched = :watched WHERE provider = :provider AND filmKey = :filmKey") fun setWatched(provider: String, filmKey: String, watched: Boolean)
    @Query("DELETE FROM watch_history WHERE provider = :provider AND recordId = :id") fun removeHistory(provider: String, id: String)
    @Query("DELETE FROM watch_history WHERE provider = :provider") fun clearHistory(provider: String)

    @Query("SELECT value FROM personal_ratings WHERE provider = :provider AND owner = :owner AND filmId = :filmId") fun rating(provider: String, owner: String, filmId: String): Int?
    @Upsert fun putRating(row: PersonalRatingRow)
    @Upsert fun archiveRating(row: LegacyRatingRow)
    @Query("SELECT * FROM legacy_ratings_archive ORDER BY originalKey") fun archivedRatings(): List<LegacyRatingRow>
    @Upsert fun archiveImport(row: LegacyImportRow)
    @Query("SELECT * FROM legacy_import_archive ORDER BY source, originalKey") fun archivedImports(): List<LegacyImportRow>

    @Query("SELECT * FROM playback_selections WHERE filmId = :filmId") fun selection(filmId: String): PlaybackSelectionRow?
    @Upsert fun putSelection(row: PlaybackSelectionRow)

    @Query("SELECT * FROM cache_entries WHERE `key` = :key") fun cache(key: String): CacheRow?
    @Upsert fun putCache(row: CacheRow)
    @Query("UPDATE cache_entries SET lastAccessedAtMs = :now WHERE `key` = :key") fun touchCache(key: String, now: Long)
    @Query("DELETE FROM cache_entries WHERE `key` = :key") fun removeCache(key: String)
    @Query("DELETE FROM cache_entries") fun clearCache()
    @Query("SELECT * FROM cache_entries ORDER BY lastAccessedAtMs ASC, `key` ASC") fun cachesOldestFirst(): List<CacheRow>
    @Query("SELECT `key`, sizeBytes FROM cache_entries ORDER BY lastAccessedAtMs ASC, `key` ASC") fun cacheEvictionOrder(): List<CacheEvictionEntry>

    @Upsert fun putFile(row: LocalFileRow)
    @Query("SELECT * FROM local_files WHERE kind = :kind ORDER BY updatedAtMs DESC, id") fun files(kind: String): List<LocalFileRow>
    @Query("DELETE FROM local_files WHERE id = :id") fun removeFile(id: String)
    @Query("DELETE FROM local_files WHERE kind = :kind AND scope = :scope") fun clearFileScope(kind: String, scope: String)
}
