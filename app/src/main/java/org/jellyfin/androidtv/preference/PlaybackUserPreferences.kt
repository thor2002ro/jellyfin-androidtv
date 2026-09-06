package org.jellyfin.androidtv.preference

import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.preference.Preference
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.enumPreference
import org.jellyfin.preference.stringPreference

private val playbackBackendPreference = enumPreference("playback_backend", PlaybackBackend.EXOPLAYER)
private val hdrUseExternalPlayerPreference = booleanPreference("hdr_external_player", false)
private val hdrExternalPlayerComponentNamePreference = stringPreference("hdr_external_player_component", "")
private val hdrPlaybackRewriteVideoEnabledPreference = booleanPreference("hdr_playback_new", true)
private val hdrPlaybackBackendPreference = enumPreference("hdr_playback_backend", PlaybackBackend.SAME_VIDEO_PLAYER)

/** Playback engine used by the new player. */
val UserPreferences.Companion.playbackBackend get() = playbackBackendPreference

internal class PlaybackPlayerPreferences(
	val useExternalPlayer: Preference<Boolean>,
	val externalPlayerComponentName: Preference<String>,
	val playbackRewriteVideoEnabled: Preference<Boolean>,
	val playbackBackend: Preference<PlaybackBackend>,
)

private val defaultPlaybackPlayerPreferences = PlaybackPlayerPreferences(
	useExternalPlayer = UserPreferences.useExternalPlayer,
	externalPlayerComponentName = UserPreferences.externalPlayerComponentName,
	playbackRewriteVideoEnabled = UserPreferences.playbackRewriteVideoEnabled,
	playbackBackend = playbackBackendPreference,
)

private val hdrPlaybackPlayerPreferences = PlaybackPlayerPreferences(
	useExternalPlayer = hdrUseExternalPlayerPreference,
	externalPlayerComponentName = hdrExternalPlayerComponentNamePreference,
	playbackRewriteVideoEnabled = hdrPlaybackRewriteVideoEnabledPreference,
	playbackBackend = hdrPlaybackBackendPreference,
)

internal fun UserPreferences.Companion.playbackPlayerPreferences(hdr: Boolean) =
	if (hdr) hdrPlaybackPlayerPreferences else defaultPlaybackPlayerPreferences
