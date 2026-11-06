package org.jellyfin.playback.exoplayer.dovi

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import io.github.thor2002ro.libdovi.DoviStatus

@UnstableApi
internal class HlsVideoCopyPlaylistParserFactory(
	private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
	private val required: Boolean = false,
) : HlsPlaylistParserFactory {
	override fun createPlaylistParser() = delegate.createPlaylistParser().filterVideoVariants()

	override fun createPlaylistParser(playlist: HlsMultivariantPlaylist, previous: HlsMediaPlaylist?) =
		delegate.createPlaylistParser(playlist, previous).filterVideoVariants()

	private fun ParsingLoadable.Parser<HlsPlaylist>.filterVideoVariants() = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
		val playlist = parse(uri, input)
		if (playlist is HlsMultivariantPlaylist) playlist.retainVideoCopyVariants(required) else playlist
	}
}

@UnstableApi
internal fun HlsMultivariantPlaylist.retainVideoCopyVariants(required: Boolean = false): HlsMultivariantPlaylist {
	// Jellyfin can offer re-encoded variants beside the original stream. Video copy and local
	// transforms must keep the original samples instead of adapting to a converted variant.
	val originalVariants = variants.filterNot { variant ->
		variant.url.queryParameterNames.any { key ->
			key.equals("AllowVideoStreamCopy", true) && variant.url.getQueryParameters(key).any { it.equals("false", true) }
		}
	}
	if (originalVariants.isEmpty()) {
		if (!required) return this
		throw DoviSampleTransformationException(
			DoviStatus.REENCODE_REQUIRED, "HLS playlist has no original video variant for local Dolby conversion",
		)
	}
	if (originalVariants.size == variants.size) return this
	return HlsMultivariantPlaylist(
		baseUri, tags, originalVariants, videos, audios, subtitles, closedCaptions,
		muxedAudioFormat, muxedCaptionFormats, hasIndependentSegments, variableDefinitions, sessionKeyDrmInitData, contentSteeringInfo,
	)
}
