package org.jellyfin.androidtv.ui.playback

import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.itemhandling.AudioQueueBaseRowItem
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import timber.log.Timber

class AudioQueueBaseRowAdapter(
	private val playbackManager: PlaybackManager,
	lifecycleScope: LifecycleCoroutineScope,
) : MutableObjectAdapter<AudioQueueBaseRowItem>(CardPresenter(true, @Suppress("MagicNumber") 140)) {
	init {
		lifecycleScope.launch {
			watchPlaybackStateChanges()
		}
	}

	private suspend fun watchPlaybackStateChanges() {
		combine(
			playbackManager.queue.entry,
			playbackManager.queue.entries,
			playbackManager.state.playbackOrder,
		) { _, _, _ -> Unit }.collectLatest { updateAdapter() }
	}

	@Suppress("TooGenericExceptionCaught")
	private suspend fun updateAdapter() {
		val upcomingEntries = try {
			playbackManager.queue.peekNext(100)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "Unable to load upcoming audio queue entries")
			playbackManager.queue.peekNextCached(100)
		}
		val currentItem = playbackManager.queue.entry.value?.let(::AudioQueueBaseRowItem)?.apply {
			playing = true
		}

		val upcomingItems = upcomingEntries
			.mapNotNull { item -> item.takeIf { it.baseItem != null }?.let(::AudioQueueBaseRowItem) }

		val items = listOfNotNull(currentItem) + upcomingItems

		// Update item row
		replaceAll(
			items,
			areItemsTheSame = { old, new -> old.baseItem?.id == new.baseItem?.id },
			// The equals functions for BaseRowItem only compare by id
			areContentsTheSame = { _, _ -> false },
		)
	}
}
