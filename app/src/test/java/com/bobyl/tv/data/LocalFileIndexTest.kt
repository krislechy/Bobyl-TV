package com.bobyl.tv.data

import com.bobyl.tv.data.database.LocalFileRecord
import com.bobyl.tv.data.database.LocalFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.FileNotFoundException

class LocalFileIndexTest {
    private fun row(id: String, kind: String = "custom-subtitle", scope: String = "film:12", status: String = "ready") =
        LocalFileRecord(id, kind, scope, "12", null, null, "/files/$id", "content://test/$id", id, "text/vtt", 50, 100,
            status, null, null, 100)
    private class Store : LocalFileStore {
        val rows = linkedMapOf<String, LocalFileRecord>()
        var replacements = 0
        override suspend fun upsertFile(entry: LocalFileRecord) { rows[entry.id] = entry }
        override suspend fun listFiles(kind: String) = rows.values.filter { it.kind == kind }
        override suspend fun removeFile(id: String) { rows.remove(id) }
        override suspend fun reconcileFileScope(kind: String, scope: String, rows: List<LocalFileRecord>) {
            replacements++
            this.rows.entries.removeAll { it.value.kind == kind && it.value.scope == scope }
            rows.forEach { this.rows[it.id] = it }
        }
    }

    @Test fun partialOrPermissionLimitedScansDoNotEraseUnseenFiles() = runTest {
        val store = Store()
        val index = FileIndexController(store)
        index.record(row("old"))
        index.snapshot("custom-subtitle", "film:12", listOf(row("new")), complete = false)
        assertEquals(setOf("old", "new"), store.rows.keys)
        assertEquals(0, store.replacements)
    }

    @Test fun completeScanReconcilesOnlyItsKindAndFilmScope() = runTest {
        val store = Store()
        val index = FileIndexController(store)
        index.record(row("removed")); index.record(row("other", scope = "film:99")); index.record(row("apk", kind = "apk"))
        index.snapshot("custom-subtitle", "film:12", emptyList(), complete = true)
        assertEquals(setOf("other", "apk"), store.rows.keys)
    }

    @Test fun rejectsCrossScopeReplacementBeforeTouchingTheDatabase() = runTest {
        val store = Store()
        try { FileIndexController(store).snapshot("custom-subtitle", "film:12", listOf(row("wrong", scope = "film:99")), true); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.rows.isEmpty())
        assertEquals(0, store.replacements)
    }

    @Test fun queuedFailedAndMissingDownloadsCannotBecomeReadyByBeingIndexed() {
        val shouldNotProbe: () -> DownloadFileAvailability = { error("Unfinished/missing downloads must not be opened") }
        assertEquals("queued", indexedDownloadState(1, "file:///a.mp4", shouldNotProbe))
        assertEquals("running", indexedDownloadState(2, "file:///a.mp4", shouldNotProbe))
        assertEquals("paused", indexedDownloadState(4, "file:///a.mp4", shouldNotProbe))
        assertEquals("missing", indexedDownloadState(8, "", shouldNotProbe))
        assertEquals("ready", indexedDownloadState(8, "content://downloads/1") { DownloadFileAvailability.Readable })
        assertEquals("failed", indexedDownloadState(16, "content://downloads/1", shouldNotProbe))
    }

    @Test fun successfulDownloadUriWithoutAReadableFileIsNotReady() {
        assertEquals("missing", indexedDownloadState(8, "content://downloads/1") {
            probeDownloadedFile { throw FileNotFoundException("gone") }
        })
        assertEquals("inaccessible", indexedDownloadState(8, "content://downloads/1") {
            probeDownloadedFile { throw SecurityException("denied") }
        })
    }

    @Test fun readOnlyProbeClosesItsDescriptor() {
        var closes = 0
        assertEquals(DownloadFileAvailability.Readable, probeDownloadedFile { Closeable { closes++ } })
        assertEquals(1, closes)
        assertEquals(DownloadFileAvailability.Missing, probeDownloadedFile { null })
    }

    @Test fun fileMetadataDescribesOriginalFormatsWithoutRelabelingThemAsMp4OrSrt() {
        assertEquals("video/x-matroska", localFileMimeType("movie.mkv"))
        assertEquals("text/x-ssa", localFileMimeType("track.ASS"))
        assertEquals("text/vtt", localFileMimeType("track.vtt"))
        assertEquals("application/vnd.android.package-archive", localFileMimeType("production.apk"))
    }
}
