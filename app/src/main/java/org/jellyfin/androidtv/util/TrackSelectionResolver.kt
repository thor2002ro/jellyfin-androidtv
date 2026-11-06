package org.jellyfin.androidtv.util

import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.androidtv.util.sdk.trackSelectionIds
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
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
		if (!item.isLiveTv()) return resolveSavedChoice(item, mediaSource, MediaStreamType.AUDIO).trackIndex
		val itemIds = item.trackSelectionIds()
		val streamIndex = TrackSelectionManager.getSelectedAudioTrack(itemIds) ?: return null
		if (mediaSource == null) return streamIndex
		if (mediaSource.hasMediaStream(MediaStreamType.AUDIO, streamIndex)) return streamIndex
		if (mediaSource.mediaStreamsOfType(MediaStreamType.AUDIO).isEmpty()) return streamIndex

		TrackSelectionManager.setSelectedAudioTracks(itemIds, null)
		return null
	}

	@JvmStatic
	fun getExplicitSubtitleSelection(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
	): TrackSelectionManager.TrackSelection {
		if (!item.isLiveTv()) return resolveSavedChoice(item, mediaSource, MediaStreamType.SUBTITLE)
		val itemIds = item.trackSelectionIds()
		val selection = TrackSelectionManager.getSelectedSubtitleTrackSelection(itemIds)
		if (!selection.hasSelection || selection.trackIndex == -1) return selection
		if (mediaSource == null) return selection
		if (selection.trackIndex != null && mediaSource.hasMediaStream(MediaStreamType.SUBTITLE, selection.trackIndex)) return selection
		if (mediaSource.mediaStreamsOfType(MediaStreamType.SUBTITLE).isEmpty()) return selection

		TrackSelectionManager.setSelectedSubtitleTracks(itemIds, null)
		return TrackSelectionManager.TrackSelection(hasSelection = false, trackIndex = null)
	}

	@JvmStatic
	fun storeSelectedAudioTrack(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
		streamIndex: Int?,
	): MediaStream? {
		val stream = mediaSource.findMediaStream(MediaStreamType.AUDIO, streamIndex)
		if (streamIndex != null && stream == null && !item.isLiveTv()) return null

		if (item.isLiveTv() || streamIndex == null) TrackSelectionManager.setSelectedAudioTracks(item.trackSelectionIds(), streamIndex)
		if (!item.isLiveTv()) TrackSelectionManager.setTrackChoices(
			item.choiceIds(), MediaStreamType.AUDIO, stream?.let(TrackChoice::from),
		)
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
		// Null means the backend has no Jellyfin mapping; only -1 is an explicit Off choice.
		val selectedIndex = streamIndex ?: return null
		if (selectedIndex == -1) {
			if (item.isLiveTv()) TrackSelectionManager.setSelectedSubtitleTracks(item.trackSelectionIds(), selectedIndex)
			if (!item.isLiveTv()) TrackSelectionManager.setTrackChoices(item.choiceIds(), MediaStreamType.SUBTITLE, TrackChoice(index = -1))
			videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("")
			videoQueueManager.setLastPlayedSubtitleForcedState(false)
			videoQueueManager.setLastPlayedSubtitleHearingImpaired(false)
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
		if (item.isLiveTv()) TrackSelectionManager.setSelectedSubtitleTracks(item.trackSelectionIds(), selectedIndex)
		if (!item.isLiveTv()) TrackSelectionManager.setTrackChoices(item.choiceIds(), MediaStreamType.SUBTITLE, TrackChoice.from(stream))
		videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(stream.language)
		videoQueueManager.setLastPlayedSubtitleForcedState(stream.isForced == true)
		videoQueueManager.setLastPlayedSubtitleHearingImpaired(stream.isHearingImpaired)
		videoQueueManager.setLastPlayedSubtitleCodec(stream.codec)
		videoQueueManager.setLastPlayedSubtitleTitle(stream.displayTitle ?: stream.title)
		return stream
	}

	private fun BaseItemDto.choiceIds() = if (type == BaseItemKind.EPISODE) {
		listOfNotNull(id, seasonId, seriesId).distinct()
	} else listOf(id)

	private fun resolveSavedChoice(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo?,
		type: MediaStreamType,
	): TrackSelectionManager.TrackSelection {
		val streams = mediaSource.mediaStreamsOfType(type)
		var forcedUnavailable = false
		for (id in item.choiceIds()) {
			val choice = TrackSelectionManager.getTrackChoice(id, type)
			if (choice != null) {
				if (type == MediaStreamType.SUBTITLE && choice.index == -1) {
					return TrackSelectionManager.TrackSelection(true, -1)
				}
				choice.match(streams, sameItem = id == item.id)?.let { stream ->
					return TrackSelectionManager.TrackSelection(true, stream.index)
				}
				if (type == MediaStreamType.SUBTITLE && choice.forced && streams.any { stream ->
					choice.matchesLanguage(stream)
				}) forcedUnavailable = true
			} else if (id == item.id) {
				resolveLegacyChoice(item, type, streams)?.let { return it }
			}
		}
		// A remembered forced track must not silently turn into full-dialogue subtitles.
		return if (forcedUnavailable) TrackSelectionManager.TrackSelection(true, -1)
		else TrackSelectionManager.TrackSelection(false, null)
	}

	private fun resolveLegacyChoice(
		item: BaseItemDto,
		type: MediaStreamType,
		streams: List<MediaStream>,
	): TrackSelectionManager.TrackSelection? {
		// Read old episode indices before inherited choices, and enrich a valid selection
		// without inventing a season or series preference from an automatic server default.
		val ids = listOf(item.id)
		val index = if (type == MediaStreamType.AUDIO) {
			TrackSelectionManager.getSelectedAudioTrack(ids)
		} else TrackSelectionManager.getSelectedSubtitleTrackSelection(ids).trackIndex
		if (type == MediaStreamType.SUBTITLE && index == -1) return TrackSelectionManager.TrackSelection(true, -1)
		val stream = streams.firstOrNull { it.index == index } ?: return null
		TrackSelectionManager.setTrackChoices(ids, type, TrackChoice.from(stream))
		return TrackSelectionManager.TrackSelection(true, stream.index)
	}

	private fun findPreferredAudioStreamIndex(
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int? {
		val language = videoQueueManager.getLastPlayedAudioLanguageIsoCode() ?: return null
		return TrackChoice(index = -1, language = language, codec = videoQueueManager.getLastPlayedAudioCodec())
			.match(mediaSource.mediaStreamsOfType(MediaStreamType.AUDIO), sameItem = false)?.index
	}

	private fun findPreferredSubtitleStreamIndex(
		mediaSource: MediaSourceInfo?,
		videoQueueManager: VideoQueueManager,
	): Int? {
		val languages = videoQueueManager.getLastPlayedSubtitleLanguageIsoCodes() ?: return null
		if (languages.firstOrNull().orEmpty().isEmpty()) return -1

		val subtitleStreams = mediaSource.mediaStreamsOfType(MediaStreamType.SUBTITLE)

		for (language in languages) {
			val match = TrackChoice(
				index = -1,
				language = language,
				forced = videoQueueManager.getLastPlayedSubtitleForcedState(),
				hearingImpaired = videoQueueManager.getLastPlayedSubtitleHearingImpaired(),
				codec = videoQueueManager.getLastPlayedSubtitleCodec(),
				title = videoQueueManager.getLastPlayedSubtitleTitle(),
			).match(subtitleStreams, sameItem = false)?.index
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
}
