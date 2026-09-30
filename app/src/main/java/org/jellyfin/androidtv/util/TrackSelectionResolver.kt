package org.jellyfin.androidtv.util

import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.androidtv.util.sdk.trackSelectionIds
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

object TrackSelectionResolver {
	@JvmStatic
	fun resolvePlaybackAudioStreamIndex(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int? = getExplicitAudioStreamIndex(item, mediaSource)
		?: findPreferredAudioStreamIndex(mediaSource, videoQueueManager)

	@JvmStatic
	fun resolvePlaybackSubtitleStreamIndex(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int? {
		val subtitleSelection = getExplicitSubtitleSelection(item, mediaSource)
		return when {
			subtitleSelection.hasSelection -> subtitleSelection.trackIndex
			else -> findPreferredSubtitleStreamIndex(mediaSource, videoQueueManager)
		}
	}

	@JvmStatic
	fun resolveDisplayAudioStreamIndex(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int? = resolvePlaybackAudioStreamIndex(item, mediaSource, videoQueueManager)
		?: mediaSource?.defaultAudioStreamIndex
		?: mediaSource.mediaStreamsOfType(MediaStreamType.AUDIO).firstOrNull { stream -> stream.isDefault }?.index
		?: mediaSource.mediaStreamsOfType(MediaStreamType.AUDIO).firstOrNull()?.index

	@JvmStatic
	fun resolveDisplaySubtitleStreamIndex(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int = resolvePlaybackSubtitleStreamIndex(item, mediaSource, videoQueueManager)
		?: mediaSource?.defaultSubtitleStreamIndex?.takeIf { index -> index >= 0 }
		?: -1

	@JvmStatic
	fun getExplicitAudioStreamIndex(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
	): Int? {
		val itemIds = item.trackSelectionIds()
		val streamIndex = TrackSelectionManager.getSelectedAudioTrack(itemIds) ?: return null
		if (item.isLiveTv()) return streamIndex
		if (mediaSource == null) return null
		if (mediaSource.hasMediaStream(MediaStreamType.AUDIO, streamIndex)) return streamIndex

		TrackSelectionManager.setSelectedAudioTracks(itemIds, null)
		return null
	}

	@JvmStatic
	fun getExplicitSubtitleSelection(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
	): TrackSelectionManager.TrackSelection {
		val itemIds = item.trackSelectionIds()
		val selection = TrackSelectionManager.getSelectedSubtitleTrackSelection(itemIds)
		if (!selection.hasSelection) return selection
		if (selection.trackIndex == -1) return selection
		if (item.isLiveTv()) return selection
		if (mediaSource == null) return TrackSelectionManager.TrackSelection(hasSelection = false, trackIndex = null)
		if (selection.trackIndex != null && mediaSource.hasMediaStream(MediaStreamType.SUBTITLE, selection.trackIndex)) return selection

		TrackSelectionManager.setSelectedSubtitleTracks(itemIds, null)
		return TrackSelectionManager.TrackSelection(hasSelection = false, trackIndex = null)
	}

	@JvmStatic
	fun hasExplicitSubtitleSelection(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
	): Boolean = getExplicitSubtitleSelection(item, mediaSource).hasSelection

	@JvmStatic
	fun storeSelectedAudioTrack(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
		streamIndex: Int?,
	): MediaStream? {
		val stream = mediaSource.findMediaStream(MediaStreamType.AUDIO, streamIndex)
		if (streamIndex != null && stream == null && !item.isLiveTv()) return null

		TrackSelectionManager.setSelectedAudioTracks(item.trackSelectionIds(), streamIndex)
		videoQueueManager.setLastPlayedAudioLanguageIsoCode(stream?.language)
		videoQueueManager.setLastPlayedAudioCodec(stream?.codec)
		return stream
	}

	@JvmStatic
	fun storeSelectedSubtitleTrack(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
		streamIndex: Int?,
	): MediaStream? {
		val selectedIndex = streamIndex ?: -1
		if (selectedIndex == -1) {
			TrackSelectionManager.setSelectedSubtitleTracks(item.trackSelectionIds(), selectedIndex)
			videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("")
			videoQueueManager.setLastPlayedSubtitleForcedState(false)
			videoQueueManager.setLastPlayedSubtitleCodec(null)
			videoQueueManager.setLastPlayedSubtitleTitle(null)
			return null
		}

		val stream = mediaSource.findMediaStream(MediaStreamType.SUBTITLE, selectedIndex)
		if (stream == null) {
			if (!item.isLiveTv()) return null

			TrackSelectionManager.setSelectedSubtitleTracks(item.trackSelectionIds(), selectedIndex)
			return null
		}
		TrackSelectionManager.setSelectedSubtitleTracks(item.trackSelectionIds(), selectedIndex)
		videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(stream.language)
		videoQueueManager.setLastPlayedSubtitleForcedState(stream.isForced == true)
		videoQueueManager.setLastPlayedSubtitleCodec(stream.codec)
		videoQueueManager.setLastPlayedSubtitleTitle(stream.displayTitle ?: stream.title)
		return stream
	}

	private fun findPreferredAudioStreamIndex(
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int? {
		val language = videoQueueManager.getLastPlayedAudioLanguageIsoCode() ?: return null
		val codec = videoQueueManager.getLastPlayedAudioCodec()
		val audioStreams = mediaSource.mediaStreamsOfType(MediaStreamType.AUDIO)

		return audioStreams.firstOrNull { stream ->
			languageCodesMatch(stream.language, language) && codec != null && stream.codec == codec
		}?.index ?: audioStreams.firstOrNull { stream ->
			languageCodesMatch(stream.language, language)
		}?.index
	}

	private fun findPreferredSubtitleStreamIndex(
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int? {
		val languages = videoQueueManager.getLastPlayedSubtitleLanguageIsoCodes() ?: return null
		if (languages.firstOrNull().orEmpty().isEmpty()) return -1

		val forced = videoQueueManager.getLastPlayedSubtitleForcedState()
		val codec = videoQueueManager.getLastPlayedSubtitleCodec()
		val title = videoQueueManager.getLastPlayedSubtitleTitle()
		val subtitleStreams = mediaSource.mediaStreamsOfType(MediaStreamType.SUBTITLE)

		for (language in languages) {
			val match = subtitleStreams.firstOrNull { stream ->
				languageCodesMatch(stream.language, language) &&
					stream.isForced == forced &&
					codec != null &&
					stream.codec == codec &&
					title != null &&
					stream.matchesTitle(title)
			}?.index ?: subtitleStreams.firstOrNull { stream ->
				languageCodesMatch(stream.language, language) &&
					stream.isForced == forced &&
					codec != null &&
					stream.codec == codec
			}?.index ?: subtitleStreams.firstOrNull { stream ->
				languageCodesMatch(stream.language, language) && stream.isForced == forced
			}?.index
			if (match != null) return match
		}
		return null
	}

	private fun MediaSourceInfo?.findMediaStream(
		type: MediaStreamType,
		streamIndex: Int?,
	): MediaStream? = mediaStreamsOfType(type)
		.firstOrNull { stream -> stream.index == streamIndex }

	private fun MediaSourceInfo.hasMediaStream(
		type: MediaStreamType,
		streamIndex: Int,
	): Boolean = mediaStreamsOfType(type)
		.any { stream -> stream.index == streamIndex }

	private fun MediaSourceInfo?.mediaStreamsOfType(type: MediaStreamType): List<MediaStream> =
		this?.mediaStreams.orEmpty().filter { stream -> stream.type == type }

	private fun MediaStream.matchesTitle(title: String): Boolean =
		this.title == title || this.displayTitle == title
}
