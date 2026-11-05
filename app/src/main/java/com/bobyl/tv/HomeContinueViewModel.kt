package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.WatchHistory
import com.bobyl.tv.data.WatchRecord
import com.bobyl.tv.data.historyFilmKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HomeContinueState(
    val records: List<WatchRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String = "",
)

/** Home only reads confirmed local progress; it never mutates history or logs in. */
internal class HomeContinueViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(HomeContinueState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    fun refresh(provider: String) {
        request?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, error = "")
        request = viewModelScope.launch {
            try {
                val records = withContext(Dispatchers.IO) {
                    WatchHistory(getApplication(), provider).all()
                        .filter { !it.watched && it.positionMs > 0 && it.url.isNotBlank() }
                        .distinctBy { historyFilmKey(it.url) }.take(8)
                }
                mutableState.value = HomeContinueState(records, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false,
                    error = "Не удалось прочитать историю просмотра")
            }
        }
    }
}
