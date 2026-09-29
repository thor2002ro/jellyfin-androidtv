package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.playsession.PlaySessionService
import org.jellyfin.sdk.api.client.ApiClient
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
	private val api by inject<ApiClient>()
	private var remoteKeyEventHandler: ((keyCode: Int, event: KeyEvent?) -> Boolean)? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Create a queue from the items added to the legacy video queue
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, videoQueueManager.getCurrentVideoQueue(), false)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier, startIndex = videoQueueManager.getCurrentMediaPosition())
		playbackManager.queue.entry
			.mapNotNull { entry -> entry?.baseItem?.liveTvChannelId() }
			.distinctUntilChanged()
			.onEach { channelId -> TvManager.setLastLiveTvChannel(channelId) }
			.launchIn(lifecycleScope)

		// Set position
		arguments?.getInt(EXTRA_POSITION)?.milliseconds?.let {
			lifecycleScope.launch {
				playbackManager.state.seek(it)
			}
		}

		// Pause player until the initial resume
		playbackManager.state.pause()
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
		playbackManager.getService<PlaySessionService>()?.sendStopIfActive()
		playbackManager.state.stop()
		if (!navigationRepository.goBack()) {
			navigationRepository.reset(Destinations.home)
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
