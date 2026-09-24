<div align="center">

<img alt="Jellyfin banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true" width="640" />

# Jellyfin Thor for Android TV

**Anime-grade subtitles, flexible playback engines, and better diagnostics.**

[![Latest release](https://img.shields.io/github/release-date/thor2002ro/jellyfin-androidtv?label=latest%20release)](https://github.com/thor2002ro/jellyfin-androidtv/releases/latest)
[![License](https://img.shields.io/github/license/thor2002ro/jellyfin-androidtv)](LICENSE)
![Android 7.0+](https://img.shields.io/badge/Android%20TV-7.0%2B-3DDC84?logo=android&logoColor=white)
![Unofficial fork](https://img.shields.io/badge/Jellyfin-unofficial%20community%20fork-orange)

[Download APK](https://github.com/thor2002ro/jellyfin-androidtv/releases/latest)
· [Report an issue](https://github.com/thor2002ro/jellyfin-androidtv/issues/new/choose)
· [Upstream project](https://github.com/jellyfin/jellyfin-androidtv)

</div>

> [!WARNING]
> Jellyfin Thor is an unofficial community fork. It is not supported by the Jellyfin team. Report fork-specific issues in this repository.

Jellyfin Thor is a playback-focused fork of the Jellyfin Android TV client for Android TV, NVIDIA Shield, and compatible Fire TV devices.

## Features

- Media3/ExoPlayer, MPV, libVLC, and external-player support
- Automatic video aspect handling with manual crop and stretch modes
- Direct MPV MediaCodec output plus compatibility and Fast HDR modes for Dolby Vision playback
- Native MPV subtitle overlays for direct HDR video
- First-class ASS/SSA rendering through custom `libass`, including renderer prewarming, HDR-specific text colors, and a configurable 24–60 FPS limit
- Fast keyframe-aware scrubbing with held-button acceleration and trickplay thumbnails
- Hardware, software, and FFmpeg decoder selection with fallback and recovery
- NVIDIA Shield fallbacks for affected H.264 Hi10P and MPEG-2 streams
- Reduced MPV output-buffer pressure for MediaTek-based Fire TV devices
- Accurate DD+ Atmos, TrueHD Atmos, DTS:X, and DTS-HD media badges
- Expanded in-player **Stats for Nerds**
- Live TV startup, guide navigation, recording refresh, buffering, and stream-recovery improvements
- Advanced library filters, reversible sorting, configurable cards, and a dense list view
- Customizable home sections, wide cards, combined Continue Watching and Next Up, and per-row limits
- Dedicated Favorites screen and global BlurHash artwork placeholders
- Automatic disposable-cache cleanup after app updates without removing accounts or settings
- Music Shuffle All and focus restoration when returning to settings
- Side-by-side installation with the official Jellyfin Android TV app

The release package ID is:

```text
org.jellyfin.androidtv.thor
```

## Bundled playback stack

| Component | Current build | Notes |
|-----------|---------------|-------|
| Media3/ExoPlayer | `1.11.1` | Custom source snapshot `2e2fc46d98ca` with selected HDR and Dolby Vision fixes |
| Media3 FFmpeg decoder | FFmpeg `8.1.2` | Shared source revision `c573a95381b0`; includes custom video-decoder and rendering patches |
| MPV Android library | `0.2.2-thor` | Uses FFmpeg `release/8.1`, LibreSSL `4.3.2`, and native subtitle-overlay export |
| libass Android | `0.5.1-thor` | Uses libass `0.17.5`; custom Media3 renderer with prewarming and configurable subtitle FPS |
| libdovi Android | `0.1.1-thor` | Packages the Dolby Vision bridge as an Android AAR and native SDK |
| Native toolchain | NDK r29 (`29.0.14206865`) | Pinned across the app and checked-in media projects |

## Installation

Requirements:

- Android TV 7.0 / API 24 or newer
- A reachable Jellyfin server
- Permission to sideload applications when installing outside an app store

Steps:

1. Open the [latest release](https://github.com/thor2002ro/jellyfin-androidtv/releases/latest).
2. Download the appropriate APK from **Assets**.
3. Transfer and install it on the device.
4. Launch Jellyfin Thor and connect to your server.

> [!CAUTION]
> Builds signed with different keys cannot update one another. Switching between a local build and a GitHub release may require uninstalling the existing app first.

## Building

Requirements:

- Git with submodule support
- JDK 21
- Android Studio or a compatible Android SDK
- Android NDK r29 (`29.0.14206865`)

The current build uses Gradle 9.7.1, Android Gradle Plugin 9.4.1, and Kotlin 2.4.20.

```shell
git clone --recurse-submodules https://github.com/thor2002ro/jellyfin-androidtv.git
cd jellyfin-androidtv
```

Build the debug APK on Windows:

```powershell
.\gradlew.bat assembleDebug
```

Or on Linux and macOS:

```shell
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/
```

Run tests with `.\gradlew.bat test` on Windows or:

```shell
./gradlew test
```

Normal app builds use the checked-in native artifacts and Maven outputs, so rebuilding the playback stack is optional:

```text
dependencies/jellyfin-androidx-media/OUTPUT/maven/
dependencies/libass-android/OUTPUT/
dependencies/libdovi-android/OUTPUT/
dependencies/mpv-android-lib/OUTPUT/maven/
```

### Optional playback stack rebuild

Only rebuild these components when changing their native code or updating their upstream sources. Run them in order because each step consumes artifacts from the earlier steps. The first three Windows commands require WSL2; the Media3 command requires Git Bash, the Android NDK, CMake, and Ninja. All rebuilds require network access.

| Order | Component | Windows | Linux |
|-------|-----------|---------|-------|
| 1 | [`libass-android`](dependencies/libass-android/README.md) | `.\dependencies\libass-android\rebuild-libass-wsl.bat` | `./dependencies/libass-android/rebuild-libass-wsl.sh` |
| 2 | [`libdovi-android`](dependencies/libdovi-android/README.md) | `.\dependencies\libdovi-android\rebuild-libdovi-wsl.bat` | `./dependencies/libdovi-android/rebuild-libdovi-wsl.sh` |
| 3 | [`mpv-android-lib`](dependencies/mpv-android-lib/README.md) | `.\dependencies\mpv-android-lib\build.bat` | `./dependencies/mpv-android-lib/build.sh` |
| 4 | [`jellyfin-androidx-media`](dependencies/jellyfin-androidx-media/README.md) | `.\dependencies\jellyfin-androidx-media\update-repo.bat` | `./dependencies/jellyfin-androidx-media/build.sh` |

Build the app again after the required artifacts have been refreshed.

## Related projects

- [`libass-android`](https://github.com/thor2002ro/libass-android) — Shared native libass provider plus Kotlin/JNI and Media3 ASS/SSA rendering modules
- [`libdovi-android`](https://github.com/thor2002ro/libdovi-android) — Android AAR and native SDK for Dolby Vision metadata inspection and conversion
- [`mpv-android-lib`](https://github.com/thor2002ro/mpv-android-lib) — Custom MPV build with shared libass, Dolby Vision support, FFmpeg provider artifacts, HDR output, and native subtitle overlays
- [`jellyfin-androidx-media`](https://github.com/thor2002ro/jellyfin-androidx-media) — Custom Media3 build with shared FFmpeg video decoding and direct native-surface rendering

## License

Based on [`jellyfin/jellyfin-androidtv`](https://github.com/jellyfin/jellyfin-androidtv) and distributed under the **GNU General Public License v2.0**. See [LICENSE](LICENSE).

Jellyfin Thor is not affiliated with, endorsed by, or supported by the Jellyfin project.
