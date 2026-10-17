param([ValidateSet('dump','click','key','swipe','shot')] [string]$Action = 'dump', [string]$Text = '', [int[]]$Coordinates, [string]$Output = '')
$ErrorActionPreference = 'Stop'
$adbPath = Join-Path $PSScriptRoot '../../.tools/platform-tools/adb.exe'
# Intentionally fixed: this helper must never operate on the physical TV.
$device = 'emulator-5554'
if ($Action -eq 'key') { & $adbPath -s $device shell input keyevent $Text; exit }
if ($Action -eq 'swipe') { & $adbPath -s $device shell input swipe @Coordinates; exit }
if ($Action -eq 'shot') {
    if (!$Output) { throw 'Output required' }
    & $adbPath -s $device shell screencap -p /sdcard/bobyl-test.png
    & $adbPath -s $device pull /sdcard/bobyl-test.png $Output
    exit
}
$dumpResult = (& $adbPath -s $device shell uiautomator dump /sdcard/bobyl-audit.xml) -join "`n"
if ($dumpResult -notmatch 'dumped to:') { throw 'Fresh UI snapshot unavailable; refusing to use stale coordinates' }
[xml]$ui = (& $adbPath -s $device shell cat /sdcard/bobyl-audit.xml) -join "`n"
if ($Action -eq 'dump') {
    $ui.SelectNodes('//node') | Where-Object { $_.password -ne 'true' -and ($_.text -ne '' -or $_.'content-desc' -ne '' -or $_.focused -eq 'true') } |
        ForEach-Object { "{0} {1} focus={2} label={3}" -f $_.text, $_.bounds, $_.focused, $_.'content-desc' }
    exit
}
$node = $ui.SelectNodes('//node') | Where-Object { ($_.text -eq $Text -or $_.'content-desc' -eq $Text) -and $_.password -ne 'true' } | Select-Object -First 1
if (!$node) { throw "UI text not found: $Text" }
while ($node -and $node.clickable -ne 'true') { $node = $node.ParentNode }
if (!$node -or !$node.bounds) { throw "No clickable parent: $Text" }
$bounds = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
& $adbPath -s $device shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2))
