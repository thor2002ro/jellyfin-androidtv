#!/usr/bin/env bash
set -euo pipefail

dependencies_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${dependencies_dir}/.." && pwd)"

# libass ──┐
#          ├─> MPV ─> Media3 ─> Jellyfin app
# libdovi ─┘            │
#                       └─> custom FFmpeg decoder
#
# libass Media3 adapter ─────────> Jellyfin app
# libdovi Android module ────────> Jellyfin app
"${dependencies_dir}/libass-android/rebuild-libass-wsl.sh"
"${dependencies_dir}/libdovi-android/rebuild-libdovi-wsl.sh"
"${dependencies_dir}/mpv-android-lib/build.sh"
"${dependencies_dir}/jellyfin-androidx-media/build.sh"
"${repository_root}/gradlew" -p "${repository_root}" assembleDebug
