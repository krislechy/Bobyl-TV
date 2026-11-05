package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.CatalogUrls
import com.bobyl.tv.data.AccountSessionGuard
import com.bobyl.tv.data.PersonalRatings
import com.bobyl.tv.data.PersonalRatingOwner
import com.bobyl.tv.data.PersonalRatingReading
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PersonalRatingState(val value: Int? = null, val loading: Boolean = true, val saving: Boolean = false,
    val error: String = "", val aggregate: Pair<String, String>? = null)

internal class PersonalRatingViewModel(application: Application, private val actions: FilmActionsViewModel,
    private val actionKey: FilmActionKey) : AndroidViewModel(application) {
    private val ratings = PersonalRatings(application)
    private val sessions = AccountSessionGuard.shared
    private var ratingOwner: PersonalRatingOwner? = null
    private val mutableState = MutableStateFlow(PersonalRatingState())
    val state = mutableState.asStateFlow()
    private var loading: kotlinx.coroutines.Job? = null
    fun load(provider: String, filmId: String) {
        if (mutableState.value.saving) return
        loading?.cancel()
        loading = viewModelScope.launch {
            ratingOwner = null
            mutableState.value = PersonalRatingState(loading = true)
            try {
                actions.awaitSettled(actionKey)
                val reading = withContext(Dispatchers.IO) {
                    require(CatalogUrls.provider(provider) == actionKey.session.provider)
                    sessions.read(actionKey.session) {
                        val (_, cookies) = sessions.requestCookies(actionKey.session.provider) {
                            android.webkit.CookieManager.getInstance().getCookie(actionKey.session.provider).orEmpty()
                        }
                        val owner = PersonalRatingOwner.fromCookies(actionKey.session.provider, cookies)
                        PersonalRatingReading(owner, ratings.read(owner, filmId))
                    }
                }
                ratingOwner = reading.owner
                mutableState.value = PersonalRatingState(value = reading.value, loading = false, error = actions.latestError(actionKey))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = PersonalRatingState(loading = false, error = networkErrorMessage(failure)) }
        }
    }

    fun rate(provider: String, filmId: String, value: Int) {
        val before = mutableState.value
        if (before.loading || before.saving || value == before.value) return
        val receipt = try {
            val owner = checkNotNull(ratingOwner) { "Не удалось прочитать личные оценки. Откройте карточку фильма заново." }
            actions.rate(actionKey, PersonalRatings.filmId(filmId), value, owner)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            mutableState.value = before.copy(saving = false, error = networkErrorMessage(failure))
            return
        }
        mutableState.value = before.copy(saving = receipt.accepted, error = "")
        viewModelScope.launch {
            try {
                val result = receipt.await()
                mutableState.value = before.copy(value = value, saving = false, aggregate = result.average to result.votes, error = "")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = before.copy(saving = false, error = networkErrorMessage(failure)) }
        }
    }
}
