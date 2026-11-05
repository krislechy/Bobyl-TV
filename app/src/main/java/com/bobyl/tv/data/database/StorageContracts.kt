package com.bobyl.tv.data.database

/** Durable trust state is quota-counted but must never be evicted as ordinary cache content. */
internal const val CacheQuarantineKey = "__quarantine_v1"

/** Serialized, versioned DTOs only. Neither cookies nor executable HTML belong here. */
internal data class CacheRecord(
    val key: String,
    val provider: String,
    val owner: String,
    val operation: String,
    val payload: String,
    val codecVersion: Int,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val sizeBytes: Long,
)

/** Implementations perform disk work off Main; the caller owns request/session epoch fencing. */
internal interface PersistentCacheStore {
    suspend fun cacheGet(key: String): CacheRecord?
    suspend fun cachePut(record: CacheRecord, maxEntries: Int, maxBytes: Long)
    suspend fun cacheRemove(key: String)
    suspend fun cacheClear()
}

/** File identity and verified metadata, never file contents or authenticated source URLs. */
internal data class LocalFileRecord(
    val id: String,
    val kind: String,
    val scope: String,
    val filmId: String?,
    val provider: String?,
    val owner: String?,
    val path: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val status: String,
    val downloadId: Long?,
    val sourceHash: String?,
    val updatedAtMs: Long,
)

internal interface LocalFileStore {
    suspend fun upsertFile(entry: LocalFileRecord)
    suspend fun listFiles(kind: String): List<LocalFileRecord>
    suspend fun removeFile(id: String)
    /** Replace metadata only after a complete scan. This never deletes physical files. */
    suspend fun reconcileFileScope(kind: String, scope: String, rows: List<LocalFileRecord>)
}
