package com.bobyl.tv

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.ExtrasRepository
import com.bobyl.tv.data.WatchHistory
import com.bobyl.tv.data.AccountSession
import com.bobyl.tv.data.AccountSessionGuard
import com.bobyl.tv.data.ServerHistoryChanges
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

internal class HistoryCleanupViewModel @JvmOverloads constructor(application: Application,
    private val sessions: AccountSessionGuard = AccountSessionGuard.shared,
    private val clearDevice: suspend (String) -> Int = { provider -> withContext(Dispatchers.IO) {
        WatchHistory(application, provider).clearCount()
    } },
    private val clearAccount: suspend (String, (Int, Int) -> Unit) -> Int = { provider, progress ->
        ExtrasRepository().clearHistory(provider, progress)
    },
    private val notifyChange: (AccountSession, Boolean, Boolean) -> Unit = { session, device, completed ->
        ServerHistoryChanges.historyCleared(session, device, completed)
    },
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(HistoryCleanupState())
    val state = mutableState.asStateFlow()

    fun clear(provider: String, target: HistoryCleanupTarget) {
        if (mutableState.value.busy) return
        val session = sessions.current(provider)
        val permit = sessions.register(session)
        if (!permit.accepted) {
            mutableState.value = HistoryCleanupState(message = permit.rejection.orEmpty())
            sessions.finish(permit)
            return
        }
        mutableState.value = HistoryCleanupState(busy = true)
        val job = viewModelScope.launch {
            var attempted = false
            var completed = false
            try {
                val count = sessions.server(permit) {
                    attempted = true
                    when (target) {
                        HistoryCleanupTarget.Device -> clearDevice(provider)
                        HistoryCleanupTarget.Account -> clearAccount(provider) { removed, total ->
                            mutableState.value = mutableState.value.copy(removed = removed, total = total)
                        }
                    }
                }
                completed = true
                notifyChange(session, target == HistoryCleanupTarget.Device, true)
                mutableState.value = HistoryCleanupState(message = "Удалено записей: $count")
            } catch (cancelled: CancellationException) {
                mutableState.value = HistoryCleanupState()
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, message = networkErrorMessage(failure))
            } finally {
                if (attempted && !completed) notifyChange(session, target == HistoryCleanupTarget.Device, false)
                sessions.finish(permit)
            }
        }
        job.invokeOnCompletion { sessions.finish(permit) }
    }
}
