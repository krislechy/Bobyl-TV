# Исторический контекст до переноса проекта

Архив на 15.09.2026. Проект перенесён в `<workspace>`.
Пути и порядок продолжения ниже сохранены для справки; актуальные инструкции — в корневом `AGENTS.md`.

## Проект и репозиторий

- Рабочая папка: `<local-path>`
- Новый Gradle/Kotlin Android TV проект на Compose for TV.
- GitHub: `https://github.com/krislechy/Bobyl-TV`
- Репозиторий private, ветка `main`.
- Последний опубликованный коммит на момент создания файла: `56a5b40`.

## Устройство и установка

- Приставка: EON Smart Box CHSTB02, Android 12.
- ADB: `TV_ADB`
- ADB executable: `<local-path>`
- Debug package: `com.bobyl.tv.dev`, label `Бобыль Dev`.
- Release package: `com.bobyl.tv`, label `Бобыль`.
- После каждой успешной debug-сборки сразу устанавливать Dev через `adb install -r`, без дополнительного вопроса.
- `install -r` обязателен: сохранять авторизацию, настройки, историю и локальные данные.
- Release на приставку не устанавливать без отдельной команды.
- Основную версию `com.bobyl.tv` не удалять и не изменять без явного запроса.

## Сборка

```powershell
$env:JAVA_HOME='<local-path>'
$env:TEMP=$null
$env:TMP=$null
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

## Исходные reference-материалы

- Оригинальный APK и reference-копия хранятся в Git в соответствующей папке.
- Декомпилированные исходники оригинала: `.tools\hdrezka-src\sources\com\falcofemoralis\hdrezkaapp`
- Очищенный smali/reference: `.tools\hdrezka-original-clean`
- Исходники MX Player 3.2.1: `.tools\mx321-src`
- Оригинальный smali-проект не использовать как конечную реализацию; Kotlin/Gradle-код поддерживать отдельно.

## Рабочие правила

- Не раскрывать логины, пароли, cookies, токены и содержимое зашифрованных сессий.
- Не добавлять лишние сетевые запросы.
- Не удалять пользовательские данные и не делать reset/uninstall без явной команды.
- Перед изменениями проверять `git status`; существующие изменения считать пользовательскими и сохранять.
- Использовать `apply_patch` для редактирования файлов.
- После изменений запускать unit-тесты и debug-сборку.
- При диагностике приставки не прерывать активный просмотр без необходимости.

## Реализованная функциональность

### UI и навигация

- Современный тёмный cinematic UI в стиле premium streaming-сервиса.
- Левое D-pad меню.
- Крупные постеры, backdrop, hero/detail layout.
- Focus scale и заметный focus state.
- Разделы: популярное, новинки, горячие новинки, категории, поиск, избранное, продолжить, новые серии, настройки.
- Строки каталога ограничены настройкой количества карточек, обычно 6.

### Авторизация

- Логин/пароль и регистрация находятся в настройках.
- Cookies сохраняются зашифрованно через Android Keystore.
- На главном экране выполняется только восстановление cookies.
- Сетевая проверка профиля выполняется внутри настроек авторизации и не должна блокировать каталог.

### Каталог и производительность

- Сетевые операции выполняются асинхронно.
- HTML-разбор и объединение карточек выполняются вне UI-потока.
- Каталог загружается постранично.
- Следующая страница запрашивается в фоне при приближении к нижним строкам.
- `CatalogViewModel` сериализует запросы и не допускает дубликатов.
- В рамках последней оптимизации новый кэш не добавлять и существующую кэш-логику не менять.
- Для строк каталога используется обычный `Row`, а не вложенный `LazyRow`, чтобы уменьшить вертикальный focus/layout lag.
- Для строк задан стабильный `contentType`.
- Остался небольшой лаг при движении вверх/вниз; анализировать только focus/layout/отрисовку, не сеть.

### Поля ввода и клавиатура

- Все поля используют общий `CinematicFields.kt` / `Input`.
- Поля: поиск, логин, пароль, регистрация, комментарии, зеркало, создание списка избранного.
- Получение focus от D-pad/автовосстановления не должно открывать клавиатуру.
- Клавиатура открывается только после клика по самому полю или нажатия OK/Enter на нём.

### MX Player и playlist hook (только Debug)

- `EpisodeRelayService` и foreground permissions присутствуют только в debug manifest.
- Hook работает внутри процесса Bobyl, отдельный сервис не устанавливается.
- Loopback bind: `127.0.0.1`, порт выбирается автоматически на сессию.
- MX получает локальные URL плейлиста и при нажатии Next/Previous обращается к hook.
- Hook запрашивает только нужную серию через исходный API.
- Весь сезон заранее не запрашивается.
- Один запрос API серии содержит видео и субтитры.
- HEAD/GET одной серии используют общий кэш результата на 60 секунд.
- Ошибки не должны запускать бесконечные повторы.
- Результат MX сопоставляется с номером серии; неизвестный URI не должен перезаписывать прогресс.
- Режим включается галочкой в настройках Dev и применяется к сериалам с MX.

### Субтитры

- Серверные субтитры передаются как раньше и не заменяются.
- Для playlist-сериалов выбранная дорожка разрешается отдельно для каждой серии через hook.
- MX 3.2.1 игнорирует `content://` в `subs`, поэтому локальные дополнительные дорожки отдаются через loopback HTTP.
- Локальные дополнительные субтитры поддерживаются только для фильмов.
- Фиксированная папка:

  `Documents/Bobyl/Subtitles/films/{ID}/`

