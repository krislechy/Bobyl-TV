package com.bobyl.tv.data.database

import com.bobyl.tv.data.BookmarkCategory
import com.bobyl.tv.data.BrowseLink
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.FilmDetails
import com.bobyl.tv.data.MediaTrack
import com.bobyl.tv.data.PlaybackInfo
import com.bobyl.tv.data.Person
import com.bobyl.tv.data.ScheduleEntry
import com.bobyl.tv.data.StreamChoices
import com.bobyl.tv.data.WatchHistory
import com.bobyl.tv.data.WatchRecord
import org.junit.Assert.*
import org.junit.Test

class FilmMetadataStoreTest : RoomTestBase() {
    private val provider = "https://metadata.example/"
    private val poster = "${provider}images/poster.jpg"
    private fun film(title: String = "Title") = CatalogFilm("42", "${provider}films/42-alias.html", title, poster, "2026", "badge", "film", "8.2")
    @Test fun publicMetadataSurvivesReopenAndUpsertCannotDeleteHistory() = io {
        val history = WatchHistory.withDatabase(database, provider)
        history.put(WatchRecord("42", film().url, "", "", "1", "0", "0", 123))
        var store = FilmMetadataStore(database)
        store.rememberFilms(provider, listOf(film()))
        store.rememberFilms(provider, listOf(film("Updated")))
        reopen(); store = FilmMetadataStore(database)
        assertEquals(film("Updated"), store.readFilm(provider, "42"))
        val restored = WatchHistory.withDatabase(database, provider).all().single()
        assertEquals("Updated", restored.title); assertEquals(poster, restored.poster)
        assertEquals(123L, restored.positionMs)
        assertNull(store.readFilm("https://other.example/", "42"))
    }
    @Test fun detailArchiveExcludesPlaybackAndAccountFieldsAndProvidesHistoryLabels() = io {
        val store = FilmMetadataStore(database)
        val detail = FilmDetails("Public title", "Original", poster, "Description", listOf("2026"),
            playback = PlaybackInfo("42", true, emptyList(), StreamChoices(listOf(MediaTrack("HD", "https://stream.example/?token=private")), emptyList())),
            canRate = true, bookmarks = listOf(BookmarkCategory("1", "Private category", checked = true)),
            schedule = listOf(ScheduleEntry("Season 1", "Episode 1", "episode-1", true, "1", "Pilot", "2026")),
            id = "42", personalRating = 9)
        store.rememberDetails(provider, detail)
        val raw = database.storage().details(provider, "42")!!.payload
        assertFalse(raw.contains("private")); assertFalse(raw.contains("Private category"))
        val restored = store.readDetails(provider, "42")!!
        assertEquals(detail.title, restored.title); assertEquals(detail.description, restored.description)
        assertNull(restored.playback); assertNull(restored.personalRating)
        assertFalse(restored.canRate); assertTrue(restored.bookmarks.isEmpty()); assertFalse(restored.schedule.single().watched)
        WatchHistory.withDatabase(database, provider).put(WatchRecord("42", film().url, "", "", "", "", "", 0))
        assertEquals("Public title", WatchHistory.withDatabase(database, provider).all().single().title)
    }
    @Test fun completeHistorySnapshotIsNeverOverwrittenByNewMetadata() = io {
        val original = WatchRecord("42", film().url, "Saved title", "saved-poster", "", "", "", 7)
        WatchHistory.withDatabase(database, provider).put(original)
        FilmMetadataStore(database).rememberFilms(provider, listOf(film("Different")))
        assertEquals(original, WatchHistory.withDatabase(database, provider).all().single())
    }

    @Test fun sensitiveUrlsNeverReachFilmOrNestedDetailStorageWhilePublicMetadataStillPersists() = io {
        val store = FilmMetadataStore(database)
        val secret = "privacy-sentinel"
        val signed = "https://metadata.example/image.jpg?signature=$secret"
        val encoded = "https://metadata.example/person?%74oken=$secret"
        val privateFilm = film().copy(url = "${provider}films/42-title.html?token=$secret")
        val privatePoster = film().copy(id = "43", url = "${provider}films/43-title.html", poster = signed)
        val publicFilm = film().copy(id = "44", url = "${provider}films/44-title.html?page=1")
        store.rememberFilms(provider, listOf(privateFilm, privatePoster, publicFilm))
        assertNull(store.readFilm(provider, "42")); assertNull(store.readFilm(provider, "43"))
        assertEquals(publicFilm, store.readFilm(provider, "44"))
        val originalHistory = WatchRecord("42", privateFilm.url, "User snapshot", signed, "voice", "2", "3", 123)
        WatchHistory.withDatabase(database, provider).put(originalHistory)
        assertEquals(originalHistory, WatchHistory.withDatabase(database, provider).find("42"))
        val detail = FilmDetails("Public title", "Original", signed, "Public description", emptyList(),
            actors = listOf(Person("Actor", signed, "actor", encoded)),
            creators = listOf(Person("Creator", "https://metadata.example/creator.jpg", "director", "https://user:$secret@metadata.example/person")),
            collection = listOf(privateFilm), related = listOf(publicFilm),
            fullPoster = "https://metadata.example/full.jpg?access_token=$secret", id = "42",
            links = listOf(BrowseLink("Private", encoded), BrowseLink("Public", "https://metadata.example/genre/drama/")))
        store.rememberDetails(provider, detail)
        val raw = database.storage().details(provider, "42")!!.payload
        assertFalse(raw.contains(secret))
        database.openHelper.readableDatabase.query("SELECT url, poster FROM films").use { cursor ->
            while (cursor.moveToNext()) { assertFalse(cursor.getString(0).contains(secret)); assertFalse(cursor.getString(1).contains(secret)) }
        }
        reopen()
        val restored = FilmMetadataStore(database).readDetails(provider, "42")!!
        assertEquals("Public description", restored.description)
        assertTrue(restored.poster.isBlank()); assertTrue(restored.fullPoster.isBlank())
        assertTrue(restored.actors.single().photo.isBlank()); assertTrue(restored.actors.single().url.isBlank())
        assertTrue(restored.creators.single().url.isBlank()); assertFalse(restored.creators.single().photo.isBlank())
        assertTrue(restored.collection.isEmpty()); assertEquals(listOf(publicFilm), restored.related)
        assertEquals(listOf(BrowseLink("Public", "https://metadata.example/genre/drama/")), restored.links)
    }
}
