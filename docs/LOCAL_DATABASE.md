# Локальная база данных

Основное хранилище — Room/SQLite, файл `bobyl.db`, версия схемы1.
API23+, Room2.8.5. Схема экспортируется KSP в `app/schemas/`.
Проверки схемы, запуска и импорта находятся в
[database tests](../app/src/test/java/com/bobyl/tv/data/database/).

## Стартовая инициализация

Android не исполняет код приложения непосредственно при установке APK.
При первом запуске `BobylApplication` начинает process-owned IO initialization.
Room builder сам по себе ленивый: `AppStorage` явно открывает writable database,
создаёт всю схему, импортирует legacy данные одной SQL-транзакцией и устанавливает
helpers. Только затем публикуется Ready и монтируется `BobylTvApp` с рабочими VM.

На ожидании остаётся прежний illustrated startup screen, без skeleton и второго
минимального ожидания. Ошибка открытия/импорта даёт конечное состояние с Retry;
destructive migration, удаление БД и обход gate не используются.
File-index reconciliation выполняется отдельно и не блокирует готовность схемы.

## Таблицы

Все10 таблиц создаются при первом открытии до использования отдельных функций.
Room дополнительно создаёт собственную таблицу идентичности схемы.

| Таблица | Ключ / область | Назначение |
|---|---|---|
| storage_meta | key | Версия импорта и служебные отметки |
| films | provider + filmKey | Метаданные CatalogFilm, запасной источник названия/постера истории |
| film_details | provider + filmKey | Версионированный JSON публичных подробностей, без streams/auth/bookmarks/personal score |
| watch_history | provider + recordId | Точные поля WatchRecord, порядок и лимит500/provider |
| personal_ratings | provider + owner + filmId | Локальная оценка аккаунта; owner — guest либо стабильный hash server user ID |
| legacy_ratings_archive | originalKey | Старые оценки без известного владельца, без автоматической атрибуции |
| playback_selections | filmId | Последние voice/season/episode; сохраняется прежняя общая filmId-only область |
| legacy_import_archive | source + originalKey | Полный исходный текст и причина неполного/повреждённого legacy импорта |
| cache_entries | key | Версионированные DTO, owner/provider, TTL, размер и LRU metadata |
| local_files | id | Путь/URI/тип/размер/статус/DownloadManager ID, без содержимого файла |

Таблицы имеют собственные ключи и индексы, не FK/cascade-связи.
Общий рейтинг сайта и число голосов не являются личной оценкой пользователя.

## Импорт старых данных

Читаются `watch_history`, `personal_ratings` и lastVoice/lastSeason/lastEpisode
из `${packageName}_preferences`. Original SharedPreferences не удаляются;
после импорта новые записи этих категорий выполняются в БД.

Импорт сохраняет provider-only историю, account+provider оценки v2 и filmId-only
playback selections. Неизвестный владелец старой оценки не угадывается.
Повреждённые источники архивируются с raw-текстом и причиной вместо тихого удаления.
SQL-изменения и completion marker входят в одну транзакцию; повтор после ошибки
идемпотентен, существующие SQL-записи не перезаписываются legacy значениями.

## Кэш и файлы

Постоянный кэш содержит подготовленные модели каталога/browse, избранного,
обновлений и фильтров. HTML, формы, cookies, streaming credentials туда не входят.
Ключ учитывает operation, hashed URL, provider и стабильного owner; payload versioned.
Лимит128 записей /16MiB суммарно /4MiB payload. Истёкшие записи не скрывают сетевую
ошибку. RAM refresh не уничтожает пригодный дисковый кэш при перезапуске.
Явная очистка затрагивает RAM и persistent entries, не durable user tables/файлы.
Перед очисткой отдельным commit сохраняется технический quarantine marker.
Если очистка не удалась, новый controller блокирует чтение/запись старого кэша,
пока успешная очистка не удалит marker. Marker защищён от LRU eviction и входит
в квоту. При одновременном отказе всех записей durability не обещается.

DownloadManager enqueue не означает готовый файл. Registry сверяется с реальным
состоянием загрузки и доступностью файла; пользовательские файлы не удаляются.
Для локальных субтитров и APK отслеживаются завершённые/проверенные файлы;
ошибка вспомогательного индекса не откатывает уже принятую загрузку.
FileProvider продолжает выдавать URI существующих папок, не хранит копии в БД.

## Что осталось вне БД

- Простые общие настройки — `${packageName}_preferences`.
- Авторизация — AES/GCM в `noBackupFilesDir`, ключ Android Keystore.
- Cookies/DOM storage — Android WebView.
- Видео, изображения, APK и субтитры — прежние файлы/SDK caches.
- UI SavedState/Bundle, focus, runtime relay и принятые команды — прежние механизмы.

Приватный файл БД разделён пакетами Dev/Production. Общие пользовательские папки
Downloads/Documents не получают автоматического account/provider ownership.
Текущие backup rules сохраняются: БД с user data может участвовать в OS backup;
auth остаётся no_backup, app_webview исключён. Фактическое облачное резервирование
и поведение на устройстве не следуют из host SQLite тестов.

## Подтверждённые проверки

Полный host unit/native SQLite прогон:198 уникальных тестов PASS.
После type-only уточнения Application в file registry отдельно повторены
7 file-index +3 startup теста, все PASS; full suite повторно не запускалась.
Финальный lint:46 Warning,0 Error,0 Hint. Robolectric4.17 /SDK28 /JDK17 наWindows,
generated production Room schema, файловые reopen/rollback/migration tests.
Physical SQLite file+все10 таблиц проверяются независимо read-only open послеReady.
Это не запуск установленного Android-приложения и не device/UI-приёмка.
