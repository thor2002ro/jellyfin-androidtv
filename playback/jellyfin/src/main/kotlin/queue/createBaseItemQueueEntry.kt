package org.jellyfin.playback.jellyfin.queue

import org.jellyfin.playback.core.mediastream.mediatype.mediaType
import org.jellyfin.playback.core.mediastream.normalizationGain
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueEntryMetadata
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.core.queue.metadata
import org.jellyfin.playback.jellyfin.livetv.LiveTvPlaybackPolicy
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.extensions.ticks
import java.util.UUID
import org.jellyfin.playback.core.mediastream.mediatype.MediaType as PlayerMediaType
import org.jellyfin.sdk.model.api.MediaType as SdkMediaType

/**
 * Create a [QueueEntry] from a [BaseItemDto].
 */
fun createBaseItemQueueEntry(api: ApiClient, baseItem: BaseItemDto): QueueEntry {
	val entry = QueueEntry()
	entry.metadata = QueueEntryMetadata(
		mediaId = baseItem.id.toString(),
		duration = baseItem.runTimeTicks?.ticks,
		title = baseItem.name,
		artist = baseItem.albumArtist,
		albumTitle = baseItem.album,
		albumArtist = baseItem.albumArtists
			?.mapNotNull { it.name }
			?.joinToString(", "),
		displayTitle = baseItem.name,
		description = baseItem.overview,
		artworkUri = when {
			baseItem.imageTags?.containsKey(ImageType.PRIMARY) == true -> api.getImageUri(
				itemId = baseItem.id,
				tag = baseItem.imageTags!![ImageType.PRIMARY]
			)

			baseItem.albumPrimaryImageTag != null -> api.getImageUri(
				itemId = baseItem.albumId ?: baseItem.id,
				tag = baseItem.albumPrimaryImageTag,
			)

			else -> null
		},
		trackNumber = baseItem.indexNumber,
		releaseDate = baseItem.premiereDate?.toLocalDate(),
		genre = baseItem.genres?.joinToString(", "),
	)
	entry.baseItem = baseItem
	entry.normalizationGain = baseItem.normalizationGain
	entry.mediaType = when {
		baseItem.isLiveTv -> PlayerMediaType.Video
		baseItem.mediaType == SdkMediaType.VIDEO -> PlayerMediaType.Video
		baseItem.mediaType == SdkMediaType.AUDIO -> PlayerMediaType.Audio
		else -> PlayerMediaType.Unknown
	}

	if (baseItem.isLiveTv) {
		entry.liveStreamTargetOffset = LiveTvPlaybackPolicy.INITIAL_LIVE_STREAM_TARGET_OFFSET
	}

	return entry
}

private val BaseItemDto.isLiveTv
	get() = when (type) {
		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.LIVE_TV_PROGRAM,
		BaseItemKind.TV_CHANNEL,
		BaseItemKind.LIVE_TV_CHANNEL -> true

		else -> false
	}

private fun ApiClient.getImageUri(itemId: UUID?, tag: String?): String? = when {
	// Invalid item id / tag
	itemId == null || tag == null -> null
	// Valid item id & tag
	else -> imageApi.getItemImageUrl(
		itemId = itemId,
		imageType = ImageType.PRIMARY,
		tag = tag,
	)
}
