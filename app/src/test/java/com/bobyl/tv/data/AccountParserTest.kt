package com.bobyl.tv.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class AccountParserTest {
    // Captured profile structure: identity is substituted, static labels are preserved.
    // Evidence: captures/account4-public-profile-safe.txt.
    private fun profilePage(ogTitle: String = "FixtureNickname", title: String = "FixtureNickname",
        authenticated: Boolean? = true, profileMarker: Boolean = true) = Jsoup.parse("""
        <html><head><title>$title</title><meta property="og:title" content="$ogTitle"></head>
        <body class="b-premium_user__body">
          ${when (authenticated) { true -> "<a href='/?action=logout'>Выйти</a>"; false -> "<div class='b-tophead__login'>Войти</div>"; null -> "" }}
          <div class="b-tophead"><div class="b-tophead-right user-things pull-right"><div class="b-tophead-dropdown">
            <div class="b-tophead__profile">Профиль<div class="b-tophead__ava"><img></div></div>
          </div></div></div>
          ${if (profileMarker) """
            <div class="b-user__settings"><form>
              <div class="b-content__htitle"><h1>Настройки профиля</h1></div>
              <span>Никнейм</span><span>FixtureNickname</span>
              <label for="email">Email</label><input type="text" name="email" id="email" value="saved-login@example.invalid">
            </form></div>
          """ else "<h1>Название фильма</h1>"}
        </body></html>
    """.trimIndent(), "https://profile.example/user/1/")

    @Test fun capturedProfileUsesNicknameMetadataAndPreservesStaticLabels() {
        val document = profilePage()
        assertEquals("Профиль", document.selectFirst(".b-tophead__profile")!!.ownText())
        assertEquals("Настройки профиля", document.selectFirst("h1")!!.text())
        val profile = AccountParser.parse(document)
        assertEquals(true, profile.authenticated)
        assertEquals("FixtureNickname", profile.nickname)
        assertTrue(profile.premium)
    }

    @Test fun documentTitleProvidesTheConfirmedProfileFallback() {
        assertEquals("FixtureNickname", AccountParser.parse(profilePage(ogTitle = "")).nickname)
        assertEquals("MetadataNickname", AccountParser.parse(profilePage(ogTitle = "MetadataNickname")).nickname)
    }

    @Test fun missingMetadataNeverUsesProfileButtonSettingsHeadingOrEmail() {
        assertEquals("", AccountParser.parse(profilePage(ogTitle = "", title = "")).nickname)
        assertEquals("", AccountParser.parse(profilePage(ogTitle = "Профиль", title = "Настройки профиля")).nickname)
        assertEquals("", AccountParser.parse(profilePage(ogTitle = "saved-login@example.invalid", title = "")).nickname)
    }

    @Test fun authenticatedFilmMetadataWithoutProfileMarkerIsNotAnAccountNickname() {
        assertEquals("", AccountParser.parse(profilePage(ogTitle = "Название фильма", title = "Название фильма", profileMarker = false)).nickname)
    }

    @Test fun loggedOutAndUnknownResponsesDoNotExposeProfileMetadataAsAName() {
        val loggedOut = AccountParser.parse(profilePage(authenticated = false))
        assertEquals(false, loggedOut.authenticated)
        assertEquals("", loggedOut.nickname)
        val unknown = AccountParser.parse(profilePage(authenticated = null))
        assertNull(unknown.authenticated)
        assertEquals("", unknown.nickname)
    }
}
