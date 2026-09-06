package org.jellyfin.androidtv.ui.playback

import android.content.Context
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.playbackPlayerPreferences
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.sdk.isHdrVideo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Utility class to launch the playback UI for an item.
 */
class PlaybackLauncher(
	private val mediaManager: MediaManager,
	private val videoQueueManager: VideoQueueManager,
	private val navigationRepository: NavigationRepository,
	private val userPreferences: UserPreferences,
) {
	enum class VideoPlayer {
		EXTERNAL,
		LEGACY,
		NEW,
	}

	data class VideoPlayerSelection(
		val player: VideoPlayer,
		val backend: PlaybackBackend? = null,
	)

	private val BaseItemDto.supportsExternalPlayer
		get() = when (type) {
			BaseItemKind.MOVIE,
			BaseItemKind.EPISODE,
			BaseItemKind.VIDEO,
			BaseItemKind.SERIES,
			BaseItemKind.SEASON,
			BaseItemKind.RECORDING,
			BaseItemKind.TV_CHANNEL,
			BaseItemKind.LIVE_TV_CHANNEL,
			BaseItemKind.PROGRAM,
			BaseItemKind.TV_PROGRAM,
			BaseItemKind.LIVE_TV_PROGRAM,
				-> true

			else -> false
		}

	@JvmOverloads
	fun launch(
		context: Context,
		items: List<BaseItemDto>,
		position: Int? = null,
		replace: Boolean = false,
		itemsPosition: Int = 0,
		shuffle: Boolean = false,
	) {
		val isAudio = items.any { it.mediaType == MediaType.AUDIO }

		if (isAudio) {
			mediaManager.playNow(context, items, itemsPosition, shuffle)
			navigationRepository.navigate(Destinations.nowPlaying)
		} else {
			val items = if (shuffle) items.shuffled() else items

			videoQueueManager.setCurrentVideoQueue(items.toList())
			videoQueueManager.setCurrentMediaPosition(itemsPosition)

			when (getVideoPlayerSelection(items, itemsPosition)?.player) {
				VideoPlayer.EXTERNAL -> {
					context.startActivity(ActivityDestinations.externalPlayer(context, position?.milliseconds ?: Duration.ZERO))
					if (!replace || !navigationRepository.goBack()) navigationRepository.goNowhere(true)
				}

				VideoPlayer.NEW -> {
					val destination = Destinations.videoPlayerNew(position)
					navigationRepository.navigate(destination, replace = replace)
				}

				VideoPlayer.LEGACY -> {
					val destination = Destinations.videoPlayer(position)
					navigationRepository.navigate(destination, replace = replace)
				}

				null -> Unit
			}
		}
	}

	fun getVideoPlayerSelection(
		items: List<BaseItemDto>,
		itemsPosition: Int,
	): VideoPlayerSelection? {
		val item = items.getOrNull(itemsPosition) ?: items.firstOrNull() ?: return null
		val playerPreferences = UserPreferences.playbackPlayerPreferences(item.isHdrVideo)

		return when {
			userPreferences[playerPreferences.useExternalPlayer] && items.all { it.supportsExternalPlayer } ->
				VideoPlayerSelection(VideoPlayer.EXTERNAL)

			userPreferences[playerPreferences.playbackRewriteVideoEnabled] ->
				VideoPlayerSelection(
					player = VideoPlayer.NEW,
					backend = userPreferences[playerPreferences.playbackBackend],
				)

			else -> VideoPlayerSelection(VideoPlayer.LEGACY)
		}
	}

	@JvmOverloads
	fun launchCurrentVideoQueue(
		context: Context,
		replace: Boolean = true,
	) {
		launch(
			context = context,
			items = videoQueueManager.getCurrentVideoQueue(),
			position = 0,
			replace = replace,
			itemsPosition = videoQueueManager.getCurrentMediaPosition(),
		)
	}
}
