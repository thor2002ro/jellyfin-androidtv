package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.queue.QueueEntry

interface MediaStream {
	val identifier: String
	val conversionMethod: MediaConversionMethod
	val container: MediaStreamContainer
	val tracks: Collection<MediaStreamTrack>
}

data class BasicMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
	val externalSubtitles: List<ExternalSubtitle> = emptyList(),
	val selectedAudioStreamIndex: Int? = null,
	val selectedSubtitleStreamIndex: Int? = null,
) : MediaStream {
	fun toPlayableMediaStream(
		queueEntry: QueueEntry,
		url: String,
	) = PlayableMediaStream(
		identifier = identifier,
		conversionMethod = conversionMethod,
		container = container,
		tracks = tracks,
		queueEntry = queueEntry,
		url = url,
		externalSubtitles = externalSubtitles,
		selectedAudioStreamIndex = selectedAudioStreamIndex,
		selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
	)
}

data class PlayableMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
	val queueEntry: QueueEntry,
	val url: String,
	val externalSubtitles: List<ExternalSubtitle> = emptyList(),
	val selectedAudioStreamIndex: Int? = null,
	val selectedSubtitleStreamIndex: Int? = null,
) : MediaStream

data class ExternalSubtitle(
	val url: String,
	val mimeType: String,
	val language: String?,
	val title: String?,
	val index: Int,
	val isDefault: Boolean = false,
	val isForced: Boolean = false,
)

data class MediaStreamContainer(
	val format: String,
)

sealed interface MediaStreamTrack {
	val index: Int?
	val codec: String
}

data class MediaStreamAudioTrack(
	override val index: Int?,
	override val codec: String,
	val bitrate: Int,
	val channels: Int,
	val sampleRate: Int,
	val language: String?,
	val title: String?,
) : MediaStreamTrack

data class MediaStreamVideoTrack(
	override val index: Int?,
	override val codec: String,
	val bitrate: Int,
	val width: Int,
	val height: Int,
	val videoRange: String?,
	val realFrameRate: Float?,
	val isInterlaced: Boolean,
) : MediaStreamTrack

data class MediaStreamSubtitleTrack(
	override val index: Int?,
	override val codec: String,
	val language: String?,
	val title: String?,
	val isExternal: Boolean,
) : MediaStreamTrack
