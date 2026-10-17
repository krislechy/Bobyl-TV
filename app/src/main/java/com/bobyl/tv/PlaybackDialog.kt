package com.bobyl.tv

import androidx.compose.foundation.lazy.LazyColumn

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class PlaybackStep { Voice, Season, Episode, Quality, Subtitles }

/** Network loads belong to the visible step: backing out cancels the previous request. */
@Composable
internal fun PlaybackDialog(provider: String, playerPackage: String, title: String, info: PlaybackInfo,
    filmUrl: String = "", poster: String = "", download: Boolean = false, resumeRecord: WatchRecord? = null, dismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE) }
    val history = remember(provider) { WatchHistory(context, provider) }
    val scope = rememberCoroutineScope()
    val repository = remember { PlaybackRepository() }
    val prefetch = remember(repository, scope) { EpisodePrefetch(repository, scope) }
    val episodePreview by prefetch.preview.collectAsState()
    var step by remember { mutableStateOf(if (resumeRecord == null) PlaybackStep.Voice else PlaybackStep.Quality) }
    var voice by remember { mutableStateOf(info.voices.firstOrNull { it.id == resumeRecord?.voice } ?: if (resumeRecord != null) info.voices.firstOrNull() else null) }
    var season by remember { mutableStateOf(resumeRecord?.season.orEmpty()) }
    var episode by remember { mutableStateOf(resumeRecord?.episode.orEmpty()) }
    var seasons by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var choices by remember { mutableStateOf<StreamChoices?>(null) }
    val qualityStreams = remember(choices) { choices?.streams.orEmpty().sortedByDescending { qualityRank(it.label) } }
    var stream by remember { mutableStateOf<MediaTrack?>(null) }
    var manualQuality by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var autoAdvance by remember { mutableStateOf(false) }
    var lastQuality by remember { mutableStateOf("") }
    var lastSubtitle by remember { mutableStateOf("") }
    var launchPending by remember { mutableStateOf(false) }
    var resumePending by remember { mutableStateOf(resumeRecord != null) }
    var pendingRecord by remember { mutableStateOf<WatchRecord?>(null) }
    var relayPlayback by remember { mutableStateOf(false) }
    var directPlaybackUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(step) {
        if (step != PlaybackStep.Episode && step != PlaybackStep.Quality) prefetch.stop()
    }
    var localSubtitlePlayback by remember { mutableStateOf(false) }
    val optionListState = rememberLazyListState()
    val firstOptionFocus = remember { FocusRequester() }
    val playerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val returnedUrl = data?.data?.toString()
        val returnedEpisode = if (relayPlayback) EpisodeRelayService.relay?.episodeFor(returnedUrl) else null
        val wasRelay = relayPlayback && (returnedEpisode != null || returnedUrl != directPlaybackUrl)
        // Never assign a different episode's position to the originally selected episode.
        val record = if (wasRelay) returnedEpisode?.let { pendingRecord?.copy(episode = it) } else pendingRecord
        if (relayPlayback || localSubtitlePlayback) {
            context.stopService(Intent(context, EpisodeRelayService::class.java))
            EpisodeRelayService.closeSession()
            relayPlayback = false
            localSubtitlePlayback = false
        }
        val position = data?.getIntExtra("position", -1) ?: -1
        val complete = data?.getStringExtra("end_by") == "playback_completion"
        if (result.resultCode == android.app.Activity.RESULT_OK && record != null && (position >= 0 || complete)) {
            val returnedDecoder = data?.getByteExtra("decode_mode", record.decoder.toByte())?.toInt() ?: record.decoder
            if (complete && info.isMovie) history.put(record.copy(positionMs = 0L, watched = true,
                updated = System.currentTimeMillis(), decoder = returnedDecoder))
            else if (complete) history.remove(record.filmId)
            else history.put(record.copy(positionMs = position.toLong(), updated = System.currentTimeMillis(), decoder = returnedDecoder))
            if (position >= 0) scope.launch {
                try { ExtrasRepository().saveProgress(provider, record.filmId, record.voice, record.season, record.episode, position / 1000L) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "Позиция сохранена на устройстве. Серверная синхронизация недоступна." }
            }
            if (complete && !wasRelay && !info.isMovie && prefs.getBoolean("autoNext", false)) {
                val all = seasons.flatMap { (s, episodes) -> episodes.map { s to it } }
                val index = all.indexOf(record.season to record.episode)
                val next = if (index >= 0) all.getOrNull(index + 1) else null
                if (next != null) {
                    autoAdvance = true
                    season = next.first; episode = next.second; choices = null; stream = null; step = PlaybackStep.Quality
                    notice = "Следующая серия: ${next.first}:${next.second}. Получаем актуальный поток…"
                } else notice = "Просмотр завершён"
            }
        } else if (result.resultCode == android.app.Activity.RESULT_FIRST_USER) error = "Плеер сообщил об ошибке воспроизведения"
        else if (result.resultCode == android.app.Activity.RESULT_OK) notice = "Плеер не вернул позицию. Прогресс не изменён."
        else notice = "Запуск отменён или плеер не начал воспроизведение."
    }
    fun back() {
        autoAdvance = false
        if (step == PlaybackStep.Subtitles) {
            manualQuality = true
            resumePending = false
        }
        error = null
        step = when (step) {
            PlaybackStep.Voice -> { dismiss(); return }
            PlaybackStep.Season -> PlaybackStep.Voice
            PlaybackStep.Episode -> PlaybackStep.Season
            PlaybackStep.Quality -> if (info.isMovie) PlaybackStep.Voice else PlaybackStep.Episode
            PlaybackStep.Subtitles -> PlaybackStep.Quality
        }
    }
    fun launch(subtitle: MediaTrack?) {
        val chosen = stream ?: return
        if (launchPending) return
        error = null
        launchPending = true
        scope.launch {
            try {
                val downloads = MediaDownloads(context)
                if (download) {
                    if (prefs.getBoolean("externalDownload", false)) downloads.external(chosen) else downloads.enqueue(chosen, title)
                    if (subtitle != null) downloads.enqueue(subtitle, "$title ${subtitle.label}", subtitle = true)
                    notice = "Загрузка передана выбранному загрузчику"
                } else {
                    val localSub = if (subtitle != null && prefs.getBoolean("localSubtitles", true)) downloads.subtitle(subtitle) else null
                    // Position belongs to the film/episode, independently of stream URL and quality.
                    val saved = resumeRecord ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        history.find(info.id)?.takeUnless { it.watched }
                    }
                    val matched = saved?.takeIf { info.isMovie || (it.season == season && it.episode == episode) }
                    val resume = matched?.positionMs ?: 0L
                    val chosenPlayer = matched?.playerPackage?.takeIf { it.isNotBlank() } ?: playerPackage
                    val decoder = matched?.decoder?.takeIf { it in listOf(1, 2, 4) }
                        ?: prefs.getInt("mxDecoder", 0).takeIf { it in listOf(1, 2, 4) } ?: 0
                    pendingRecord = WatchRecord(info.id, filmUrl, title, poster, voice?.id.orEmpty(), season, episode, resume,
                        quality = chosen.label, subtitle = subtitle?.label.orEmpty(), playerPackage = chosenPlayer, decoder = decoder)
                    // Commit history only when the player returns a confirmed position or completion.
                    // Preparing an intent (or cancelling its chooser) must preserve existing progress.
                    var customSubtitles = if (info.isMovie) {
                        try { CustomSubtitles(context).forFilm(info.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            android.widget.Toast.makeText(context, "Папка дополнительных субтитров недоступна. Проверьте её в настройках.", android.widget.Toast.LENGTH_LONG).show()
                            emptyList()
                        }
                    } else emptyList()
                    if (BuildConfig.DEBUG && customSubtitles.isNotEmpty()) {
                        val files = customSubtitles
                        val localServer = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                            check(files.size <= 32) { "В папке слишком много субтитров (максимум 32)" }
                            var total = 0L
                            val snapshots = files.map { track ->
                                val bytes = context.contentResolver.openInputStream(track.uri)?.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(output.size() + count <= 4 * 1024 * 1024 && total <= 16 * 1024 * 1024) { "Локальные субтитры слишком большие" }
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                } ?: error("Не удалось открыть локальные субтитры")
                                track.name to bytes
                            }
                            LocalSubtitleServer(snapshots)
                        }
                        EpisodeRelayService.closeSession()
                        EpisodeRelayService.subtitleServer = localServer
                        localSubtitlePlayback = true
                        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, EpisodeRelayService::class.java))
                        customSubtitles = files.mapIndexed { index, track -> track.copy(uri = Uri.parse(localServer.urls[index])) }
                    }
                    val intent = externalVideoIntent(context, chosen, subtitle, if (info.isMovie) title else "Сезон $season — Серия $episode • $title", chosenPlayer, resume, localSub, decoder.toByte(), customSubtitles)
                    directPlaybackUrl = chosen.url
                    if (prefs.getBoolean("episodeRelay", true) && !info.isMovie &&
                        mxPlayerTargets(intent).isNotEmpty()) {
                        val selectedVoice = checkNotNull(voice)
                        val selectedSeason = season
                        if (seasons[selectedSeason].isNullOrEmpty()) seasons = repository.seasons(provider, info, selectedVoice)
                        val episodeIds = seasons[selectedSeason].orEmpty()
                        check(episode in episodeIds) { "Список серий не загружен. Выберите сезон заново." }
                        EpisodeRelayService.closeSession()
                        val selectedSubtitle = subtitle?.label
                        // Video and adjacent-subtitle probes share one API response per episode.
                        val sourceCache = mutableMapOf<String, Pair<Long, StreamChoices>>()
                        choices?.let { sourceCache[episode] = System.currentTimeMillis() to it }
                        fun sources(number: String): StreamChoices = synchronized(sourceCache) {
                            sourceCache[number]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second
                                ?: kotlinx.coroutines.runBlocking {
                                    repository.episode(provider, info, selectedVoice, selectedSeason, number)
                                }.also { sourceCache[number] = System.currentTimeMillis() to it }
                        }
                        val relay = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            EpisodeRelay(episodeIds, resolveSubtitle = { number ->
                                if (selectedSubtitle == null) null else sources(number).subtitles
                                    .firstOrNull { it.label == selectedSubtitle }?.url
                            }) { requestedEpisode ->
                                    val tracks = sources(requestedEpisode).streams
                                    tracks.firstOrNull { it.label == chosen.label }?.url
                                        ?: error("Выбранное качество недоступно")
                            }
                        }
                        EpisodeRelayService.relay = relay
                        relayPlayback = true
                        prefs.edit().putInt("episodeRelayLastPort", relay.port).apply()
                        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, EpisodeRelayService::class.java))
                        applyEpisodePlaylist(intent, relay, selectedSeason, episode, title, subtitle)
                    }
                    playerLauncher.launch(intent)
                    lastQuality = chosen.label; lastSubtitle = subtitle?.label.orEmpty()
                    prefs.edit().putString("lastVoice:${info.id}", voice?.id).putString("lastSeason:${info.id}", season)
                        .putString("lastEpisode:${info.id}", episode).apply()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ActivityNotFoundException) { error = "Выбранный плеер или загрузчик не установлен. Измените выбор в настройках." }
            catch (failure: Exception) { error = networkErrorMessage(failure) }
            finally {
                if ((relayPlayback || localSubtitlePlayback) && error != null) {
                    context.stopService(Intent(context, EpisodeRelayService::class.java))
                    EpisodeRelayService.closeSession(); relayPlayback = false
                    localSubtitlePlayback = false
                }
                launchPending = false
            }
        }
    }
    LaunchedEffect(step, voice, season, episode, retry) {
        error = null
        busy = false
        val selectedVoice = voice ?: return@LaunchedEffect
        try {
            when (step) {
                PlaybackStep.Season -> if (seasons.isEmpty()) {
                    busy = true
                    seasons = repository.seasons(provider, info, selectedVoice)
                }
                PlaybackStep.Quality -> {
                    if (choices == null) {
                    busy = true
                    // Resume skips the season picker, but Back and auto-next still need its data.
                    if (!info.isMovie && seasons.isEmpty()) {
                        seasons = repository.seasons(provider, info, selectedVoice)
                    }
                    choices = if (info.isMovie) repository.movie(provider, info, selectedVoice)
                        else prefetch.await(provider, info, selectedVoice, season, episode)
                            ?: repository.episode(provider, info, selectedVoice, season, episode)
                    }
                }
                else -> Unit
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
        finally { busy = false }
    }
    LaunchedEffect(step, choices, manualQuality, download) {
        if (step != PlaybackStep.Quality || manualQuality || download) return@LaunchedEffect
        val available = qualityStreams
        if (available.isEmpty()) return@LaunchedEffect
        val preferred = prefs.getString("defaultQuality", "ask") ?: "ask"
        val selected = automaticQuality(available, preferred)
            ?: if (resumePending) available.firstOrNull { it.label == resumeRecord?.quality } ?: available.first()
            else if (autoAdvance) available.firstOrNull { it.label == lastQuality } ?: available.first()
            else null
        if (selected != null) {
            stream = selected
            step = PlaybackStep.Subtitles
        }
    }
    LaunchedEffect(step, stream, autoAdvance, resumePending) {
        if (resumePending && step == PlaybackStep.Subtitles && stream != null) {
            resumePending = false
            val subtitle = choices?.subtitles?.firstOrNull { it.label == resumeRecord?.subtitle }
            launch(subtitle)
        }
        if (autoAdvance && step == PlaybackStep.Subtitles && stream != null) {
            autoAdvance = false
            val subtitle = choices?.subtitles?.firstOrNull { it.label == lastSubtitle }
            if (lastSubtitle.isBlank() || subtitle != null) launch(subtitle)
            else notice = "Субтитры предыдущей серии недоступны. Выберите вариант запуска."
        }
    }
    val hasFirstOption = when (step) {
        PlaybackStep.Voice -> info.voices.isNotEmpty()
        PlaybackStep.Season -> seasons.isNotEmpty()
        PlaybackStep.Episode -> seasons[season].orEmpty().isNotEmpty()
        PlaybackStep.Quality -> choices?.streams?.isNotEmpty() == true
        PlaybackStep.Subtitles -> true
    }
    LaunchedEffect(step, busy, hasFirstOption, seasons, choices) {
        if (!busy && hasFirstOption) {
            optionListState.scrollToItem(0)
            withFrameNanos { }
            firstOptionFocus.requestFocus()
        }
    }
    Dialog(::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val stepTitle = when (step) {
                PlaybackStep.Voice -> "Озвучка и язык"
                PlaybackStep.Season -> "Выберите сезон"
                PlaybackStep.Episode -> "Выберите серию"
                PlaybackStep.Quality -> "Качество видео"
                PlaybackStep.Subtitles -> "Субтитры и запуск"
            }
        val panelOptions = when (step) {
            PlaybackStep.Voice -> info.voices.map { it.title to (it.id == voice?.id) }
            PlaybackStep.Season -> seasons.keys.map { "Сезон $it" to (it == season) }
            PlaybackStep.Episode -> seasons[season].orEmpty().map { "Серия $it" to (it == episode) }
            PlaybackStep.Quality -> qualityStreams.map { it.label to (it == stream) }
            PlaybackStep.Subtitles -> listOf("Без субтитров" to true) + choices?.subtitles.orEmpty().map { it.label to false }
        }
        val nextPanel = when (step) {
            PlaybackStep.Voice -> if (info.isMovie) PushupPanel(PlaybackStep.Quality.ordinal, "Качество видео", emptyList())
                else PushupPanel(PlaybackStep.Season.ordinal, "Выберите сезон", emptyList())
            PlaybackStep.Season -> PushupPanel(PlaybackStep.Episode.ordinal, "Выберите серию", seasons[season].orEmpty().map { "Серия $it" to false })
            PlaybackStep.Episode -> PushupPanel(PlaybackStep.Quality.ordinal, "Качество видео", episodePreview?.choices?.streams.orEmpty().sortedByDescending { qualityRank(it.label) }.map { it.label to false })
            PlaybackStep.Quality -> PushupPanel(PlaybackStep.Subtitles.ordinal, "Субтитры и запуск",
                if (choices == null) emptyList() else listOf("Без субтитров" to false) + choices?.subtitles.orEmpty().map { it.label to false })
            PlaybackStep.Subtitles -> null
        }
        PlaybackPushup(PushupPanel(step.ordinal, stepTitle, panelOptions), title, mapOf(
            PlaybackStep.Voice.ordinal to voice?.title.orEmpty(),
            PlaybackStep.Season.ordinal to "Сезон $season",
            PlaybackStep.Episode.ordinal to "Серия $episode",
            PlaybackStep.Quality.ordinal to stream?.label.orEmpty(),
        ), nextPanel) {
            Text(stepTitle, color = Color.White, fontSize = 24.sp)
            Text(title, color = CinemaStyle.Muted, fontSize = 15.sp, maxLines = 2)
            if (launchPending) LoadingIndicator(if (download) "Начинаем загрузку…" else "Подготавливаем плеер…")
            if (notice.isNotBlank()) Text(notice, color = Color.LightGray)
            val listShape = RoundedCornerShape(16.dp)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)
                .clip(listShape).background(CinemaStyle.Surface)) {
            LazyColumn(state = optionListState, modifier = Modifier.fillMaxSize().popupScrollIndicator(optionListState).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                if (busy) item { SkeletonLoading(SkeletonLayout.Options, rows = 4) }
                if (!busy) {
                    when (step) {
                        PlaybackStep.Voice -> itemsIndexed(info.voices) { index, option ->
                            CinematicButton(option.title + (if (option.premium) " • Premium" else "") + (if (option.id == prefs.getString("lastVoice:${info.id}", "")) " • последняя" else ""), voice?.id == option.id,
                                if (index == 0) Modifier.focusRequester(firstOptionFocus) else Modifier) {
                                voice = option; choices = null; seasons = emptyMap(); stream = null; manualQuality = false
                                step = if (info.isMovie) PlaybackStep.Quality else PlaybackStep.Season
                            }
                        }
                        PlaybackStep.Season -> itemsIndexed(seasons.keys.toList()) { index, number ->
                            CinematicButton("Сезон $number" + if (number == prefs.getString("lastSeason:${info.id}", "")) " • последний" else "", season == number,
                                if (index == 0) Modifier.focusRequester(firstOptionFocus) else Modifier) { season = number; step = PlaybackStep.Episode }
                        }
                        PlaybackStep.Episode -> itemsIndexed(seasons[season].orEmpty()) { index, number ->
                            CinematicButton("Серия $number" + if (number == prefs.getString("lastEpisode:${info.id}", "") && season == prefs.getString("lastSeason:${info.id}", "")) " • последняя" else "", episode == number,
                                (if (index == 0) Modifier.focusRequester(firstOptionFocus) else Modifier).onFocusChanged {
                                    if (it.isFocused) voice?.let { selected -> prefetch.prepare(provider, info, selected, season, number) }
                                }) {
                                episode = number; choices = null; stream = null; manualQuality = false; step = PlaybackStep.Quality
                            }
                        }
                        PlaybackStep.Quality -> itemsIndexed(qualityStreams) { index, option ->
                            CinematicButton(option.label, stream == option, if (index == 0) Modifier.focusRequester(firstOptionFocus) else Modifier) {
                                stream = option
                                // Keep launch explicit even when there are no subtitle tracks.
                                step = PlaybackStep.Subtitles
                            }
                        }
                        PlaybackStep.Subtitles -> {
                            item { CinematicButton(if (download) "Скачать видео" else "Смотреть без субтитров", true,
                                Modifier.focusRequester(firstOptionFocus)) { launch(null) } }
                            if (download || prefs.getBoolean("selectSubtitles", true)) itemsIndexed(choices?.subtitles.orEmpty()) { _, option ->
                                CinematicButton((if (download) "Скачать • " else "Смотреть • ") + option.label, false) { launch(option) }
                            }
                        }
                    }
                    if (step == PlaybackStep.Voice && info.voices.isEmpty()) item {
                        Text("На странице нет доступных озвучек. Возможно, релиз ещё не опубликован.", color = Color.White)
                    }
                }
                error?.let { message -> item {
                    Text(message, color = Color(0xFFFFB5B9), fontSize = 17.sp)
                    if (step == PlaybackStep.Season || step == PlaybackStep.Quality) {
                        Spacer(Modifier.height(12.dp))
                        CinematicButton("Повторить загрузку", true) { retry++ }
                    }
                } }
            }
            if (optionListState.canScrollBackward) Box(Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth().height(30.dp)
                .background(Brush.verticalGradient(listOf(CinemaStyle.Surface, Color.Transparent))))
            if (optionListState.canScrollForward) Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, CinemaStyle.Surface))))
            }
            CinematicButton(if (busy) "Отменить и назад" else "Назад", false, onClick = ::back)
        }
    }
}

