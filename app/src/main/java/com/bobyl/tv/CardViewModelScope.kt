package com.bobyl.tv

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.bobyl.tv.data.AccountSession
import com.bobyl.tv.data.AccountSessionGuard

internal class CardViewModelStoreOwner : ViewModelStoreOwner, AutoCloseable {
    override val viewModelStore = ViewModelStore()
    override fun close() { viewModelStore.clear() }
}
internal val LocalCardSession = staticCompositionLocalOf<AccountSession?> { null }

@Composable
internal fun currentFilmActionKey(provider: String, reference: String): FilmActionKey {
    val inherited = LocalCardSession.current
    val session = inherited?.takeIf { it.provider == com.bobyl.tv.data.CatalogUrls.provider(provider) }
        ?: remember(provider) { AccountSessionGuard.shared.current(provider) }
    return remember(session, reference) { FilmActionKey.of(session, reference) }
}

/** All windows for one card share its store; nested film cards enter a fresh scope. */
@Composable
internal fun CardViewModelScope(provider: String, film: String, content: @Composable () -> Unit) {
    val session = remember(provider, film) { AccountSessionGuard.shared.current(provider) }
    val owner = remember(provider, film) { CardViewModelStoreOwner() }
    DisposableEffect(owner) { onDispose { owner.close() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalCardSession provides session, content = content)
}
