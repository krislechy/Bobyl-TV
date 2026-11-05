package com.bobyl.tv.data.database

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "storage_meta", primaryKeys = ["key"])
internal data class StorageMeta(val key: String, val value: String)

@Entity(tableName = "films", primaryKeys = ["provider", "filmKey"])
internal data class FilmRow(val provider: String, val filmKey: String, val id: String, val url: String,
    val title: String, val poster: String, val metadata: String, val badge: String, val kind: String,
    val rating: String, val updatedAtMs: Long)

@Entity(tableName = "film_details", primaryKeys = ["provider", "filmKey"])
internal data class FilmDetailsRow(val provider: String, val filmKey: String, val payload: String,
    val codecVersion: Int, val updatedAtMs: Long)

@Entity(tableName = "watch_history", primaryKeys = ["provider", "recordId"],
    indices = [Index(value = ["provider", "filmKey"]), Index(value = ["provider", "updated"])])
internal data class HistoryRow(val provider: String, val recordId: String, val filmKey: String,
    val url: String, val title: String, val poster: String, val voice: String, val season: String,
    val episode: String, val positionMs: Long, val updated: Long, val quality: String,
    val subtitle: String, val playerPackage: String, val decoder: Int, val watched: Boolean, val rank: Long)

@Entity(tableName = "personal_ratings", primaryKeys = ["provider", "owner", "filmId"])
internal data class PersonalRatingRow(val provider: String, val owner: String, val filmId: String, val value: Int)

@Entity(tableName = "legacy_ratings_archive", primaryKeys = ["originalKey"])
internal data class LegacyRatingRow(val originalKey: String, val value: Int)

/** Existing playback preferences were global by film ID; migration preserves that scope. */
@Entity(tableName = "playback_selections", primaryKeys = ["filmId"])
internal data class PlaybackSelectionRow(val filmId: String, val voice: String, val season: String,
    val episode: String, val updatedAtMs: Long)

@Entity(tableName = "legacy_import_archive", primaryKeys = ["source", "originalKey"])
internal data class LegacyImportRow(val source: String, val originalKey: String, val raw: String, val reason: String)

@Entity(tableName = "cache_entries", primaryKeys = ["key"], indices = [Index(value = ["lastAccessedAtMs"])])
internal data class CacheRow(val key: String, val provider: String, val owner: String, val operation: String,
    val payload: String, val codecVersion: Int, val createdAtMs: Long, val expiresAtMs: Long,
    val sizeBytes: Long, val lastAccessedAtMs: Long)

@Entity(tableName = "local_files", primaryKeys = ["id"], indices = [Index(value = ["kind", "scope"])])
internal data class LocalFileRow(val id: String, val kind: String, val scope: String, val filmId: String?,
    val provider: String?, val owner: String?, val path: String, val uri: String, val displayName: String,
    val mimeType: String, val sizeBytes: Long, val modifiedAtMs: Long, val status: String,
    val downloadId: Long?, val sourceHash: String?, val updatedAtMs: Long)
