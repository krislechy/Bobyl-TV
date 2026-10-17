package com.bobyl.tv

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.ExtrasRepository
import com.bobyl.tv.data.WatchHistory
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class HistoryCleanupTarget { Device, Account }

@Immutable
internal data class HistoryCleanupState(val busy: Boolean = false, val message: String = "", val removed: Int = 0, val total: Int = 0)

internal class HistoryCleanupViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(HistoryCleanupState())
    val state = mutableState.asStateFlow()

    fun clear(provider: String, target: HistoryCleanupTarget) {
        if (mutableState.value.busy) return
        mutableState.value = HistoryCleanupState(busy = true)
        viewModelScope.launch {
            try {
                val count = when (target) {
                    HistoryCleanupTarget.Device -> withContext(Dispatchers.IO) {
                        val history = WatchHistory(getApplication(), provider)
                        val count = history.all().size
                        history.clear()
                        count
                    }
                    HistoryCleanupTarget.Account -> ExtrasRepository().clearHistory(provider) { removed, total ->
                        mutableState.value = mutableState.value.copy(removed = removed, total = total)
                    }
                }
                val location = if (target == HistoryCleanupTarget.Device) "на устройстве" else "в аккаунте"
                mutableState.value = HistoryCleanupState(message = "Список «Продолжить» $location очищен. Удалено записей: $count.")
            } catch (cancelled: CancellationException) {
                mutableState.value = HistoryCleanupState()
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, message = networkErrorMessage(failure))
            }
        }
    }
}
