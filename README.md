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
- Direct MPV MediaCodec output for compatible HDR and Dolby Vision playback
- Native MPV subtitle overlays for direct HDR video
- First-class ASS/SSA rendering through custom `libass`, including renderer prewarming and a configurable 24–60 FPS limit
- Fast keyframe-aware scrubbing with held-button acceleration
- Hardware, software, and FFmpeg decoder selection with fallback and recovery
- NVIDIA Shield fallbacks for affected H.264 Hi10P and MPEG-2 streams
- Reduced MPV output-buffer pressure for MediaTek-based Fire TV devices
- Accurate DD+ Atmos, TrueHD Atmos, DTS:X, and DTS-HD media badges
- Expanded in-player **Stats for Nerds**
- Live TV startup, buffering, and stream-recovery improvements
- Music Shuffle All, improved library sorting, and focus restoration when returning to settings
- Side-by-side installation with the official Jellyfin Android TV app

The release package ID is:

```text
org.jellyfin.androidtv.thor
```

## Bundled playback stack

| Component | Current build | Notes |
|-----------|---------------|-------|
| Media3/ExoPlayer | `1.11.0` | Custom source snapshot `1.11.0-200-g835628b4c0ce` with selected HDR and Dolby Vision fixes |
| Media3 FFmpeg decoder | FFmpeg `8.0.git` | Source revision `f944afd04097`; includes custom video-decoder and rendering patches |
| MPV Android library | `0.2.1-thor` | Uses FFmpeg `release/8.1`, LibreSSL `4.3.2`, and native subtitle-overlay export |
| libass Android | `0.5.0-thor` | Custom Media3 renderer with prewarming and configurable subtitle FPS |
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

The current build uses Gradle 9.6.1, Android Gradle Plugin 9.3.1, and Kotlin 2.4.10.

```shell
git clone --recurse-submodules https://github.com/thor2002ro/jellyfin-androidtv.git
cd jellyfin-androidtv
./gradlew assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/
```

Run tests with:

```shell
./gradlew test
```

The application resolves its custom Media3 and MPV libraries from the checked-in Maven outputs:

```text
dependencies/jellyfin-androidx-media/OUTPUT/maven/
dependencies/mpv-android-lib/OUTPUT/maven/
```

The `libass-android` submodule is included as a Gradle build, so a recursive clone contains the sources and artifacts required for a normal application build. See the subproject documentation when rebuilding native media components:

- [`jellyfin-androidx-media`](dependencies/jellyfin-androidx-media/README.md)
- [`libass-android`](dependencies/libass-android/README.md)
- [`mpv-android-lib`](dependencies/mpv-android-lib/README.md)

## Related projects

- [`libass-android`](https://github.com/thor2002ro/libass-android) — Android libass build and Media3 ASS/SSA renderer
- [`jellyfin-androidx-media`](https://github.com/thor2002ro/jellyfin-androidx-media) — Custom Media3 build with FFmpeg video decoding
- [`mpv-android-lib`](https://github.com/thor2002ro/mpv-android-lib) — Custom Android MPV build with HDR and native subtitle-overlay support

## License

Based on [`jellyfin/jellyfin-androidtv`](https://github.com/jellyfin/jellyfin-androidtv) and distributed under the **GNU General Public License v2.0**. See [LICENSE](LICENSE).

Jellyfin Thor is not affiliated with, endorsed by, or supported by the Jellyfin project.
