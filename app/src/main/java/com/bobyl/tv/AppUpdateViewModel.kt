package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.GitHubAppUpdates
import com.bobyl.tv.data.ProductionUpdate
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal data class AppUpdateState(val checking: Boolean = false, val downloading: Boolean = false,
    val progress: Int = 0, val update: ProductionUpdate? = null, val apk: File? = null, val message: String = "", val error: String = "")

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GitHubAppUpdates(application)
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()

    fun check() {
        if (!GitHubAppUpdates.enabled || state.value.checking || state.value.downloading) return
        mutableState.update { it.copy(checking = true, error = "", message = "") }
        viewModelScope.launch {
            try {
                val update = repository.latest()
                mutableState.update { it.copy(update = update, apk = null,
                    message = if (update == null) "Новых production-обновлений нет" else "Доступна версия ${update.versionName}") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    fun download() {
        val update = state.value.update ?: return
        if (!GitHubAppUpdates.enabled || state.value.downloading || state.value.checking) return
        mutableState.update { it.copy(downloading = true, progress = 0, error = "") }
        viewModelScope.launch {
            try {
                val apk = repository.download(update) { value -> mutableState.update { it.copy(progress = value) } }
                mutableState.update { it.copy(apk = apk, message = "Обновление загружено. Нажмите «Установить».") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally { mutableState.update { it.copy(downloading = false) } }
        }
    }
}
