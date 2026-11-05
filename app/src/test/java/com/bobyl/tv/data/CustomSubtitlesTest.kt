package com.bobyl.tv.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CustomSubtitlesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun acceptsOnlyNumericFilmIds() {
        assertTrue(CustomSubtitles.validId("88449"))
        listOf("", "../88449", "88449/1", "-1", "film").forEach { assertFalse(CustomSubtitles.validId(it)) }
    }
    @Test fun recognizesSubtitleExtensionsWithoutRestrictingLanguageNames() {
        listOf("Українська.srt", "English.ASS", "track.ssa", "captions.vtt").forEach { assertTrue(CustomSubtitles.supported(it)) }
        listOf("poster.jpg", "video.mp4", "track.srt.exe", "README", "archive.zip").forEach { assertFalse(CustomSubtitles.supported(it)) }
    }
    @Test fun discoversEverySupportedFileOnlyInTheExactFilmDirectory() {
        val films = temporary.newFolder("films")
        val film = java.io.File(films, "5104").apply { mkdir() }
        val names = listOf("Русский.srt", "English.ASS", "another.ssa", "captions.vtt", "second.ru.SRT")
        names.forEach { java.io.File(film, it).writeText("subtitle") }
        listOf("poster.jpg", "track.srt.exe", "README").forEach { java.io.File(film, it).writeText("ignored") }
        java.io.File(film, "nested.srt").mkdir()
        java.io.File(film, "nested.srt/hidden.srt").writeText("ignored")
        java.io.File(films, "51040").mkdir()
        java.io.File(films, "51040/wrong.srt").writeText("wrong film")

        val files = CustomSubtitles.filesForFilm(films, "5104")
        assertEquals(names.sortedBy { it.lowercase() }, files.map { it.name })
        assertTrue(files.all { it.parentFile == film })
    }
    @Test fun missingAndInvalidFilmIdsDoNotReadOtherDirectories() {
        val films = temporary.newFolder("films")
        java.io.File(films, "leak.srt").writeText("not a film")
        listOf("5104", "", "..", "../5104", "5104/1", "5104-devyat-2009.html").forEach {
            assertTrue("ID: $it", CustomSubtitles.filesForFilm(films, it).isEmpty())
        }
    }
}
