package com.bobyl.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.BrowseRepository
import com.bobyl.tv.data.CatalogFilm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class SearchSuggestionsState(
    val films: List<CatalogFilm> = emptyList(),
    val loading: Boolean = false,
    val unavailable: Boolean = false,
)

class SearchSuggestionsViewModel(
    private val load: suspend (String, String) -> List<CatalogFilm> = BrowseRepository()::quickSearch,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchSuggestionsState())
    val state = mutableState.asStateFlow()
    private var request: Pair<String, String>? = null
    private var job: Job? = null
    private var generation = 0L

    fun update(provider: String, value: String) {
        val next = provider to value
        if (request == next) return
        stop()
        request = next
        if (value.trim().length < 2) return
        val currentGeneration = generation
        job = viewModelScope.launch {
            delay(400)
            mutableState.value = SearchSuggestionsState(loading = true)
            try {
                val films = load(provider, value)
                if (generation == currentGeneration) mutableState.value = SearchSuggestionsState(films = films)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == currentGeneration) mutableState.value = SearchSuggestionsState(unavailable = true)
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        request = null
        mutableState.value = SearchSuggestionsState()
    }
}
