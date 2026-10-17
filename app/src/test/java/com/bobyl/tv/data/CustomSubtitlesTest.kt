package com.bobyl.tv.data

import org.junit.Assert.*
import org.junit.Test

class CustomSubtitlesTest {
    @Test fun acceptsOnlyNumericFilmIds() {
        assertTrue(CustomSubtitles.validId("88449"))
        listOf("", "../88449", "88449/1", "-1", "film").forEach { assertFalse(CustomSubtitles.validId(it)) }
    }
    @Test fun recognizesSubtitleExtensionsWithoutRestrictingLanguageNames() {
        listOf("Українська.srt", "English.ASS", "track.ssa", "captions.vtt").forEach { assertTrue(CustomSubtitles.supported(it)) }
        listOf("poster.jpg", "video.mp4", "track.srt.exe", "README", "archive.zip").forEach { assertFalse(CustomSubtitles.supported(it)) }
    }
}
