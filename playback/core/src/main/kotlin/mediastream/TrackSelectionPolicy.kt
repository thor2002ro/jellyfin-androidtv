package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.backend.TrackType

/**
 * Returns whether a backend can change this track without requesting a new stream from the server.
 * External subtitles remain separate player tracks during remux and audio-only conversion.
 */
fun PlayableMediaStream.allowsLocalTrackSelection(type: TrackType, track: MediaStreamTrack?): Boolean =
	conversionMethod == MediaConversionMethod.None ||
		type == TrackType.SUBTITLE &&
		(track as? MediaStreamSubtitleTrack)?.isExternal == true &&
		!hasServerRenderedSubtitle()

/** Returns whether disabling subtitles locally can remove them from the rendered output. */
fun PlayableMediaStream.allowsLocalSubtitleDisable() = !hasServerRenderedSubtitle()

private fun PlayableMediaStream.hasServerRenderedSubtitle(): Boolean {
	if (conversionMethod != MediaConversionMethod.Transcode) return false
	val streamIndex = selectedSubtitleStreamIndex?.takeIf { it >= 0 } ?: return false
	return tracks
		.filterIsInstance<MediaStreamSubtitleTrack>()
		.firstOrNull { track -> track.index == streamIndex }
		?.isExternal != true
}