/** Fixed preferences select the same resolution first, then the nearest available lower one. */
internal fun automaticQuality(streams: List<MediaTrack>, preference: String): MediaTrack? {
    val preferred = preference.trim()
    if (preferred.equals("ask", true) || streams.isEmpty()) return null
    if (preferred.equals("max", true)) return streams.maxByOrNull { qualityRank(it.label) }
    streams.firstOrNull { it.label.trim().equals(preferred, true) }?.let { return it }
    val requested = qualityRank(preferred)
    if (requested == 0) return null
    streams.firstOrNull { qualityRank(it.label) == requested }?.let { return it }
    val resolution = requested / 10
    streams.filter { qualityRank(it.label) / 10 == resolution }
        .minByOrNull { kotlin.math.abs(qualityRank(it.label) - requested) }?.let { return it }
    val known = streams.filter { qualityRank(it.label) > 0 }
    return known.filter { qualityRank(it.label) / 10 <= resolution }.maxByOrNull { qualityRank(it.label) }
        ?: known.minByOrNull { qualityRank(it.label) }
        ?: streams.first()
}

/** Normalizes site labels such as `4K`, `UHD`, `2160p` and `1080p Ultra`. */
internal fun qualityRank(label: String): Int {
    val normalized = label.lowercase()
    val resolution = when {
        Regex("(^|\\D)8\\s*k($|\\D)").containsMatchIn(normalized) -> 4320
        Regex("(^|\\D)4\\s*k($|\\D)").containsMatchIn(normalized) || "uhd" in normalized -> 2160
        Regex("(^|\\D)2\\s*k($|\\D)").containsMatchIn(normalized) || "qhd" in normalized -> 1440
        else -> Regex("(?<!\\d)(2160|1440|1080|720|576|480|360|240)(?:p|i)?(?!\\d)")
            .findAll(normalized).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
    }
    val variant = (if ("ultra" in normalized) 4 else 0) + (if ("hdr" in normalized) 2 else 0)
    return resolution * 10 + variant
}

