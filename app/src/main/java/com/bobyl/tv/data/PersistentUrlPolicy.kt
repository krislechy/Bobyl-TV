package com.bobyl.tv.data

import java.net.URI
import java.net.URLDecoder

/** Public metadata may persist URLs, but never embedded credentials or signed/token-bearing links. */
internal fun requirePersistablePublicUrl(value: String) {
    if (value.isBlank()) return
    val uri = URI(value)
    check(uri.scheme in listOf("http", "https") && uri.userInfo == null)
    val sensitive = setOf("token", "auth", "key", "signature", "sig", "expires", "phpsessid", "dle_password", "dle_user_id", "access_token")
    check(uri.rawQuery.orEmpty().split('&').none {
        URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() in sensitive
    })
}
