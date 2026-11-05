package com.bobyl.tv.data

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import com.bobyl.tv.data.database.LocalFileRecord
import com.bobyl.tv.data.database.LocalFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException

/** A failed/partial directory scan must never erase records for files that were not inspected. */
internal class FileIndexController(private val store: LocalFileStore) {
    suspend fun record(record: LocalFileRecord) = store.upsertFile(record)
    suspend fun snapshot(kind: String, scope: String, rows: List<LocalFileRecord>, complete: Boolean) {
        require(rows.all { it.kind == kind && it.scope == scope })
        if (complete) store.reconcileFileScope(kind, scope, rows) else rows.forEach { store.upsertFile(it) }
    }
    suspend fun records(kind: String): List<LocalFileRecord> = store.listFiles(kind)
}

internal enum class DownloadFileAvailability { Readable, Missing, Inaccessible }

internal fun probeDownloadedFile(openReadOnly: () -> Closeable?): DownloadFileAvailability = try {
    openReadOnly()?.use { DownloadFileAvailability.Readable } ?: DownloadFileAvailability.Missing
} catch (_: FileNotFoundException) { DownloadFileAvailability.Missing }
catch (_: SecurityException) { DownloadFileAvailability.Inaccessible }
catch (_: IOException) { DownloadFileAvailability.Inaccessible }
catch (_: IllegalArgumentException) { DownloadFileAvailability.Inaccessible }

internal fun indexedDownloadState(status: Int, readableUri: String,
    probe: () -> DownloadFileAvailability): String = when (status) {
    DownloadManager.STATUS_PENDING -> "queued"
    DownloadManager.STATUS_RUNNING -> "running"
    DownloadManager.STATUS_PAUSED -> "paused"
    DownloadManager.STATUS_SUCCESSFUL -> if (readableUri.isBlank()) "missing" else when (probe()) {
        DownloadFileAvailability.Readable -> "ready"
        DownloadFileAvailability.Missing -> "missing"
        DownloadFileAvailability.Inaccessible -> "inaccessible"
    }
    DownloadManager.STATUS_FAILED -> "failed"
    else -> "unknown"
}

internal fun localFileMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "apk" -> "application/vnd.android.package-archive"
    "srt" -> "application/x-subrip"
    "vtt" -> "text/vtt"
    "ass", "ssa" -> "text/x-ssa"
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> "application/octet-stream"
}

/** File contents stay in their original folders. This index only records verified observations. */
internal object LocalFileIndex {
    private data class Installed(val context: Application, val controller: FileIndexController)
    @Volatile private var installed: Installed? = null
    internal fun resetForTests() { installed = null }
    private const val MaximumScanEntries = 512
    private const val DownloadScope = "android-download-manager"
    private const val SubtitleScope = "private-subtitles"
    private const val ApkScope = "private-updates"

    fun install(context: Context, store: LocalFileStore) {
        val application = requireNotNull(context.applicationContext as? Application) {
            "File index requires an application context"
        }
        installed = Installed(application, FileIndexController(store))
    }

    suspend fun queuedDownload(id: Long, title: String, path: String, mimeType: String, sourceUrl: String,
        filmId: String? = null, provider: String? = null) {
        val current = installed ?: return
        observe {
            val now = System.currentTimeMillis()
            current.controller.record(LocalFileRecord("download:$id", "download", DownloadScope, filmId, provider, null,
                path, "", title, mimeType, -1, 0, "queued", id, PersistentCacheController.digest(sourceUrl), now))
        }
    }

    /** Reading the registry refreshes DownloadManager state instead of trusting an old queued flag. */
    suspend fun files(kind: String): List<LocalFileRecord> = withContext(Dispatchers.IO) {
        val current = installed ?: return@withContext emptyList()
        when (kind) {
            "download" -> observe { reconcileDownloads(current) }
            "cached-subtitle" -> reconcileCachedSubtitles()
            "apk" -> observe { reconcilePrivate(current, "apk", ApkScope, File(current.context.cacheDir, "updates")) { it.name == "production.apk" } }
        }
        current.controller.records(kind)
    }

    suspend fun cachedSubtitle(file: File, sourceUrl: String) {
        rememberFile(file, "cached-subtitle", SubtitleScope, "ready", sourceHash = PersistentCacheController.digest(sourceUrl))
    }

    suspend fun verifiedApk(file: File, sourceUrl: String) {
        rememberFile(file, "apk", ApkScope, "verified", sourceHash = PersistentCacheController.digest(sourceUrl))
    }

    private suspend fun rememberFile(file: File, kind: String, scope: String, status: String,
        filmId: String? = null, sourceHash: String? = null) {
        val current = installed ?: return
        observe {
            current.controller.record(record(current.context, file, kind, scope, status, filmId, sourceHash))
        }
    }

