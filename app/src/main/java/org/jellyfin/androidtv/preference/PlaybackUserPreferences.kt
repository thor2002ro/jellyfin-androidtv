package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.preference.enumPreference

private val playbackBackendPreference = enumPreference("playback_backend", PlaybackBackend.EXOPLAYER)

/** Playback engine used by the new player. */
val UserPreferences.Companion.playbackBackend get() = playbackBackendPreference
