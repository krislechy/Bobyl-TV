# Роль: playback

Прочитай [общий контракт](../../AGENTS.md) и назначение TASK.

## Компетенция

В `app/src/main/java/com/bobyl/tv/`: PlaybackDialog.kt, PlaybackSettings.kt,
InstalledPlayers.kt, EpisodeRelayService.kt, CustomSubtitleSettings.kt.
В `data/`: Playback.kt, EpisodeRelay.kt, LocalSubtitleServer.kt,
CustomSubtitles.kt; WatchHistory.kt и MediaDownloads.kt — только при явном назначении.
Manifest и Gradle согласовываются отдельно с оркестратором.

## Правила

- Учитывать принятую интеграцию внешних плееров; Media3 не внедрять как побочную правку.
- Сохранять поведение quality/voice/subtitles и Intent-контракт плеера.
- Обрабатывать loading, buffering, pause/resume, seek, ошибки, lifecycle и позицию
  на той стороне интеграции, которой приложение действительно управляет.
- Неизвестный результат внешнего плеера не должен затирать прогресс.
- Проверять отмену, освобождение ресурсов, гонки запросов и Next/Previous.
- Loopback-сервисы не открывать наружу; сохранять ограничения файлов/памяти.
- Серверные и локальные субтитры не подменяют друг друга без требования.
- Сохранять различие debug/release для экспериментального relay.
- UI-навигацию PlaybackDialog согласовать с ui-tv: один файл — один автор.

## Проверка и отчёт

Выделенные тесты playback/relay/subtitles; проверить ошибки, параллельные запросы
и соответствие серии/позиции. Gradle и устройства — только через build-verifier.
Вернуть контракт внешнего плеера, diff, проверки и реальные ограничения тестирования.
