package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.preference.constant.PlaybackBackend
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.Destination
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.androidtv.util.PlaybackDisplayMode
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackOrder
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.playback.libvlc.LibVLCBackend
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend
import org.jellyfin.playback.mpv.LibMPVBackend
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VideoPlayerFragment : Fragment(), View.OnKeyListener {
	companion object {
		const val EXTRA_POSITION: String = "position"
		const val EXTRA_CLOSE_TO_LIVE_TV_LIBRARY: String = "close_to_live_tv_library"
		const val EXTRA_LIVE_TV_CHANNEL_ID: String = "live_tv_channel_id"
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val exoPlayerBackend by inject<ExoPlayerBackend>()
	private val libVLCBackend by inject<LibVLCBackend>()
	private val mpvBackend by inject<LibMPVBackend>()
	private val navigationRepository by inject<NavigationRepository>()
	private val playbackLauncher by inject<PlaybackLauncher>()
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val interactionTracker by inject<InteractionTrackerViewModel>()
	private var remoteKeyEventHandler: ((keyCode: Int, event: KeyEvent?) -> Boolean)? = null
	private var closingPlayer = false
	private var hasSeenVideoQueueEntry = false
	private var showingPostPlaybackPrompt = false
	private var handingOffPlayer = false
	private var completedEntry: QueueEntry? = null
	private val entryChangePredicate: suspend (QueueEntry) -> Boolean = ::shouldChangeQueueEntry
	private var displayModePreparation: (suspend (PlayableMediaStream) -> Boolean)? = null
	private var originalDisplayModePreference: Int? = null
	private var displayModeRestoreJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Capture the baseline once, before playback changes the output mode. Using
		// each item's current mode instead would gradually lose the original baseline.
		val playerActivity = requireActivity()
		val originalMode = playerActivity.windowManager.defaultDisplay.mode
		val originalPreference = playerActivity.window.attributes.preferredDisplayModeId
		originalDisplayModePreference = originalPreference
		displayModePreparation = { stream ->
			val video = stream.tracks.filterIsInstance<MediaStreamVideoTrack>().firstOrNull()
			if (video == null) true else PlaybackDisplayMode.applyAndAwait(
				playerActivity, video.width, video.height,
				PlaybackDisplayMode.frameRateForDisplay(video.realFrameRate, video.isInterlaced),
				userPreferences[UserPreferences.refreshRateSwitchingEnabled],
				userPreferences[UserPreferences.resolutionSwitchingEnabled], originalMode,
				originalPreference = originalPreference,
				delaySeconds = userPreferences[UserPreferences.displayChangeDelaySeconds],
				isCurrent = { isAdded && playbackManager.options.beforePlayItem === displayModePreparation },
			)
		}
		// Install before adding the queue: its first entry can start resolving at once.
		// Preparation belongs in the startup path, rather than a separate Compose effect.
		playbackManager.options.beforePlayItem = displayModePreparation

		val items = videoQueueManager.getCurrentVideoQueue()
		val requestedLiveTvChannelId = arguments
			?.getString(EXTRA_LIVE_TV_CHANNEL_ID)
			?.let(UUID::fromString)
		val startIndex = requestedLiveTvChannelId
			?.let { channelId ->
				items.indexOfFirst { item -> item.liveTvChannelId() == channelId }
					.takeIf { index -> index >= 0 }
			}
			?: videoQueueManager.getCurrentMediaPosition()

		if (
			arguments?.getBoolean(EXTRA_CLOSE_TO_LIVE_TV_LIBRARY) == true &&
			playbackManager.queue.entry.value?.isLiveTv == true
		) {
			playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
			playbackManager.state.stop()
		}

		// Create a queue from the items added to the legacy video queue
		val startPosition = arguments
			?.takeIf { it.containsKey(EXTRA_POSITION) }
			?.getInt(EXTRA_POSITION)
			?.milliseconds
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(
			api = api,
			items = items,
			visibleInScreensaver = false,
			initialStartPosition = startPosition,
			initialStartIndex = startIndex,
		)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		videoQueueManager.setCurrentMediaPosition(startIndex)
		playbackManager.getService<QueueService>()?.setEntryChangePredicate(entryChangePredicate)
		playbackManager.state.setPlaybackOrder(PlaybackOrder.DEFAULT)
		playbackManager.state.setRepeatMode(RepeatMode.NONE)
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier, startIndex = startIndex)

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				playbackManager.state.playState.onEach { playState ->
					interactionTracker.notifyPlaybackState(playState == PlayState.PLAYING)
				}.launchIn(this)
				playbackManager.queue.entry
					.onEach { entry ->
						if (entry == null) {
							if (
								hasSeenVideoQueueEntry &&
								playbackManager.state.playState.value == PlayState.STOPPED &&
								!showingPostPlaybackPrompt &&
								!handingOffPlayer
							) closePlayer()
						} else {
							hasSeenVideoQueueEntry = entry.isLiveTv != true
							onQueueEntryChanged(entry)
						}
					}
					.launchIn(this)
			}
		}
		playbackManager.queue.entry
			.mapNotNull { entry -> entry?.baseItem?.liveTvChannelId() }
			.distinctUntilChanged()
			.onEach { channelId -> TvManager.setLastLiveTvChannel(channelId) }
			.launchIn(lifecycleScope)

		// Pause player until the initial resume
		playbackManager.state.pause()
	}

	private fun restoreDisplayMode(
		preparation: (suspend (PlayableMediaStream) -> Boolean)? = displayModePreparation,
		originalPreference: Int? = originalDisplayModePreference,
		window: Window? = activity?.window,
	) {
		// The manager is shared. An older fragment must not remove a newer screen's
		// callback or restore its window preference after that screen takes ownership.
		if (playbackManager.options.beforePlayItem !== preparation) {
			Timber.d("Skipping display mode restore because another player owns it")
			return
		}
		playbackManager.options.beforePlayItem = null
		window?.let { playerWindow ->
			originalPreference?.let { preference ->
				val attributes = playerWindow.attributes
				val previousPreference = attributes.preferredDisplayModeId
				attributes.preferredDisplayModeId = preference
				playerWindow.attributes = attributes
				Timber.i("Restored preferred display mode from %d to %d", previousPreference, preference)
			}
		}
	}

	private fun scheduleDisplayModeRestore() {
		displayModeRestoreJob?.let { job ->
			if (job.isActive) Timber.d("Cancelling pending display mode restore before rescheduling")
			job.cancel()
		}
		val preparation = displayModePreparation
		val originalPreference = originalDisplayModePreference
		val window = activity?.window
		val switchingEnabled =
			userPreferences[UserPreferences.refreshRateSwitchingEnabled] ||
				userPreferences[UserPreferences.resolutionSwitchingEnabled]
		// Don't touch the window if we never switched modes. On some Amlogic devices,
		// doing that can turn HDR back on after playback has already returned to SDR.
		val skipReason = when {
			!switchingEnabled -> "display switching is disabled"
			window == null -> "the player window is unavailable"
			originalPreference == null -> "the original display mode is unavailable"
			window.attributes.preferredDisplayModeId == originalPreference -> "the display mode did not change"
			else -> null
		}
		if (skipReason != null) {
			Timber.d("Skipping delayed display mode restore because %s", skipReason)
			displayModeRestoreJob = null
			restoreDisplayMode(preparation, originalPreference, window = null)
			return
		}
		val playerWindow = window ?: return
		// The fragment may be gone before the delay finishes, so keep the job with the activity.
		// If another player starts in the meantime, the ownership check leaves its mode alone.
		Timber.i(
			"Scheduling preferred display mode restore from %d to %s in 2 seconds",
			playerWindow.attributes.preferredDisplayModeId,
			originalPreference,
		)
		displayModeRestoreJob = requireActivity().lifecycleScope.launch {
			// Give the player and TV a moment to settle in SDR before restoring the mode.
			delay(2.seconds)
			restoreDisplayMode(preparation, originalPreference, playerWindow)
		}
	}

	override fun onDestroy() {
		if (displayModeRestoreJob == null) scheduleDisplayModeRestore()
		displayModePreparation = null
		playbackManager.getService<QueueService>()?.clearEntryChangePredicate(entryChangePredicate)
		super.onDestroy()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		BaseScreen {
			VideoPlayerScreen(
				onRemoteKeyEventHandlerChanged = { handler ->
					remoteKeyEventHandler = handler
				},
				onClosePlayer = ::closePlayer,
				onPlaybackComplete = ::onPlaybackComplete,
				onPlayNext = ::playNext,
			)
		}
	}

	override fun onDestroyView() {
		remoteKeyEventHandler = null
		super.onDestroyView()
	}

	override fun onKey(
		v: View?,
		keyCode: Int,
		event: KeyEvent?
	) = remoteKeyEventHandler?.invoke(keyCode, event) == true

	private fun closePlayer() {
		if (closingPlayer) return
		closingPlayer = true
		remoteKeyEventHandler = null

		notifyPlaybackChanged()
		if (shouldReturnToLiveTvGuide()) {
			if (!navigationRepository.goBack()) {
				navigationRepository.reset(Destinations.home)
			}
			return
		}

		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		if (arguments?.getBoolean(EXTRA_CLOSE_TO_LIVE_TV_LIBRARY) == true) {
			if (!navigationRepository.goBack() || !navigationRepository.goBack()) {
				navigationRepository.reset(Destinations.home)
			}
		} else if (!navigationRepository.goBack()) {
			navigationRepository.reset(Destinations.home)
		}
	}

	private fun notifyPlaybackChanged(item: BaseItemDto? = playbackManager.queue.entry.value?.baseItem) {
		dataRefreshService.notifyPlayback(item)
	}

	private fun shouldReturnToLiveTvGuide(): Boolean =
		arguments?.getBoolean(EXTRA_CLOSE_TO_LIVE_TV_LIBRARY) == true &&
			playbackManager.queue.entry.value?.isLiveTv == true

	private fun recordCompleted(entry: QueueEntry, completedPosition: Duration): Boolean {
		if (
			entry !== playbackManager.queue.entry.value ||
			completedEntry === entry ||
			handingOffPlayer ||
			closingPlayer ||
			showingPostPlaybackPrompt
		) return false

		completedEntry = entry
		notifyPlaybackChanged(entry.baseItem)
		playbackManager.getService<PlaySessionService>()?.sendStopIfActive(
			completedPosition.takeIf { it > Duration.ZERO }
		)
		entry.baseItem?.takeIf { item -> item.type == BaseItemKind.EPISODE }?.let { episode ->
			interactionTracker.onEpisodeWatched()
		}
		return true
	}

	private fun onPlaybackComplete(entry: QueueEntry, completedPosition: Duration) {
		if (
			entry !== playbackManager.queue.entry.value ||
			entry.isLiveTv ||
			!playbackManager.state.playState.value.allowsTimedCompletion()
		) return
		if (!recordCompleted(entry, completedPosition)) return
		if (showPostPlaybackPromptIfAvailable(entry)) return

		lifecycleScope.launch {
			if (!advanceToNext(entry)) {
				Timber.i("Video queue ended, closing player")
				closePlayer()
			}
		}
	}

	private suspend fun playNext(entry: QueueEntry, completedPosition: Duration): Boolean {
		if (entry !== playbackManager.queue.entry.value || closingPlayer || showingPostPlaybackPrompt) return false
		recordCompleted(entry, completedPosition)
		return advanceToNext(entry)
	}

	private suspend fun advanceToNext(entry: QueueEntry): Boolean {
		if (entry !== playbackManager.queue.entry.value) return false
		val currentIndex = playbackManager.queue.indexOf(entry) ?: playbackManager.queue.entryIndex.value
		val nextIndex = currentIndex + 1
		if (currentIndex < 0 || nextIndex > videoQueueManager.getCurrentVideoQueue().lastIndex) return false

		val nextEntry = playbackManager.queue.peekNext(usePlaybackOrder = false, useRepeatMode = false) ?: return false
		// The next entry may have been preloaded before the latest subtitle selection was known.
		nextEntry.mediaStream = null
		if (playbackManager.queue.next(usePlaybackOrder = false, useRepeatMode = false) == null) return false

		videoQueueManager.setCurrentMediaPosition(nextIndex)
		return true
	}

	internal fun selectPostPlaybackPrompt(itemType: BaseItemKind?, nextItemId: UUID): Destination.Fragment? = when {
		itemType == BaseItemKind.TRAILER -> null
		interactionTracker.getShowStillWatching() -> Destinations.stillWatching(nextItemId)
		userPreferences[UserPreferences.nextUpBehavior] != NextUpBehavior.DISABLED -> Destinations.nextUp(nextItemId)
		else -> null
	}

	private fun showPostPlaybackPromptIfAvailable(entry: QueueEntry): Boolean {
		if (showingPostPlaybackPrompt) return true
		if (closingPlayer) return false

		val currentIndex = playbackManager.queue.indexOf(entry)
			?: playbackManager.queue.entryIndex.value
		if (currentIndex < 0) return false

		val nextIndex = currentIndex + 1
		val nextItem = videoQueueManager.getCurrentVideoQueue().getOrNull(nextIndex) ?: return false
		val destination = selectPostPlaybackPrompt(entry.baseItem?.type, nextItem.id) ?: return false

		Timber.i("Showing post-playback prompt for item ${nextItem.id} after queue index $currentIndex")
		videoQueueManager.setCurrentMediaPosition(nextIndex)
		showingPostPlaybackPrompt = true
		remoteKeyEventHandler = null

		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		navigationRepository.navigate(destination, replace = true)

		return true
	}

	private suspend fun shouldChangeQueueEntry(entry: QueueEntry): Boolean = withContext(Dispatchers.Main.immediate) {
		if (handingOffPlayer) return@withContext false
		if (closingPlayer) return@withContext true
		if (showingPostPlaybackPrompt) return@withContext false

		val nextIndex = playbackManager.queue.indexOf(entry) ?: return@withContext true
		val queue = videoQueueManager.getCurrentVideoQueue()
		val selection = playbackLauncher.getVideoPlayerSelection(queue, nextIndex) ?: return@withContext true

		if (selection.player == PlaybackLauncher.VideoPlayer.NEW) {
			selection.backend?.let { backend ->
				playbackManager.switchBackend(when (backend) {
					PlaybackBackend.SAME_VIDEO_PLAYER,
					PlaybackBackend.EXOPLAYER -> exoPlayerBackend
					PlaybackBackend.LIBVLC -> libVLCBackend
					PlaybackBackend.MPV -> mpvBackend
				})
			}
			return@withContext true
		}

		val fragmentContext = context ?: return@withContext false
		handingOffPlayer = true
		videoQueueManager.setCurrentMediaPosition(nextIndex)
		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		playbackLauncher.launchCurrentVideoQueue(fragmentContext)
		false
	}

	internal fun onQueueEntryChanged(entry: QueueEntry) {
		entry.baseItem?.let(interactionTracker::notifyStart)
		if (entry.isLiveTv) return
		val queueIndex = playbackManager.queue.indexOf(entry)
			?: playbackManager.queue.entryIndex.value
		if (queueIndex >= 0) {
			videoQueueManager.setCurrentMediaPosition(queueIndex)
		}
	}

	override fun onPause() {
		super.onPause()
		interactionTracker.notifyPlaybackState(false)

		if (closingPlayer && shouldReturnToLiveTvGuide()) return
		playbackManager.state.pause()
	}

	override fun onResume() {
		super.onResume()

		playbackManager.state.unpause()
	}

	override fun onStart() {
		super.onStart()
		displayModeRestoreJob?.let { job ->
			if (job.isActive) Timber.i("Cancelling pending display mode restore because playback resumed")
			job.cancel()
		}
		displayModeRestoreJob = null
		playbackManager.options.beforePlayItem = displayModePreparation
	}

	override fun onStop() {
		super.onStop()
		if (closingPlayer && shouldReturnToLiveTvGuide()) {
			// closePlayer already stopped playback before navigating to the guide.
			scheduleDisplayModeRestore()
			return
		}
		notifyPlaybackChanged()
		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		// Exit animations can delay destruction until after the browsing screen resumes.
		scheduleDisplayModeRestore()
	}
}
