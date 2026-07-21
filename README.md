<div align="center">

<img alt="Jellyfin banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true" width="640" />

# Jellyfin Thor for Android TV

**Anime-grade subtitles, flexible playback engines, and better diagnostics.**

[![Latest release](https://img.shields.io/github/release-date/thor2002ro/jellyfin-androidtv?label=latest%20release)](https://github.com/thor2002ro/jellyfin-androidtv/releases/latest)
[![License](https://img.shields.io/github/license/thor2002ro/jellyfin-androidtv)](LICENSE)
![Android 6.0+](https://img.shields.io/badge/Android%20TV-6.0%2B-3DDC84?logo=android&logoColor=white)
![Unofficial fork](https://img.shields.io/badge/Jellyfin-unofficial%20community%20fork-orange)

[Download APK](https://github.com/thor2002ro/jellyfin-androidtv/releases/latest)
· [Report an issue](https://github.com/thor2002ro/jellyfin-androidtv/issues/new/choose)
· [Upstream project](https://github.com/jellyfin/jellyfin-androidtv)

</div>

> [!WARNING]
> Jellyfin Thor is an unofficial community fork. It is not supported by the Jellyfin team. Report fork-specific issues in this repository.

Jellyfin Thor is a playback-focused fork of the Jellyfin Android TV client for Android TV, NVIDIA Shield, and compatible Fire TV devices.

## Features

- First-class ASS/SSA subtitle rendering through a custom `libass` integration
- Media3/ExoPlayer, LibVLC, and external-player support
- Hardware, software, and FFmpeg decoder selection with fallback and recovery
- Expanded in-player **Stats for Nerds**
- Live TV startup, buffering, and stream-recovery improvements
- Device-specific MPEG-TS and decoder workarounds
- Side-by-side installation with the official Jellyfin Android TV app

The release package ID is:

```text
org.jellyfin.androidtv.thor
```

## Installation

Requirements:

- Android TV 6.0 / API 23 or newer
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

The build requires the custom Media3/FFmpeg decoder artifact:

```text
dependencies/jellyfin-androidx-media/OUTPUT/media3-ffmpeg-decoder-latest-SNAPSHOT.aar
```

See [`dependencies/jellyfin-androidx-media/README.md`](dependencies/jellyfin-androidx-media/README.md) if the artifact must be rebuilt.

## Related projects

- [`libass-android`](https://github.com/thor2002ro/libass-android) — Android libass build and Media3 ASS/SSA renderer
- [`jellyfin-androidx-media`](https://github.com/thor2002ro/jellyfin-androidx-media) — Custom Media3 build with FFmpeg video decoding

## License

Based on [`jellyfin/jellyfin-androidtv`](https://github.com/jellyfin/jellyfin-androidtv) and distributed under the **GNU General Public License v2.0**. See [LICENSE](LICENSE).

Jellyfin Thor is not affiliated with, endorsed by, or supported by the Jellyfin project.