    suspend fun customSubtitles(filmId: String, files: List<File>) {
        val current = installed ?: return
        observe {
            val scope = "film:$filmId"
            val rows = files.take(MaximumScanEntries).map { record(current.context, it, "custom-subtitle", scope, "ready", filmId) }
            current.controller.snapshot("custom-subtitle", scope, rows, complete = files.size <= MaximumScanEntries)
        }
    }

    /** Called after database readiness. Does not walk shared storage or request any permissions. */
    suspend fun reconcile() {
        val current = installed ?: return
        observe {
            reconcilePrivate(current, "cached-subtitle", SubtitleScope, File(current.context.cacheDir, "subtitles")) {
                CustomSubtitles.supported(it.name) && it.length() <= 4 * 1024 * 1024
            }
            reconcilePrivate(current, "apk", ApkScope, File(current.context.cacheDir, "updates")) { it.name == "production.apk" }
            reconcileDownloads(current)
        }
    }

    private suspend fun observe(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // DownloadManager/files remain authoritative; retry observation on the next bounded scan.
            android.util.Log.w("BobylStorage", "File index reconciliation deferred: ${failure.javaClass.simpleName}")
        }
    }

    suspend fun reconcileCachedSubtitles() {
        val current = installed ?: return
        observe {
            reconcilePrivate(current, "cached-subtitle", SubtitleScope, File(current.context.cacheDir, "subtitles")) {
                CustomSubtitles.supported(it.name) && it.length() <= 4 * 1024 * 1024
            }
        }
    }

    private suspend fun reconcilePrivate(current: Installed, kind: String, scope: String, directory: File,
        accepts: (File) -> Boolean) {
        val files = runInterruptible(Dispatchers.IO) {
            if (!directory.exists()) emptyList() else checkNotNull(directory.listFiles()) { "Не удалось проверить файлы приложения" }
                .filter { it.isFile && accepts(it) }
        }
        val previous = current.controller.records(kind).associateBy { it.path }
        val rows = files.take(MaximumScanEntries).map { file ->
            val old = previous[file.absolutePath]
            val status = if (kind != "apk") "ready" else if (old?.status == "verified" &&
                old.sizeBytes == file.length() && old.modifiedAtMs == file.lastModified()) "verified" else "present"
            record(current.context, file, kind, scope, status, sourceHash = old?.sourceHash)
        }
        current.controller.snapshot(kind, scope, rows, complete = files.size <= MaximumScanEntries)
    }

    private suspend fun reconcileDownloads(current: Installed) {
        val manager = current.context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val previous = current.controller.records("download").associateBy { it.downloadId }
        val snapshot = runInterruptible(Dispatchers.IO) {
            manager.query(DownloadManager.Query())?.use { cursor ->
                val rows = mutableListOf<LocalFileRecord>()
                while (rows.size < MaximumScanEntries && cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val old = previous[id]
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val localUri = cursor.text(DownloadManager.COLUMN_LOCAL_URI)
                    val readyUri = if (status == DownloadManager.STATUS_SUCCESSFUL) manager.getUriForDownloadedFile(id)?.toString().orEmpty() else localUri
                    val sourceUrl = cursor.text(DownloadManager.COLUMN_URI)
                    val state = indexedDownloadState(status, readyUri) {
                        probeDownloadedFile { current.context.contentResolver.openFileDescriptor(Uri.parse(readyUri), "r") }
                    }
                    val path = runCatching { URI(localUri).takeIf { it.scheme == "file" }?.path }.getOrNull().orEmpty()
                    rows += LocalFileRecord("download:$id", "download", DownloadScope, old?.filmId, old?.provider, old?.owner, path.ifBlank { old?.path.orEmpty() },
                        readyUri, cursor.text(DownloadManager.COLUMN_TITLE), cursor.text(DownloadManager.COLUMN_MEDIA_TYPE),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)), state, id,
                        sourceUrl.takeIf(String::isNotBlank)?.let(PersistentCacheController::digest) ?: old?.sourceHash, System.currentTimeMillis())
                }
                rows to (cursor.count <= MaximumScanEntries)
            }
        } ?: return
        current.controller.snapshot("download", DownloadScope, snapshot.first, snapshot.second)
    }

    private fun Cursor.text(column: String): String = getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }.orEmpty()

    private fun record(context: Context, file: File, kind: String, scope: String, status: String,
        filmId: String? = null, sourceHash: String? = null): LocalFileRecord {
        check(file.isFile && file.canRead()) { "Файл недоступен" }
        val path = file.canonicalPath
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = localFileMimeType(file.name)
        return LocalFileRecord("$kind:${PersistentCacheController.digest(path)}", kind, scope, filmId, null, null,
            path, uri.toString(), file.name, mime, file.length(), file.lastModified(), status, null, sourceHash, System.currentTimeMillis())
    }
}
