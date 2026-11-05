package com.bobyl.tv.data

import android.content.Context
import com.bobyl.tv.data.database.AppStorage
import com.bobyl.tv.data.database.BobylDatabase
import com.bobyl.tv.data.database.PersonalRatingRow
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.security.MessageDigest

/** Stable account namespace, independent of login spelling, password and process session tokens. */
@ConsistentCopyVisibility
internal data class PersonalRatingOwner private constructor(val provider: String, val namespace: String) {
    companion object {
        fun fromCookies(provider: String, header: String): PersonalRatingOwner {
            val origin = CatalogUrls.provider(provider)
            val credentials = AccountSessionGuard.credentials(header)
            val user = credentials[0]
            val password = credentials[1]
            fun absent(value: String) = value.isBlank() || value.equals("deleted", ignoreCase = true)
            if (absent(user) && absent(password)) return PersonalRatingOwner(origin, "guest")
            check(user.matches(Regex("[0-9]+")) && user.trimStart('0').isNotEmpty() &&
                !absent(password) && password.none { it == '\r' || it == '\n' }) {
                "Не удалось определить учётную запись для личных оценок. Выполните вход заново."
            }
            val id = user.trimStart('0')
            val digest = MessageDigest.getInstance("SHA-256").digest("dle-user:$id".toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return PersonalRatingOwner(origin, "user-$digest")
        }
    }
}

internal data class PersonalRatingReading(val owner: PersonalRatingOwner, val value: Int)

/** Never attributes ownerless legacy entries. Reads and accepted writes are owned by IO callers. */
internal class PersonalRatings private constructor(private val database: BobylDatabase) {
    constructor(context: Context) : this(AppStorage.database(context))
    companion object {
        internal fun withDatabase(database: BobylDatabase) = PersonalRatings(database)
        fun filmId(reference: String): String = reference.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: Regex("/(\\d+)-").find(URI(reference).path.orEmpty())?.groupValues?.get(1)
            ?: error("Не удалось определить фильм для оценки. Откройте его карточку и повторите попытку.")
    }

    fun read(owner: PersonalRatingOwner, reference: String): Int =
        (database.storage().rating(owner.provider, owner.namespace, filmId(reference)) ?: 0).coerceIn(0, 10)

    suspend fun <T> save(expectedOwner: PersonalRatingOwner, reference: String, value: Int,
        currentOwner: () -> PersonalRatingOwner, submit: suspend () -> T): T {
        require(value in 1..10)
        val destination = PersonalRatingRow(expectedOwner.provider, expectedOwner.namespace, filmId(reference), value)
        val capturedOwner = currentOwner()
        check(capturedOwner == expectedOwner) { "Учётная запись изменилась. Откройте карточку фильма заново." }
        val result = submit()
        // Destination was fixed before HTTP. A later cookie change cannot reattribute this value.
        try { database.runInTransaction { database.storage().putRating(destination) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw IllegalStateException("Оценка отправлена, но не удалось сохранить её на устройстве.", failure) }
        return result
    }
}
