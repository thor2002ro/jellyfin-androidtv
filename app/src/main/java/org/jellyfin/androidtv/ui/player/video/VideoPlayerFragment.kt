package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.sdk.liveTvChannelId
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.isLiveTv
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

class VideoPlayerFragment : Fragment(), View.OnKeyListener {
	companion object {
		const val EXTRA_POSITION: String = "position"
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val navigationRepository by inject<NavigationRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()
	private var remoteKeyEventHandler: ((keyCode: Int, event: KeyEvent?) -> Boolean)? = null
	private var closingPlayer = false
	private var hasSeenVideoQueueEntry = false
	private var closeWhenVideoQueueEnds = false
	private var showingPostPlaybackPrompt = false
	private var mediaStreamEndListener: PlayerBackendEventListener? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Create a queue from the items added to the legacy video queue
		val startIndex = videoQueueManager.getCurrentMediaPosition()
		val startPosition = arguments
			?.takeIf { it.containsKey(EXTRA_POSITION) }
			?.getInt(EXTRA_POSITION)
			?.milliseconds
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(
			api = api,
			items = videoQueueManager.getCurrentVideoQueue(),
			visibleInScreensaver = false,
			initialStartPosition = startPosition,
			initialStartIndex = startIndex,
		)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier, startIndex = startIndex)
		mediaStreamEndListener = object : PlayerBackendEventListener() {
			override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) {
				if (mediaStream.queueEntry !== playbackManager.queue.entry.value) return
				if (mediaStream.queueEntry.isLiveTv) return
				if (showNextUpIfAvailable(mediaStream)) return

				closeWhenVideoQueueEnds = true
				closePlayerIfVideoQueueEnded()
			}
		}.also(playbackManager::addBackendEventListener)
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				playbackManager.queue.entry
					.onEach { entry ->
						if (entry == null) {
							closePlayerIfVideoQueueEnded()
						} else {
							hasSeenVideoQueueEntry = entry.isLiveTv != true
							closeWhenVideoQueueEnds = false
							if (!entry.isLiveTv) {
								syncLegacyVideoQueuePosition(entry)
							}
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

	override fun onDestroy() {
		mediaStreamEndListener?.let(playbackManager::removeBackendEventListener)
		mediaStreamEndListener = null
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

		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		if (!navigationRepository.goBack()) {
			navigationRepository.reset(Destinations.home)
		}
	}

	private fun closePlayerIfVideoQueueEnded() {
		if (
			!hasSeenVideoQueueEntry ||
			!closeWhenVideoQueueEnds ||
			playbackManager.queue.entry.value != null ||
			closingPlayer ||
			showingPostPlaybackPrompt
		) return

		Timber.i("Video queue ended, closing player")
		closeWhenVideoQueueEnds = false
		closePlayer()
	}

	private fun showNextUpIfAvailable(mediaStream: PlayableMediaStream): Boolean {
		if (showingPostPlaybackPrompt) return true
		if (closingPlayer) return false
		when (userPreferences[UserPreferences.nextUpBehavior]) {
			NextUpBehavior.DISABLED -> return false
			NextUpBehavior.MINIMAL,
			NextUpBehavior.EXTENDED -> Unit
		}
		if (mediaStream.queueEntry.baseItem?.type == BaseItemKind.TRAILER) return false

		val currentIndex = playbackManager.queue.indexOf(mediaStream.queueEntry)
			?: playbackManager.queue.entryIndex.value
		if (currentIndex < 0) return false

		val nextIndex = currentIndex + 1
		val nextItem = videoQueueManager.getCurrentVideoQueue().getOrNull(nextIndex) ?: return false

		Timber.i("Showing Next Up for item ${nextItem.id} after queue index $currentIndex")
		videoQueueManager.setCurrentMediaPosition(nextIndex)
		showingPostPlaybackPrompt = true
		closeWhenVideoQueueEnds = false
		remoteKeyEventHandler = null

		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		navigationRepository.navigate(Destinations.nextUp(nextItem.id), replace = true)

		return true
	}

	private fun syncLegacyVideoQueuePosition(entry: QueueEntry) {
		val queueIndex = playbackManager.queue.indexOf(entry)
			?: playbackManager.queue.entryIndex.value
		if (queueIndex >= 0) {
			videoQueueManager.setCurrentMediaPosition(queueIndex)
		}
	}

	override fun onPause() {
		super.onPause()

		playbackManager.state.pause()
	}

	override fun onResume() {
		super.onResume()

		playbackManager.state.unpause()
	}

	override fun onStop() {
		super.onStop()

		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
	}
}
