param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Screenshot,
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk"
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$testPackage = 'com.buzbuz.smartautoclicker.core.detection.test'
function Invoke-TestAdb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
}

# Only the isolated instrumentation package is touched; install its APK first.
Invoke-TestAdb shell run-as $testPackage id
$cache = Join-Path $env:LOCALAPPDATA 'KlickrBuild\chinese-ocr-fixtures-1.0.0'
New-Item -ItemType Directory -Path $cache -Force | Out-Null
$archive = Join-Path $cache 'chinese_simplified.zip'
$expectedHash = 'F926054E3CF3F7CD937FFD8A87058D9B257EE2FEB4AF9DB67568E949CAC5F205'
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri 'https://github.com/Nain57/Smart-AutoClicker/releases/download/recognition-models-1.0.0/chinese_simplified.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
    throw "Unexpected model archive hash at $archive; nothing was extracted or installed."
}
$model = Join-Path $cache 'model'
Expand-Archive -LiteralPath $archive -DestinationPath $model -Force
Invoke-TestAdb shell run-as $testPackage mkdir -p files/ocr-fixtures/chinese_simplified
foreach ($name in @('dict.txt', 'rec.ncnn.param', 'rec.ncnn.bin')) {
    # Explicit destination names avoid Windows adb Unicode-directory filename truncation.
    $remote = "/data/local/tmp/klickr-ocr-$name"
    Invoke-TestAdb push (Join-Path $model $name) $remote
    Invoke-TestAdb shell run-as $testPackage cp $remote "files/ocr-fixtures/chinese_simplified/$name"
}
if ($Screenshot) {
    $source = (Resolve-Path -LiteralPath $Screenshot).Path
    Invoke-TestAdb push $source '/data/local/tmp/klickr-ocr-screen.png'
    Invoke-TestAdb shell run-as $testPackage cp '/data/local/tmp/klickr-ocr-screen.png' 'files/ocr-fixtures/screen.png'
}
Write-Host 'Chinese model fixtures prepared. Screenshots stay local and are not bundled in any APK.'
