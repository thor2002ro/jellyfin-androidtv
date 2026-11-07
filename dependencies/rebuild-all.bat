@echo off
setlocal

rem libass ──┐
rem          ├─^> MPV ─^> Media3 ─^> Jellyfin app
rem libdovi ─┘            │
rem                       └─^> custom FFmpeg decoder
rem.
rem libass Media3 adapter ─────────^> Jellyfin app
rem libdovi Android module ────────^> Jellyfin app
call "%~dp0libass-android\rebuild-libass-wsl.bat"
if errorlevel 1 exit /b %errorlevel%

call "%~dp0libdovi-android\rebuild-libdovi-wsl.bat"
if errorlevel 1 exit /b %errorlevel%

call "%~dp0mpv-android-lib\build.bat"
if errorlevel 1 exit /b %errorlevel%

call "%~dp0jellyfin-androidx-media\update-repo.bat"
if errorlevel 1 exit /b %errorlevel%

pushd "%~dp0.."
if errorlevel 1 exit /b %errorlevel%
call gradlew.bat assembleDebug
set "BUILD_EXIT=%ERRORLEVEL%"
popd
exit /b %BUILD_EXIT%
