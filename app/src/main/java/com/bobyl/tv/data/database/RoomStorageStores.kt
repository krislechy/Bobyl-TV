package com.bobyl.tv.data.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RoomStorageStores(private val database: BobylDatabase,
    private val clock: () -> Long = System::currentTimeMillis) : PersistentCacheStore, LocalFileStore {
    private val dao get() = database.storage()

    override suspend fun cacheGet(key: String): CacheRecord? = withContext(Dispatchers.IO) {
        database.runInTransaction<CacheRecord?> {
            val row = dao.cache(key) ?: return@runInTransaction null
            dao.touchCache(key, clock())
            CacheRecord(row.key, row.provider, row.owner, row.operation, row.payload, row.codecVersion,
                row.createdAtMs, row.expiresAtMs, row.sizeBytes)
        }
    }

    override suspend fun cachePut(record: CacheRecord, maxEntries: Int, maxBytes: Long) = withContext(Dispatchers.IO) {
        require(maxEntries in 1..128 && maxBytes in 1..16L * 1024 * 1024)
        val bytes = record.payload.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > minOf(maxBytes, 4L * 1024 * 1024)) return@withContext
        database.runInTransaction {
            dao.putCache(CacheRow(record.key, record.provider, record.owner, record.operation, record.payload,
                record.codecVersion, record.createdAtMs, record.expiresAtMs, bytes, clock()))
            val rows = dao.cacheEvictionOrder()
            var remainingBytes = rows.sumOf { it.sizeBytes }
            var remainingCount = rows.size
            for (row in rows) {
                if (remainingCount <= maxEntries && remainingBytes <= maxBytes) break
                if (row.key == CacheQuarantineKey) continue
                dao.removeCache(row.key)
                remainingBytes -= row.sizeBytes
                remainingCount--
            }
        }
    }

    override suspend fun cacheRemove(key: String) = withContext(Dispatchers.IO) { dao.removeCache(key) }
    override suspend fun cacheClear() = withContext(Dispatchers.IO) { dao.clearCache() }
    override suspend fun upsertFile(entry: LocalFileRecord) = withContext(Dispatchers.IO) { dao.putFile(entry.toRow()) }
    override suspend fun listFiles(kind: String): List<LocalFileRecord> = withContext(Dispatchers.IO) { dao.files(kind).map { it.toRecord() } }
    override suspend fun removeFile(id: String) = withContext(Dispatchers.IO) { dao.removeFile(id) }
    override suspend fun reconcileFileScope(kind: String, scope: String, rows: List<LocalFileRecord>) = withContext(Dispatchers.IO) {
        require(rows.all { it.kind == kind && it.scope == scope })
        require(rows.map { it.id }.distinct().size == rows.size)
        database.runInTransaction {
            dao.clearFileScope(kind, scope)
            rows.forEach { dao.putFile(it.toRow()) }
        }
    }
}

private fun LocalFileRecord.toRow() = LocalFileRow(id, kind, scope, filmId, provider, owner, path, uri,
    displayName, mimeType, sizeBytes, modifiedAtMs, status, downloadId, sourceHash, updatedAtMs)
private fun LocalFileRow.toRecord() = LocalFileRecord(id, kind, scope, filmId, provider, owner, path, uri,
    displayName, mimeType, sizeBytes, modifiedAtMs, status, downloadId, sourceHash, updatedAtMs)
