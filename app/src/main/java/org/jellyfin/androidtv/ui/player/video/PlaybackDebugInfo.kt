package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.playback.appendInline
import org.jellyfin.androidtv.ui.playback.appendStatusPart
import org.jellyfin.androidtv.ui.playback.displayName
import org.jellyfin.androidtv.util.sdk.formatVideoRange
import org.jellyfin.androidtv.util.toIso2LanguageDisplayOrSelf
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackType
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.mediaStreamFlow

@Composable
fun PlaybackDebugInfo(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) {
	val entry by rememberQueueEntry(playbackManager)
	val mediaStream by entry?.mediaStreamFlow?.collectAsState(null) ?: return
	val stream = mediaStream ?: return

	var refreshTick by remember { mutableStateOf(0) }
	LaunchedEffect(playbackManager.trackSelection) {
		while (true) {
			delay(1_000)
			refreshTick++
		}
	}

	val debugInfo = remember(stream, refreshTick) {
		buildPlaybackDebugInfo(playbackManager, stream)
	}

	if (debugInfo.isBlank()) return

	Text(
		text = debugInfo,
		overflow = TextOverflow.Ellipsis,
		maxLines = 1,
		style = TextStyle(
			color = Color.White,
			fontFamily = FontFamily.Monospace,
			fontSize = 11.sp,
			lineHeight = 12.sp,
		),
		modifier = modifier,
	)
}

private fun buildPlaybackDebugInfo(
	playbackManager: PlaybackManager,
	stream: MediaStream,
): String = buildString {
	val videoTrack = stream.tracks.filterIsInstance<MediaStreamVideoTrack>().firstOrNull()
	// Keep every audio stream available because the selected stream may not be the first item.
	val audioTracks = stream.tracks.filterIsInstance<MediaStreamAudioTrack>()
	val trackSelection = playbackManager.trackSelection
	val selectedAudio = trackSelection
		?.getAvailableTracks(TrackType.AUDIO)
		?.firstOrNull(PlayerTrack::isSelected)
	val selectedSubtitle = trackSelection
		?.getAvailableTracks(TrackType.SUBTITLE)
		?.firstOrNull(PlayerTrack::isSelected)

	appendStatusPart(stream.conversionMethod.displayName())
	appendStatusPart(videoTrack.videoSummary())
	appendStatusPart(audioTracks.audioSummary(selectedAudio))
	appendStatusPart(selectedSubtitle.subtitleSummary())
}

private fun MediaStreamVideoTrack?.videoSummary(): String? {
	if (this == null) return null

	return buildString {
		if (width > 0 && height > 0) append("${width}x$height")
		appendInline(codec.uppercase())
		appendInline(videoRange.formatVideoRange())
	}
}

internal fun List<MediaStreamAudioTrack>.audioSummary(playerTrack: PlayerTrack?): String {
	// Match the backend selection by Jellyfin stream index so codec and channels stay paired.
	val mediaStream = selectedTrack(playerTrack)
	if (mediaStream == null && playerTrack == null) return "Audio: unknown"

	return buildString {
		append("Audio:")
		appendInline(playerTrack?.codec?.uppercase() ?: mediaStream?.codec?.uppercase())
		mediaStream?.channels?.takeIf { it > 0 }?.let { appendInline("${it}ch") }
		appendInline(playerTrack?.language.toIso2LanguageDisplayOrSelf())
	}
}

private fun PlayerTrack?.subtitleSummary(): String {
	if (this == null) return "Sub: off"

	return buildString {
		append("Sub:")
		appendInline(codec?.uppercase())
		appendInline(language.toIso2LanguageDisplayOrSelf())
	}
}