- Пример:

  `Documents/Bobyl/Subtitles/films/88449/zpn9sfno08s4.ru.vtt`

- Поддерживаются `.srt`, `.ass`, `.ssa`, `.vtt`.
- Папка и `films` проверяются/создаются при запуске приложения.
- На Android 12 нужен `MANAGE_EXTERNAL_STORAGE`; в настройках есть переход на системный экран разрешения.
- Локальные дорожки добавляются к серверным через `subs`, `subs.name`, `subs.filename`.
- Ограничения локального HTTP-сервера: максимум 32 файла, 4 MiB на файл, 16 MiB суммарно.
- Файлы не загружаются в интернет и не требуют API-запросов.

## Важные файлы

- Каталог/UI: `app/src/main/java/com/bobyl/tv/ConnectedCatalog.kt`
- ViewModel каталога: `app/src/main/java/com/bobyl/tv/CatalogViewModel.kt`
- Поля ввода: `app/src/main/java/com/bobyl/tv/CinematicFields.kt`
- Activity: `app/src/main/java/com/bobyl/tv/MainActivity.kt`
- Каталог repository/parser: `app/src/main/java/com/bobyl/tv/data/CatalogRepository.kt`, `Catalog.kt`
- Playback: `app/src/main/java/com/bobyl/tv/PlaybackDialog.kt`, `PlaybackSettings.kt`
- Playlist service: `app/src/main/java/com/bobyl/tv/EpisodeRelayService.kt`
- Episode hook: `app/src/main/java/com/bobyl/tv/data/EpisodeRelay.kt`
- Local subtitle server: `app/src/main/java/com/bobyl/tv/data/LocalSubtitleServer.kt`
- Local subtitle discovery: `app/src/main/java/com/bobyl/tv/data/CustomSubtitles.kt`
- Subtitle settings: `app/src/main/java/com/bobyl/tv/CustomSubtitleSettings.kt`
- FileProvider paths: `app/src/main/res/xml/file_paths.xml`

## Тесты и текущий статус

- Запуск тестов: `.:\gradlew.bat --no-daemon :app:testDebugUnitTest`
- Последний известный результат: BUILD SUCCESSFUL, все debug unit tests проходят.
- После последнего изменения Dev-билд установлен на приставку через `install -r`.
- Остаточная задача: уменьшить оставшийся лаг вертикального D-pad-перехода в каталоге, не ухудшив горизонтальную навигацию и не меняя сетевую/кэш-логику.

## Порядок продолжения

1. Проверить `git status` и текущую ветку.
2. Проанализировать вертикальный лаг в Compose focus/layout/animation.
3. Внести минимальное исправление через `apply_patch`.
4. Запустить `:app:testDebugUnitTest` и `:app:assembleDebug`.
5. Сразу установить `app-debug.apk` на `TV_ADB` через `adb install -r`.
6. В финале сообщить, что изменено, результат сборки и установки.
