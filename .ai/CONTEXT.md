# Карта контекста

Пути ниже — от Git-корня. Сначала выбери сценарий; не читай таблицу как список
обязательной загрузки. Документы описывают контракты и исторические проверки;
текущее поведение подтверждай исходниками через Serena и проверками задачи.

| Задача | Начальные файлы | Инструкции и материалы по необходимости |
|---|---|---|
| Compose, D-pad, focus | `app/src/main/java/com/bobyl/tv/{BobylTvApp,ConnectedCatalog,BrowseScreens,CinematicFields}.kt` | [ui-tv](roles/ui-tv.md) |
| Состояние, каталог, сеть | `CatalogViewModel.kt`, `data/{CatalogRepository,BrowseRepository,Catalog,NetworkErrors,PageCache}.kt` в том же пакете | [data](roles/data.md), [сетевой контракт](../docs/NETWORK_CONTRACT.md) |
| Авторизация, аккаунт | `AuthSettings.kt`, `AccountWebDialog.kt`, `data/{AuthRepository,AccountProfile}.kt` | [data](roles/data.md), [auth/news](../docs/AUTH_AND_NEWS.md) |
| Библиотека, история | `FavoritesScreen.kt`, `data/{LibraryRepository,WatchHistory,MediaDownloads}.kt` | [data](roles/data.md), [film/library](../docs/FILM_AND_LIBRARY.md) |
| Внешние плееры, серии | `PlaybackDialog.kt`, `InstalledPlayers.kt`, `data/{Playback,EpisodeRelay}.kt`, `EpisodeRelayService.kt` | [playback](roles/playback.md), [миграция](../docs/PLAYBACK_MIGRATION.md), [relay](../docs/EXPERIMENTAL_EPISODE_RELAY.md), [серии](../docs/POSTER_STATUS_AND_EPISODES.md) |
| Субтитры | `CustomSubtitleSettings.kt`, `data/{CustomSubtitles,LocalSubtitleServer}.kt` | [playback](roles/playback.md), [субтитры](../docs/CUSTOM_FILM_SUBTITLES.md) |
| Сборка, тесты, устройство | `app/build.gradle.kts`, `app/src/test/`, `app/src/androidTest/` | [build-verifier](roles/build-verifier.md); [tvRelease](../docs/TV_RELEASE.md) только для этого варианта |
| Сравнение с оригиналом | `reference/original-apk/`; Java/smali в tools_root | [reference](roles/reference.md) |
| Делегирование, интеграция | точные write sets и контракты задачи | [orchestrator](roles/orchestrator.md), [ORCHESTRATION](ORCHESTRATION.md); [MODELS](MODELS.md) при выборе модели |
| История миграции | исторические отчёты, не инструкции продолжения | [архив](../docs/previous-workspace-context.md), [parity report](../docs/PARITY_REPORT_2026-09-04.md), [parity work](../docs/PARITY_WORK.md), [migration plan](../docs/MIGRATION_PLAN.md) |

## Пути среды и источники истины

- Основной repo_root: `<workspace>\project`.
- tools_root: `<workspace>\.tools`; в worktree не вычислять его из родителя.
- JDK/SDK/ADB: `android-dev/jdk-17`, `android-dev/sdk`, `platform-tools/adb.exe` внутри tools_root.
- Reference: `hdrezka-src/sources/com/falcofemoralis/hdrezkaapp`,
  `hdrezka-original-clean`, `mx321-src` внутри tools_root.
- Версии и зависимости: Gradle-файлы; состояние работы: Git и результаты текущих проверок.
- Общие правила: AGENTS.md; правила реализации: CONVENTIONS.md; роль дополняет их по своей области.
- Память Serena — короткий индекс к этим файлам. Начни с `mem:core`; остальные воспоминания
  читай по конкретному вопросу. Не дублируй документацию и не сохраняй временные сбои/секреты.

## Ограничение загрузки и передача работы

Используй `get_symbols_overview` → `find_symbol` с нужным телом →
`find_referencing_symbols` по необходимости. Ограничивай поиск каталогом или файлом.
Не загружай build/cache, APK, полные логи и декомпиляции без конкретного вопроса.
При большом результате уточни запрос вместо увеличения лимита.

Для повторяемых проверок предпочитай один пакетный скрипт: полный лог сохраняй
в captures, в контекст возвращай статус, итоговые метрики и короткую причину ошибки.
Не перечитывай baseline-prof.txt и сырые трассы целиком: проверяй их инструментами
по конкретному вопросу. Ожидай долгую команду укрупнёнными интервалами до 60 секунд.

Для долгой задачи передавай цель, подтверждённые решения, ссылки на файлы/символы,
изменённые файлы, выполненные проверки и следующий шаг. Историю диалога не копируй.
Постоянные сведения обновляй в их источнике, краткоживущий план держи в задаче.

Профильный skill или плагин подключай только под нужную операцию: Serena для кода,
GitHub для запрошенной работы с репозиторием, документацию библиотек для конкретного API.
Используй доступные инструменты по их фактической схеме; имена из старых заметок
не подтверждают доступность. Наличие плагина само по себе не требует его загрузки.
