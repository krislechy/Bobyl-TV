package com.bobyl.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class EpisodePreview(val key: String, val choices: StreamChoices)

/** A single cancellable preview request, reused when its episode is selected. */
internal class EpisodePrefetch(private val repository: PlaybackRepository, private val scope: CoroutineScope) {
    private val mutablePreview = MutableStateFlow<EpisodePreview?>(null)
    val preview = mutablePreview.asStateFlow()
    private var key = ""
    private var pending: Deferred<StreamChoices?>? = null

    private fun requestKey(provider: String, info: PlaybackInfo, voice: VoiceOption, season: String, episode: String) =
        "$provider|${info.id}|${voice.id}|$season|$episode"

    fun prepare(provider: String, info: PlaybackInfo, voice: VoiceOption, season: String, episode: String) {
        val requested = requestKey(provider, info, voice, season, episode)
        if (requested == key && pending != null) return
        stop()
        key = requested
        pending = scope.async(Dispatchers.IO) {
            try {
                repository.episode(provider, info, voice, season, episode).also {
                    if (key == requested) mutablePreview.value = EpisodePreview(requested, it)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // The active picker will report a normal load failure.
        }
    }

    suspend fun await(provider: String, info: PlaybackInfo, voice: VoiceOption, season: String, episode: String): StreamChoices? =
        if (key == requestKey(provider, info, voice, season, episode)) pending?.await() else null

    fun stop() {
        pending?.cancel()
        pending = null
        key = ""
        mutablePreview.value = null
    }
}
