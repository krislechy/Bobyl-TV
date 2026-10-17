package com.bobyl.tv.data

import org.jsoup.HttpStatusException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun networkErrorMessage(error: Exception): String = when (error) {
    is HttpStatusException -> when (error.statusCode) {
        403 -> "Сайт отказал в доступе (403). Проверьте доступность страницы или адрес зеркала в настройках."
        404 -> "Страница не найдена (404). Возможно, сайт изменил её адрес."
        else -> "Ошибка сайта (${error.statusCode}). Попробуйте позже."
    }
    is SocketTimeoutException -> "Сайт не ответил вовремя. Проверьте соединение и повторите запрос."
    is UnknownHostException -> "Не удалось найти сайт. Проверьте интернет и адрес зеркала."
    is SSLException -> "Не удалось проверить защищённое соединение. Проверьте адрес сайта и дату устройства."
    else -> error.message ?: "Не удалось загрузить данные"
}