@Suppress("DEPRECATION")
internal fun mxPlayerTargets(intent: Intent): List<Intent> {
    val targets = if (intent.action == Intent.ACTION_CHOOSER) {
        listOfNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)) +
            intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS).orEmpty().filterIsInstance<Intent>()
    } else listOf(intent)
    return targets.filter { (it.`package` ?: it.component?.packageName).orEmpty().startsWith("com.mxtech.videoplayer") }
}

internal fun applyEpisodePlaylist(intent: Intent, relay: EpisodeRelay, season: String, episode: String,
    title: String, subtitle: MediaTrack?) {
    // Configure the actual ACTION_VIEW targets, including those nested in a chooser.
    // Other players keep the direct stream because this playlist contract is MX-specific.
    mxPlayerTargets(intent).forEach { target ->
        target.setDataAndType(Uri.parse(relay.urls.getValue(episode)), "video/*")
        target.putExtra("video_list", relay.urls.values.map(Uri::parse).toTypedArray())
        target.putExtra("video_list.name", relay.urls.keys.map { "Сезон $season — Серия $it • $title" }.toTypedArray())
        target.putExtra("video_list_is_explicit", true)
        subtitle?.let {
            val localSubtitle = Uri.parse(relay.subtitleUrl(episode, it.url))
            target.putExtra("subs", arrayOf<android.os.Parcelable>(localSubtitle))
            target.putExtra("subs.enable", arrayOf<android.os.Parcelable>(localSubtitle))
            target.putExtra("subtitles_location", localSubtitle.toString())
        }
    }
}

