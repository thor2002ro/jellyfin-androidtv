package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.preference.constant.LiveTvChannelOrder
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.livetv.liveTvChannelFields
import org.jellyfin.androidtv.ui.livetv.sortedByLiveTvChannelOrder
import org.jellyfin.androidtv.ui.livetv.toLiveTvItemSortBy
import org.jellyfin.androidtv.ui.livetv.toLiveTvSortOrder
import org.jellyfin.androidtv.ui.livetv.usesServerFavoriteSorting
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber

enum class LiveTvChannelDirection(val offset: Int) {
	PREVIOUS(-1),
	NEXT(1),
}

@Composable
fun rememberLiveTvChannelNavigator(
	api: ApiClient = koinInject(),
	liveTvPreferences: LiveTvPreferences = koinInject(),
	videoQueueManager: VideoQueueManager = koinInject(),
): LiveTvChannelNavigator = remember(api, liveTvPreferences, videoQueueManager) {
	LiveTvChannelNavigator(
		api = api,
		liveTvPreferences = liveTvPreferences,
		videoQueueManager = videoQueueManager,
	)
}

class LiveTvChannelNavigator(
	private val api: ApiClient,
	private val liveTvPreferences: LiveTvPreferences,
	private val videoQueueManager: VideoQueueManager,
) {
	suspend fun switchChannel(
		playbackManager: PlaybackManager,
		currentItem: BaseItemDto,
		direction: LiveTvChannelDirection,
	): Boolean {
		val currentChannelId = currentItem.liveTvChannelId() ?: return false
		val channels = getChannels()
		if (channels.size < 2) return false

		val currentIndex = channels.indexOfFirst { channel -> channel.liveTvChannelId() == currentChannelId }
		if (currentIndex < 0) {
			Timber.w("Unable to switch Live TV channel because current channel $currentChannelId is not in the channel list")
			return false
		}

		val targetIndex = channels.adjacentIndex(currentIndex, direction.offset)
		return switchToChannel(playbackManager, channels, targetIndex, currentChannelId)
	}

	suspend fun switchToChannel(
		playbackManager: PlaybackManager,
		currentItem: BaseItemDto,
		targetChannel: BaseItemDto,
		currentChannelId: java.util.UUID? = currentItem.liveTvChannelId(),
	): Boolean {
		currentChannelId ?: return false
		if (targetChannel.liveTvChannelId() == currentChannelId) return false

		val channels = getChannels()
		val targetIndex = channels.indexOfFirst { channel ->
			channel.liveTvChannelId() == targetChannel.liveTvChannelId()
		}
		if (targetIndex < 0) return false

		return switchToChannel(playbackManager, channels, targetIndex, currentChannelId)
	}

	suspend fun startLastOrFirstChannel(
		playbackManager: PlaybackManager,
	): Boolean {
		val channels = getChannels()
		if (channels.isEmpty()) return false

		val channelId = TvManager.getLastLiveTvChannel()
		val targetIndex = channels
			.indexOfFirst { channel -> channel.liveTvChannelId() == channelId }
			.takeIf { index -> index >= 0 }
			?: 0

		playChannel(playbackManager, channels, targetIndex)
		return true
	}

	suspend fun getChannels(): List<BaseItemDto> = runCatching {
		loadChannels()
	}.onFailure { error ->
		Timber.e(error, "Unable to load Live TV channels")
	}.getOrDefault(emptyList())

	private fun switchToChannel(
		playbackManager: PlaybackManager,
		channels: List<BaseItemDto>,
		targetIndex: Int,
		currentChannelId: java.util.UUID,
	): Boolean {
		val targetChannel = channels[targetIndex]
		if (targetChannel.liveTvChannelId() == currentChannelId) return false

		playChannel(playbackManager, channels, targetIndex)
		return true
	}

	private fun playChannel(
		playbackManager: PlaybackManager,
		channels: List<BaseItemDto>,
		targetIndex: Int,
	) {
		val targetChannel = channels[targetIndex]
		val targetItems = listOf(targetChannel)
		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		playbackManager.queue.clear()
		videoQueueManager.setCurrentVideoQueue(targetItems)
		targetChannel.liveTvChannelId()?.let(TvManager::setLastLiveTvChannel)
		playbackManager.queue.addSupplier(
			supplier = RewriteMediaManager.BaseItemQueueSupplier(api, targetItems, visibleInScreensaver = false),
			startIndex = 0,
		)
		playbackManager.state.play()
	}

	private suspend fun loadChannels(): List<BaseItemDto> {
		TvManager.getAllChannels()
			?.takeUnless { TvManager.shouldForceReload() }
			?.takeIf { channels -> channels.isNotEmpty() }
			?.let { channels -> return channels }

		val channelOrder = LiveTvChannelOrder.fromString(liveTvPreferences[LiveTvPreferences.channelOrder])
		val favoritesAtTop = liveTvPreferences[LiveTvPreferences.favsAtTop]

		val channels = withContext(Dispatchers.IO) {
			api.liveTvApi.getLiveTvChannels(
				addCurrentProgram = true,
				fields = liveTvChannelFields,
				enableFavoriteSorting = channelOrder.usesServerFavoriteSorting(favoritesAtTop),
				sortBy = setOf(channelOrder.toLiveTvItemSortBy()),
				sortOrder = channelOrder.toLiveTvSortOrder(),
			).content.items.let { channels ->
				channels.sortedByLiveTvChannelOrder(channelOrder, favoritesAtTop)
			}
		}
		TvManager.setAllChannels(channels)

		return channels
	}
}

private fun List<BaseItemDto>.adjacentIndex(
	currentIndex: Int,
	offset: Int,
): Int = (currentIndex + offset).floorMod(size)

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
