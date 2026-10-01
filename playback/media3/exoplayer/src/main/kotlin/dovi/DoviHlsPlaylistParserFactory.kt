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
internal class DoviHlsPlaylistParserFactory(
	private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {
	override fun createPlaylistParser() = delegate.createPlaylistParser().forDovi()

	override fun createPlaylistParser(playlist: HlsMultivariantPlaylist, previous: HlsMediaPlaylist?) =
		delegate.createPlaylistParser(playlist, previous).forDovi()

	private fun ParsingLoadable.Parser<HlsPlaylist>.forDovi() = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
		val playlist = parse(uri, input)
		if (playlist is HlsMultivariantPlaylist) playlist.retainDoviVideoCopyVariants() else playlist
	}
}

@UnstableApi
internal fun HlsMultivariantPlaylist.retainDoviVideoCopyVariants(): HlsMultivariantPlaylist {
	// Jellyfin offers SDR re-encodes beside the original HDR stream. A local Dolby transform
	// must receive the original samples; selecting an SDR alternate would invalidate its plan.
	val originalVariants = variants.filterNot { variant ->
		variant.url.queryParameterNames.any { key ->
			key.equals("AllowVideoStreamCopy", true) && variant.url.getQueryParameters(key).any { it.equals("false", true) }
		}
	}
	if (originalVariants.isEmpty()) throw DoviSampleTransformationException(
		DoviStatus.REENCODE_REQUIRED, "HLS playlist has no original video variant for local Dolby conversion",
	)
	if (originalVariants.size == variants.size) return this
	return HlsMultivariantPlaylist(
		baseUri, tags, originalVariants, videos, audios, subtitles, closedCaptions,
		muxedAudioFormat, muxedCaptionFormats, hasIndependentSegments, variableDefinitions, sessionKeyDrmInitData, contentSteeringInfo,
	)
}
