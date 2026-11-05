package com.bobyl.tv.data

import org.jsoup.nodes.Document

data class AccountProfile(val authenticated: Boolean?, val avatar: String, val premium: Boolean, val premiumInfo: String,
    val nickname: String = "")
object AccountParser {
    fun parse(doc: Document): AccountProfile {
        val logout = doc.select("a[href*=logout]").isNotEmpty() || Regex("[?&]action=logout").containsMatchIn(doc.html())
        val login = doc.select(".b-tophead__login").isNotEmpty()
        val authenticated = if (logout) true else if (login) false else null
        // Unknown markup or a challenge is not proof that the saved session has expired.
        return AccountProfile(authenticated,
            doc.selectFirst("div.b-userprofile__avatar_holder img")?.absUrl("src").orEmpty(),
            doc.select("body.b-premium_user__body").isNotEmpty(), doc.select("li.b-tophead-premuser").text(),
            nickname = if (authenticated == true && doc.selectFirst(".b-user__settings") != null) {
                // The profile page metadata contains the nickname; its header says "Профиль".
                sequenceOf(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(), doc.title())
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && '@' !in it &&
                        !it.equals("Профиль", ignoreCase = true) && !it.equals("Настройки профиля", ignoreCase = true) }
                    .orEmpty()
            } else "")
    }
}
