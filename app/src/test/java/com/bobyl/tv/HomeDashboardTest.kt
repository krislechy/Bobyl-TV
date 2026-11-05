package com.bobyl.tv

import com.bobyl.tv.data.BookmarkCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardTest {
    @Test fun greetingChangesAtActualDayParts() {
        assertEquals("Доброй ночи", homeGreeting(0))
        assertEquals("Доброй ночи", homeGreeting(4))
        assertEquals("Доброе утро", homeGreeting(5))
        assertEquals("Доброе утро", homeGreeting(11))
        assertEquals("Добрый день", homeGreeting(12))
        assertEquals("Добрый день", homeGreeting(17))
        assertEquals("Добрый вечер", homeGreeting(18))
        assertEquals("Добрый вечер", homeGreeting(22))
        assertEquals("Доброй ночи", homeGreeting(23))
    }

    @Test fun favoritesUsesAllCategoryRatherThanDoubleCountingOverlappingLists() {
        val categories = listOf(BookmarkCategory("0", "Всё", count = 9),
            BookmarkCategory("2", "Любимое", count = 7), BookmarkCategory("3", "На выходные", count = 5))
        assertEquals(HomeFavoritesCount(9), homeFavoritesCount(categories))
    }

    @Test fun favoritesUnionIsExactForOneNonemptyListAndBoundedForOverlaps() {
        assertNull(homeFavoritesCount(emptyList()))
        assertEquals(HomeFavoritesCount(16), homeFavoritesCount(listOf(BookmarkCategory("2", "К просмотру", count = 16),
            BookmarkCategory("3", "В ожидании", count = 0))))
        assertEquals(HomeFavoritesCount(7, lowerBound = true), homeFavoritesCount(listOf(
            BookmarkCategory("2", "Любимое", count = 7), BookmarkCategory("3", "На выходные", count = 5))))
        assertEquals(HomeFavoritesCount(0), homeFavoritesCount(listOf(BookmarkCategory("all", "Все", count = 0))))
        assertEquals(HomeFavoritesCount(0), homeFavoritesCount(listOf(BookmarkCategory("3", "В ожидании", count = 0))))
    }

    @Test fun homeShortcutsOverrideSavedHistoryFilterButNormalNavigationPreservesIt() {
        assertEquals("watched", historyTabForRequest("continue", "watched"))
        assertEquals("continue", historyTabForRequest("watched", "continue"))
        assertEquals("watched", historyTabForRequest("watched", null))
        assertEquals("deleted", historyTabForRequest("deleted", null))
        assertEquals("watched", historyTabForRequest("watched", "unknown"))
    }

    @Test fun premiumUsesActualRemainingDaysWithCorrectPluralInsteadOfWebsiteActionCopy() {
        assertEquals("109 дней", homePremiumLabel("Осталось109днейPremiumдоступаПродлить"))
        assertEquals("1 день", homePremiumLabel("Осталось 1 день"))
        assertEquals("2 дня", homePremiumLabel("Осталось 2 дня"))
        assertEquals("11 дней", homePremiumLabel("Осталось 11 дней"))
        assertEquals("21 день", homePremiumLabel("Осталось 21 день"))
        assertEquals("Активен", homePremiumLabel(""))
    }

    @Test fun customBookmarkListsAreNotMistakenForAll() {
        assertFalse(isAllBookmarkCategory(BookmarkCategory("2", "К просмотру")))
        assertTrue(isAllBookmarkCategory(BookmarkCategory("0", "Всё")))
    }

    @Test fun featuredArtworkTriesOnlyDistinctRealImageUrlsInQualityOrder() {
        assertEquals(listOf("full.jpg", "thumb.jpg", "update.jpg"), homeArtworkCandidates("full.jpg", "thumb.jpg", "update.jpg"))
        assertEquals(listOf("thumb.jpg"), homeArtworkCandidates("", "thumb.jpg", "thumb.jpg"))
        assertTrue(homeArtworkCandidates("", " ", "").isEmpty())
    }
}
