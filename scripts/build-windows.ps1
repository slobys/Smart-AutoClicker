param(
    [string[]]$Tasks = @(':smartautoclicker:assembleFDroidDebug'),
    [string]$JavaHome,
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [ValidatePattern('^[D-Zd-z]$')][string]$DriveLetter = 'K'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (!$JavaHome) {
    $candidates = @($env:JAVA_HOME) + @(Get-ChildItem "$env:LOCALAPPDATA\KlickrBuild" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    $JavaHome = $candidates | Where-Object {
        $_ -and (Test-Path -LiteralPath "$_\release") -and
        (Select-String -LiteralPath "$_\release" -Pattern '^JAVA_VERSION="21[."]' -Quiet)
    } | Select-Object -First 1
}
if (!$JavaHome -or !(Test-Path -LiteralPath "$JavaHome\bin\java.exe") -or
    !(Select-String -LiteralPath "$JavaHome\release" -Pattern '^JAVA_VERSION="21[."]' -Quiet)) {
    throw 'JDK 21 is required. Supply -JavaHome <JDK 21 directory>; the global Java installation is not changed.'
}
if ((!(Test-Path -LiteralPath "$AndroidSdk\platforms\android-37") -and
    !(Test-Path -LiteralPath "$AndroidSdk\platforms\android-37.0")) -or
    !(Test-Path -LiteralPath "$AndroidSdk\ndk\28.2.13676358") -or
    !(Test-Path -LiteralPath "$AndroidSdk\cmake\3.22.1")) {
    throw 'Install Android SDK 37, NDK 28.2.13676358 and CMake 3.22.1, or supply -AndroidSdk.'
}

$drive = $DriveLetter.ToUpperInvariant() + ':'
$mappedHere = $false
$oldJava = $env:JAVA_HOME
$oldSdk = $env:ANDROID_HOME
$oldSdkRoot = $env:ANDROID_SDK_ROOT
$pushed = $false
try {
    # SUBST only adds a temporary alias. Never copy, delete or rename source files.
    $existing = @(& subst.exe) | Where-Object { $_.StartsWith($drive + '\:') }
    if ($existing) {
        $target = ($existing -split ' => ', 2)[1]
        if ($target.TrimEnd('\') -ne $repoRoot.TrimEnd('\')) {
            throw "$drive is mapped to another directory; use -DriveLetter with a free letter."
        }
    } elseif (Test-Path -LiteralPath "$drive\") {
        throw "$drive is in use; use -DriveLetter with a free letter."
    } else {
        & subst.exe $drive $repoRoot
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the temporary ASCII build path.' }
        $mappedHere = $true
    }

    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $AndroidSdk).Path
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    Push-Location "$drive\"
    $pushed = $true
    Write-Host "Building from $drive\ with JDK 21: $env:JAVA_HOME"
    & .\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
} finally {
    if ($pushed) { Pop-Location }
    $env:JAVA_HOME = $oldJava
    $env:ANDROID_HOME = $oldSdk
    $env:ANDROID_SDK_ROOT = $oldSdkRoot
    if ($mappedHere) { & subst.exe $drive /D }
}
