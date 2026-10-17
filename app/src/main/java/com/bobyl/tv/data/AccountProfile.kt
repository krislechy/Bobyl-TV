package com.bobyl.tv.data

import org.jsoup.nodes.Document

data class AccountProfile(val authenticated: Boolean?, val avatar: String, val premium: Boolean, val premiumInfo: String)
object AccountParser {
    fun parse(doc: Document): AccountProfile {
        val logout = doc.select("a[href*=logout]").isNotEmpty() || Regex("[?&]action=logout").containsMatchIn(doc.html())
        val login = doc.select(".b-tophead__login").isNotEmpty()
        // Unknown markup or a challenge is not proof that the saved session has expired.
        return AccountProfile(if (logout) true else if (login) false else null,
            doc.selectFirst("div.b-userprofile__avatar_holder img")?.absUrl("src").orEmpty(),
            doc.select("body.b-premium_user__body").isNotEmpty(), doc.select("li.b-tophead-premuser").text())
    }
}
