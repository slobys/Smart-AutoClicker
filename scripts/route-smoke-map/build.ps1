param(
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Jdk = 'C:/Program Files/Android/Android Studio/jbr',
    [string]$Output = "$PSScriptRoot/../../artifacts/route-smoke-map"
)
$ErrorActionPreference = 'Stop'
$buildTools = Get-ChildItem "$Sdk/build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$platform = Get-ChildItem "$Sdk/platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$androidJar = Join-Path $platform.FullName 'android.jar'
$outDir = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force -Path $outDir, "$outDir/classes", "$outDir/dex" | Out-Null
$env:JAVA_HOME = $Jdk
& "$Jdk/bin/javac.exe" -source 8 -target 8 -classpath $androidJar -d "$outDir/classes" "$PSScriptRoot/RouteMapActivity.java"
if ($LASTEXITCODE) { throw 'javac failed' }
& "$Jdk/bin/jar.exe" cf "$outDir/classes.jar" -C "$outDir/classes" .
if ($LASTEXITCODE) { throw 'jar failed' }
& "$($buildTools.FullName)/d8.bat" --lib $androidJar --min-api 24 --output "$outDir/dex" "$outDir/classes.jar"
if ($LASTEXITCODE) { throw 'd8 failed' }
& "$($buildTools.FullName)/aapt2.exe" link -I $androidJar --manifest "$PSScriptRoot/AndroidManifest.xml" -o "$outDir/map-unsigned.apk"
if ($LASTEXITCODE) { throw 'aapt2 failed' }
& "$Jdk/bin/jar.exe" uf "$outDir/map-unsigned.apk" -C "$outDir/dex" classes.dex
if ($LASTEXITCODE) { throw 'adding dex failed' }
& "$($buildTools.FullName)/zipalign.exe" -f 4 "$outDir/map-unsigned.apk" "$outDir/map-aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }
& "$($buildTools.FullName)/apksigner.bat" sign --ks "$env:USERPROFILE/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$outDir/route-test-map.apk" "$outDir/map-aligned.apk"
if ($LASTEXITCODE) { throw 'signing failed' }
Write-Output "$outDir/route-test-map.apk"
