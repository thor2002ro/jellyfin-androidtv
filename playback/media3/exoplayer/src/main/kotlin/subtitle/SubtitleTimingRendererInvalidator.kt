package org.jellyfin.playback.media3.exoplayer.subtitle

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset

/** Refreshes Media3's cached subtitle event index after live timing changes. */
@UnstableApi
internal class SubtitleTimingRendererInvalidator(
	state: SubtitleTimingOffsetState,
	private val playerProvider: () -> ExoPlayer?,
	private val refreshSupported: () -> Boolean,
) {
	private companion object {
		const val REFRESH_DELAY_MS = 150L
	}

	private var handler: Handler? = null
	private var pendingMediaItem: MediaItem? = null
	private val refresh = Runnable {
		val expectedMediaItem = pendingMediaItem ?: return@Runnable
		pendingMediaItem = null
		if (!refreshSupported()) return@Runnable
		val player = playerProvider() ?: return@Runnable
		if (player.playbackState != Player.STATE_IDLE && player.currentMediaItem === expectedMediaItem) {
			player.seekTo(player.currentPosition)
		}
	}

	init {
		state.addTimingListener({ scheduleRefresh() }, notifyCurrent = false)
	}

	private fun scheduleRefresh() {
		if (!refreshSupported()) {
			cancel()
			return
		}
		val player = playerProvider() ?: return
		val mediaItem = player.currentMediaItem ?: return
		if ((mediaItem.localConfiguration?.tag as? QueueEntry)?.liveStreamTargetOffset != null) return
		val currentHandler = handler
			?.takeIf { it.looper == player.applicationLooper }
			?: Handler(player.applicationLooper).also { handler = it }
		currentHandler.removeCallbacks(refresh)
		pendingMediaItem = mediaItem
		currentHandler.postDelayed(refresh, REFRESH_DELAY_MS)
	}

	fun cancel() {
		handler?.removeCallbacks(refresh)
		pendingMediaItem = null
	}
}
