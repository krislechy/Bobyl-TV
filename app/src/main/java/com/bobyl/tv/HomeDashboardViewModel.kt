package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.AccountProfile
import com.bobyl.tv.data.AccountSession
import com.bobyl.tv.data.AccountSessionGuard
import com.bobyl.tv.data.premiumFraction
import com.bobyl.tv.data.premiumRemainingDays
import com.bobyl.tv.data.AuthRepository
import com.bobyl.tv.data.BookmarkCategory
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.CatalogRepository
import com.bobyl.tv.data.CatalogSection
import com.bobyl.tv.data.FilmDetails
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URI

internal data class HomeDashboardState(
    val nickname: String = "",
    val profile: AccountProfile? = null,
    val accountLoading: Boolean = true,
    val premiumFraction: Float? = null,
    val films: List<CatalogFilm> = emptyList(),
    val loading: Boolean = true,
    val error: String = "",
    val favorites: Int? = null,
    val favoritesLowerBound: Boolean = false,
    val favoritesLoading: Boolean = true,
    val featured: FilmDetails? = null,
    val featuredRequestUrl: String = "",
    val featuredLoading: Boolean = false,
    val featuredError: String = "",
)

/** Shared account summary; Home content is requested only when Home is displayed. */
internal class HomeDashboardViewModel @JvmOverloads constructor(
    application: Application,
    private val loadFilms: suspend (String, Boolean) -> List<CatalogFilm> = { provider, force ->
        CatalogRepository().load(provider, CatalogSection.Slider, 1, "", force = force)
    },
    private val loadCategories: suspend (String, Boolean) -> List<BookmarkCategory> = { provider, force ->
        com.bobyl.tv.data.LibraryRepository().categories(provider, force)
    },
    private val readAccountProfile: suspend (String) -> AccountProfile? = { provider ->
        val auth = AuthRepository(application)
        if (auth.restore(provider) != null) auth.profile(provider) else null
    },
    private val readPremiumPeriod: suspend (String, AccountProfile, Boolean) -> Int? = { provider, profile, refresh ->
        AuthRepository(application).premiumPeriod(provider, profile, refresh)
    },
    private val readFeatured: suspend (String) -> FilmDetails = { CatalogRepository().details(it) },
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(HomeDashboardState())
    val state = mutableState.asStateFlow()
    private var accountKey = ""
    private var accountSession: AccountSession? = null
    private var contentKey: Triple<String, Int, Int>? = null
    private var favoritesKey: HomeFavoritesRequest? = null
    private var accountJob: Job? = null
    private var contentJob: Job? = null
    private var favoritesJob: Job? = null
    private var featuredJob: Job? = null
    private var featuredUrl = ""
    private var featuredSession: AccountSession? = null
    private var featuredSerial = 0L

    fun loadAccount(provider: String, revision: Int, refreshRevision: Int = 0) {
        val session = AccountSessionGuard.shared.current(provider)
        val key = "$provider|$revision|${session.token}|$refreshRevision"
        if (accountKey == key) return
        val sameAccount = accountSession == session
        accountKey = key
        accountSession = session
        accountJob?.cancel()
        mutableState.update { if (sameAccount) it else it.copy(nickname = "", profile = null,
            premiumFraction = null, accountLoading = true) }
        fun current() = accountKey == key && AccountSessionGuard.shared.current(provider) == session
        accountJob = viewModelScope.launch {
            try {
                val previous = mutableState.value.profile
                val profile = readAccountProfile(provider)
                if (!current()) return@launch
                val increased = (premiumRemainingDays(profile?.premiumInfo.orEmpty()) ?: 0) >
                    (premiumRemainingDays(previous?.premiumInfo.orEmpty()) ?: Int.MAX_VALUE)
                mutableState.update { it.copy(profile = profile, accountLoading = false,
                    nickname = accountDisplayNickname(profile), premiumFraction =
                        if (profile?.premium == true && !increased) it.premiumFraction else null) }
                if (profile?.authenticated == true && profile.premium) {
                    try {
                        val purchased = readPremiumPeriod(provider, profile, previous?.premium == false)
                        if (current()) mutableState.update { it.copy(premiumFraction =
                            premiumFraction(premiumRemainingDays(profile.premiumInfo), purchased)) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (current()) mutableState.update { it.copy(premiumFraction = null) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // The account dialog offers validation/retry; public Home remains usable offline.
                if (current()) mutableState.update { it.copy(accountLoading = false) }
            }
        }
    }

    fun loadContent(provider: String, sessionRevision: Int, refreshRevision: Int, force: Boolean = false) {
        val key = Triple(provider, sessionRevision, refreshRevision)
        if (!force && contentKey == key) return
        val explicitRefresh = force || contentKey?.third?.let { it != refreshRevision } == true
        contentKey = key
        contentJob?.cancel()
        mutableState.update { it.copy(loading = true, error = "") }
        contentJob = viewModelScope.launch {
            try {
                val films = loadFilms(provider, explicitRefresh)
                mutableState.update { it.copy(films = films, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(loading = false, error = networkErrorMessage(failure)) }
            }
        }
    }

    fun loadFavorites(provider: String, sessionRevision: Int, refreshRevision: Int, detailRevision: Int, visible: Boolean = true) {
        if (!visible) return
        val key = HomeFavoritesRequest(provider, sessionRevision, refreshRevision, detailRevision)
        if (favoritesKey == key) return
        val previous = favoritesKey
        favoritesKey = key
        favoritesJob?.cancel()
        val sameAccount = previous != null && previous.provider == provider && previous.sessionRevision == sessionRevision
        mutableState.update { it.copy(favorites = if (sameAccount) it.favorites else null,
            favoritesLowerBound = sameAccount && it.favoritesLowerBound, favoritesLoading = true) }
        favoritesJob = viewModelScope.launch {
            try {
                val categories = loadCategories(provider, previous != null || detailRevision != 0 || refreshRevision != 0)
                val summary = homeFavoritesCount(categories)
                mutableState.update { it.copy(favorites = summary?.count,
                    favoritesLowerBound = summary?.lowerBound == true, favoritesLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(favoritesLoading = false) } }
        }
    }

    fun loadFeatured(url: String) {
        val session = url.takeIf { it.isNotBlank() }?.let { reference -> runCatching {
            val uri = URI(reference)
            AccountSessionGuard.shared.current("${uri.scheme}://${uri.rawAuthority}")
        }.getOrNull() }
        if (url == featuredUrl && session == featuredSession) return
        val serial = ++featuredSerial
        featuredUrl = url
        featuredSession = session
        featuredJob?.cancel()
        mutableState.update { it.copy(featured = null, featuredRequestUrl = url,
            featuredLoading = url.isNotBlank(), featuredError = "") }
        if (url.isBlank()) return
        fun current() = featuredSerial == serial && featuredUrl == url &&
            (session == null || AccountSessionGuard.shared.current(session.provider) == session)
        featuredJob = viewModelScope.launch {
            try {
                val details = withTimeout(4_000L) { readFeatured(url) }
                if (current()) mutableState.update { it.copy(featured = details) }
            } catch (_: TimeoutCancellationException) {
                if (current()) mutableState.update { it.copy(featuredError = "Не удалось загрузить сведения вовремя") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current()) mutableState.update { it.copy(featuredError = networkErrorMessage(failure)) }
            } finally { if (featuredSerial == serial && featuredUrl == url)
                mutableState.update { it.copy(featuredLoading = false) } }
        }
    }
}

private data class HomeFavoritesRequest(val provider: String, val sessionRevision: Int, val refreshRevision: Int, val detailRevision: Int)

internal fun accountDisplayNickname(profile: AccountProfile?): String =
    if (profile?.authenticated == true) profile.nickname.trim() else ""

internal data class HomeFavoritesCount(val count: Int, val lowerBound: Boolean = false)

internal fun isAllBookmarkCategory(category: BookmarkCategory): Boolean =
    category.id == "0" || category.name.trim().equals("Всё", true) || category.name.trim().equals("Все", true)

internal fun homeFavoritesCount(categories: List<BookmarkCategory>): HomeFavoritesCount? {
    categories.firstOrNull(::isAllBookmarkCategory)?.let { return HomeFavoritesCount(it.count) }
    if (categories.isEmpty()) return null
    val positive = categories.filter { it.count > 0 }
    return HomeFavoritesCount(positive.maxOfOrNull { it.count } ?: 0, lowerBound = positive.size > 1)
}

internal fun homePremiumLabel(info: String): String {
    val days = Regex("(\\d+)\\s*(?:дней|дня|день)", RegexOption.IGNORE_CASE)
        .find(info)?.groupValues?.get(1)?.toLongOrNull() ?: return info.trim().ifBlank { "Активен" }
    val unit = when {
        days % 100 in 11L..14L -> "дней"
        days % 10 == 1L -> "день"
        days % 10 in 2L..4L -> "дня"
        else -> "дней"
    }
    return "$days $unit"
}

internal fun homeArtworkCandidates(fullPoster: String, poster: String, updatePoster: String): List<String> =
    listOf(fullPoster, poster, updatePoster).map { it.trim() }.filter { it.isNotBlank() }.distinct()

internal fun homeGreeting(hour: Int): String = when (hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}
