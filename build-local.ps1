param(
 [string]$JavaHome=$env:JAVA_HOME,
 [string]$AndroidSdk=$env:ANDROID_HOME,
 [string]$Python='python',
 [string]$BuildRoot='',
 [ValidateSet('arm64-v8a','x86_64')][string]$Abi='arm64-v8a',
 [switch]$RunTests,
 [switch]$Offline
)
$ErrorActionPreference='Stop'
if(!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))){throw 'Set JAVA_HOME or pass -JavaHome to your Android Studio JDK.'}
if(!$AndroidSdk){$AndroidSdk=$env:ANDROID_SDK_ROOT}
if(!$AndroidSdk -or !(Test-Path -LiteralPath $AndroidSdk)){throw 'Set ANDROID_HOME or pass -AndroidSdk.'}
if(!$BuildRoot){$BuildRoot=Join-Path ([IO.Path]::GetTempPath()) 'dapan-assistant-build-1.0.0'}
$BuildRoot=[IO.Path]::GetFullPath($BuildRoot)
$env:JAVA_HOME=$JavaHome
$env:ANDROID_HOME=$AndroidSdk
Push-Location $PSScriptRoot
try {
 & $Python scripts/component-catalogue.py
 if($LASTEXITCODE -ne 0){throw 'Data component integrity generation failed.'}
 $buildArgs=@(':app:assembleDebug',"-PdapanAbi=$Abi","-PdapanBuildRoot=$BuildRoot",'--console=plain')
 if($RunTests){$buildArgs+= ':app:testDebugUnitTest'}
 if($Offline){$buildArgs+='--offline'}
 & ./gradlew.bat @buildArgs
 if($LASTEXITCODE -ne 0){throw 'Android build or tests failed.'}
 $releaseDir=Join-Path $PSScriptRoot 'release'
 New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
 $version=[regex]::Match((Get-Content app/build.gradle.kts -Raw),'versionName\s*=\s*"([^"]+)"').Groups[1].Value
 if(!$version){throw 'Missing app version.'}
 $apk=Join-Path $releaseDir "DapanAssistant-$version-$Abi-debug.apk"
 Copy-Item -LiteralPath (Join-Path $BuildRoot 'app/outputs/apk/debug/app-debug.apk') -Destination $apk -Force
 Write-Output "APK: $apk"
 Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {Pop-Location}
