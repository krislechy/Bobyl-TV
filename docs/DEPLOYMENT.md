# Деплой Bobyl TV

Команды выполняются из корня рабочего пространства (родителя `project/`).
Каждый скрипт собирает выбранную версию и устанавливает APK через `adb install -r`,
сохраняя данные приложения. Все команды используют общий lock Gradle/ADB;
тесты, запуск приложения и дополнительные проверки не выполняются.

| Версия и устройство | Команда | Лог в `project/captures/` |
|---|---|---|
| Прод, Android TV EON | `powershell -NoProfile -ExecutionPolicy Bypass -File .\project\tools\DeployProdTv.ps1` | `build-prod.log` |
| DEV, Android TV EON | `powershell -NoProfile -ExecutionPolicy Bypass -File .\project\tools\DeployDevTv.ps1` | `build-dev-tv.log` |
| Прод, эмулятор | `powershell -NoProfile -ExecutionPolicy Bypass -File .\project\tools\DeployProdEmulator.ps1` | `build-prod-emulator.log` |
| DEV, эмулятор | `powershell -NoProfile -ExecutionPolicy Bypass -File .\project\tools\DeployDevEmulator.ps1` | `build-dev.log` |

Телевизор: `TV_ADB`; скрипт подключается к нему автоматически.
Эмулятор: заранее запущенный `emulator-5554`.
Прод: `com.bobyl.tv`, вариант `tvRelease`. DEV: `com.bobyl.tv.dev`, вариант `debug`,
с красной лентой DEV на значке и TV-плитке.

Совместимые прежние команды: `BuildProd.ps1` устанавливает прод на телевизор,
`BuildDev.ps1` — DEV на эмулятор. Параметр `-Target Tv` или `-Target Emulator`
позволяет выбрать устройство явно. В случае ошибки скрипт возвращает код 1,
указывает этап и путь к логу; автоматических повторов и удаления приложения нет.

Установка на телевизор и production-деплой выполняются только по явному запросу.
