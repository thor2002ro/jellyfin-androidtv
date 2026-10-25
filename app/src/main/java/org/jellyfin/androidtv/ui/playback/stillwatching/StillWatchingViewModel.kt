package org.jellyfin.androidtv.ui.playback.stillwatching

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.ImageType

class StillWatchingViewModel(
	private val context: Context,
	private val videoQueueManager: VideoQueueManager,
	private val userPreferences: UserPreferences,
	private val interactionTrackerViewModel: InteractionTrackerViewModel,
) : ViewModel() {
	private val _item = MutableStateFlow<StillWatchingItemData?>(null)
	val item: StateFlow<StillWatchingItemData?> = _item
	private val _state = MutableStateFlow(StillWatchingState.INITIALIZED)
	val state: StateFlow<StillWatchingState> = _state

	fun setItemId(id: UUID?) {
		_item.value = id?.let(::loadItemData)
		if (_item.value == null) _state.value = StillWatchingState.NO_DATA
	}

	fun stillWatching() {
		interactionTrackerViewModel.notifyStillWatching()
		_state.value = StillWatchingState.STILL_WATCHING
	}

	fun close() {
		_state.value = StillWatchingState.CLOSE
	}

	private fun loadItemData(id: UUID): StillWatchingItemData? {
		val item = videoQueueManager.getCurrentVideoQueue().firstOrNull { it.id == id } ?: return null

		val thumbnail = when (userPreferences[UserPreferences.nextUpBehavior]) {
			NextUpBehavior.EXTENDED -> item.itemImages[ImageType.PRIMARY]
			else -> null
		}
		val logo = item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO]
		val title = item.getDisplayName(context)

		return StillWatchingItemData(
			item,
			item.id,
			title,
			thumbnail,
			logo
		)
	}
}