internal fun externalVideoIntent(context: Context, stream: MediaTrack, subtitle: MediaTrack?, title: String, playerPackage: String = "",
    positionMs: Long = 0, subtitleUri: Uri? = null, decoder: Byte = 0, customSubtitles: List<CustomSubtitle> = emptyList()): Intent {
    require(PlaybackParser.isMediaUrl(stream.url))
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(stream.url), "video/*")
        .putExtra("title", title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra("return_result", true).putExtra("position", positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        .putExtra("video_list", arrayOf(Uri.parse(stream.url)))
    subtitle?.let {
        require(PlaybackParser.isMediaUrl(it.url))
        // MX 3.2.1 and the reference APK pass the source URI in `subs`. A private
        // FileProvider URI could lose its grant through some chooser implementations.
        val sourceUri = Uri.parse(it.url)
        val uri = if (playerPackage.startsWith("com.mxtech.videoplayer")) sourceUri else subtitleUri ?: sourceUri
        intent.putExtra("subs", arrayOf<android.os.Parcelable>(uri))
        intent.putExtra("subs.enable", arrayOf<android.os.Parcelable>(uri))
        intent.putExtra("subtitles_location", (subtitleUri ?: sourceUri).toString())
        if (uri.scheme == "content") intent.clipData = android.content.ClipData.newRawUri("Субтитры", uri)
        intent.putExtra("subs.name", arrayOf(it.label))
        intent.putExtra("subs.filename", arrayOf(it.label))
    }
    if (playerPackage.isNotBlank() && playerPackage != "system") {
        intent.setPackage(playerPackage)
        if (playerPackage == "com.mxtech.videoplayer.ad") intent.setClassName(playerPackage, "$playerPackage.ActivityScreen")
        if (playerPackage == "com.mxtech.videoplayer.pro") intent.setClassName(playerPackage, "com.mxtech.videoplayer.ActivityScreen")
    }
    if (playerPackage.startsWith("com.mxtech.videoplayer")) configureMxIntent(intent, subtitle, decoder)
    // Network ACTION_VIEW may be filtered as an unverified web link on Android TV.
    // Enumerate video handlers too and send the selected package an explicit media intent.
    if (playerPackage.isBlank() || playerPackage == "system") {
        val players = installedPlayers(context)
        if (players.isEmpty()) throw ActivityNotFoundException("No external video player")
        val targets = players.map { candidate ->
            Intent(intent).setPackage(candidate.activityInfo.packageName).also { target ->
                when (candidate.activityInfo.packageName) {
                    "com.mxtech.videoplayer.ad" -> { target.setClassName("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.ad.ActivityScreen"); configureMxIntent(target, subtitle, decoder) }
                    "com.mxtech.videoplayer.pro" -> { target.setClassName("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ActivityScreen"); configureMxIntent(target, subtitle, decoder) }
                }
                appendCustomSubtitles(target, customSubtitles)
            }
        }
        if (playerPackage == "system") {
            val preferred = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            targets.firstOrNull { it.`package` == preferred }?.let { return it }
            if (targets.size == 1) return targets.first()
        }
        return Intent.createChooser(targets.first(), "Выберите видеоплеер")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray()).also {
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                it.clipData = targets.first().clipData
            }
    }
    if (context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) throw ActivityNotFoundException("No external video player")
    // Never forward account cookies or passwords to a third-party player.
    appendCustomSubtitles(intent, customSubtitles)
    return intent
}

