package org.jellyfin.androidtv.ui.playback.nextup

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.ImageType

class NextUpViewModel(
	private val context: Context,
	private val videoQueueManager: VideoQueueManager,
	private val userPreferences: UserPreferences,
) : ViewModel() {
	private val _item = MutableStateFlow<NextUpItemData?>(null)
	val item: StateFlow<NextUpItemData?> = _item
	private val _state = MutableStateFlow(NextUpState.INITIALIZED)
	val state: StateFlow<NextUpState> = _state

	fun setItemId(id: UUID?) {
		_item.value = id?.let(::loadItemData)
		if (_item.value == null) _state.value = NextUpState.NO_DATA
	}

	fun playNext() {
		_state.value = NextUpState.PLAY_NEXT
	}

	fun close() {
		_state.value = NextUpState.CLOSE
	}

	private fun loadItemData(id: UUID): NextUpItemData? {
		val item = videoQueueManager.getCurrentVideoQueue().firstOrNull { it.id == id } ?: return null

		val thumbnail = item.itemImages[ImageType.PRIMARY]
			.takeIf { userPreferences[UserPreferences.nextUpBehavior] == NextUpBehavior.EXTENDED }
		val logo = item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO]
		val title = item.getDisplayName(context)

		return NextUpItemData(
			item,
			item.id,
			title,
			thumbnail,
			logo,
		)
	}
}
