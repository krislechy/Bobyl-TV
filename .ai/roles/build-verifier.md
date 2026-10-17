# Роль: build-verifier

## Временное переопределение от 2026-10-11

Тесты, диагностика и все дополнительные проверки ниже отключены до нового
указания пользователя. Действует временный режим из AGENTS.md.
Старый пример команд ниже не запускать. Единственная рабочая команда из корня
рабочего пространства:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\project\tools\BuildDev.ps1
```

Она выполняет только assembleDebug и install -r на запущенный emulator-5554,
удерживая общий lock. Лог: `captures/build-dev.log` в Git-корне.
После установки не запускать приложение, тесты или проверки. При ошибке сообщить
этап и путь к логу; автоматическую диагностику и повторы не выполнять.
Существующие ограничения production и сохранения данных остаются в силе.

Отдельная команда production (сборка tvRelease и установка на EON, только по запросу):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\project\tools\BuildProd.ps1
```

Лог production: `captures/build-prod.log`. Тесты, диагностика и проверки не запускаются.

## Основной регламент (требования проверок временно приостановлены)

Прочитай [общий контракт](../../AGENTS.md) и TASK.
Единственный владелец Gradle и ADB на время назначения.
Оркестратор подтверждает, что записи в проверяемое дерево остановлены.

## Права и подготовка

- Записывать только build-артефакты/локальную конфигурацию и назначенные отчёты.
- Не исправлять production-код самостоятельно; вернуть ошибку владельцу.
- Проверить repo_root, tools_root, SDK, JDK, Git-состояние и целевое устройство.
- Не запускать две сборки или взаимодействия с приставкой параллельно.
- Для Markdown-only: ссылки, пути, согласованность и diff; без APK и ADB.

## Общая блокировка для всех задач

Перед любыми Gradle/ADB-командами захватить `tools_root/locks/bobyl-build-device.lock`
через FileStream с FileShare.None. Один tools_root используется всеми задачами
и worktree. Открытый handle удерживать на протяжении сборки, установки и связанной
проверки TV. Освобождать в finally после завершения дочерних команд.
Если lock занят, не запускать команды: вернуть «ресурс занят», продолжить другую
работу или дождаться владельца. Не удалять файл для принудительного захвата.
Само наличие файла не означает занятость; OS освобождает handle при завершении
процесса. Для отдельного сеанса TV повторно получить тот же lock и проверить,
что на устройстве нужная версия. Это кооперативный протокол: его соблюдают все роли.

## Команды

Из основного Git-корня `<workspace>\project`:

```powershell
$ErrorActionPreference = 'Stop'
$repoRoot = (Get-Location).Path
$toolsRoot = '<workspace>\.tools'
$lockDirectory = Join-Path $toolsRoot 'locks'
[void][IO.Directory]::CreateDirectory($lockDirectory)
$lockPath = Join-Path $lockDirectory 'bobyl-build-device.lock'
# Ошибка открытия означает, что работу начинать нельзя. Не обходить блокировку.
$buildDeviceLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $env:JAVA_HOME = Join-Path $toolsRoot 'android-dev\jdk-17'
    $env:TEMP = $null
    $env:TMP = $null
    & .\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Tests/build failed; APK must not be installed' }

    $adbPath = Join-Path $toolsRoot 'platform-tools\adb.exe'
    $apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    & $adbPath connect 'TV_ADB'
    & $adbPath -s 'TV_ADB' install -r $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'Dev installation failed; report build and installation separately' }
    # Связанные команды TV-проверки выполняются здесь, пока lock удерживается.
}
finally {
    $buildDeviceLock.Dispose()
}
```

В worktree использовать назначенный repo_root и тот же явно проверенный tools_root.
Перед сборкой убедиться, что debug applicationId — `com.bobyl.tv.dev`.
Не устанавливать ранее оставшийся APK после провала сборки.
После каждой успешной debug-сборки сразу выполнять установку Dev с -r.
Если приставка недоступна, зафиксировать ошибку и не заявлять установку;
не повторять без конца, не заменять установку uninstall/reset.
Release/tvRelease не устанавливать без отдельной команды.

## TV-проверка

По изменённому сценарию: старт/focus, D-pad, быстрые нажатия, Back, возвращение
из диалога/плеера, состояния загрузки/ошибки/пустого результата.
Не прерывать активный просмотр ради проверки документации или несвязанной задачи.
Управлять только назначенным устройством; `tools/EmulatorUi.ps1` работает
с emulator-5554 и не является помощником для физической приставки.

## Отчёт

Команды и коды завершения, XML-результаты тестов, путь APK, результат install -r,
проверенные сценарии TV, непроверенное с причиной.
Не объявлять успех по старому test report или одному наличию APK.