/** Append after MX-specific configuration so it cannot replace the additional tracks. */
@Suppress("DEPRECATION")
internal fun appendCustomSubtitles(intent: Intent, tracks: List<CustomSubtitle>) {
    if (tracks.isEmpty()) return
    val existing = intent.getParcelableArrayExtra("subs").orEmpty().filterIsInstance<Uri>()
    val added = tracks.distinctBy { it.uri }.filter { it.uri !in existing }
    if (added.isEmpty()) return
    val names = intent.getStringArrayExtra("subs.name")
    val filenames = intent.getStringArrayExtra("subs.filename")
    intent.putExtra("subs", (existing + added.map { it.uri }).toTypedArray<android.os.Parcelable>())
    intent.putExtra("subs.name", (existing.indices.map { names?.getOrNull(it) ?: "Субтитры ${it + 1}" } + added.map { "Локальные • ${it.name}" }).toTypedArray())
    intent.putExtra("subs.filename", (existing.indices.map { filenames?.getOrNull(it) ?: "subtitle-$it.srt" } + added.map { it.name }).toTypedArray())
    // Preserve subs.enable: adding a track must not change the user's active selection.
    val clip = intent.clipData ?: android.content.ClipData.newRawUri("Дополнительные субтитры", added.first().uri)
    added.forEach { track ->
        if ((0 until clip.itemCount).none { clip.getItemAt(it).uri == track.uri }) clip.addItem(android.content.ClipData.Item(track.uri))
    }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun configureMxIntent(intent: Intent, subtitle: MediaTrack?, decoder: Byte) {
    subtitle?.let {
        val source = Uri.parse(it.url)
        intent.putExtra("subs", arrayOf<android.os.Parcelable>(source))
        intent.putExtra("subs.enable", arrayOf<android.os.Parcelable>(source))
        intent.putExtra("subs.name", arrayOf(it.label))
        intent.putExtra("subs.filename", arrayOf(it.label))
    }
    if (decoder in listOf<Byte>(1, 2, 4)) intent.putExtra("decode_mode", decoder)
}
