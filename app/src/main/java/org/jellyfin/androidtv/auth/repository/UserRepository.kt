package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.androidtv.util.TrackSelectionManager
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.sdk.model.api.UserDto

/**
 * Repository to get the current authenticated user.
 */
interface UserRepository {
	val currentUser: StateFlow<UserDto?>

	fun setCurrentUser(user: UserDto?)
}

class UserRepositoryImpl(private val videoQueueManager: VideoQueueManager) : UserRepository {
	override val currentUser = MutableStateFlow<UserDto?>(null)

	override fun setCurrentUser(user: UserDto?) {
		if (currentUser.value?.trackSelectionScope != user?.trackSelectionScope) videoQueueManager.clearVideoQueue()
		TrackSelectionManager.setScope(user?.trackSelectionScope)
		currentUser.value = user
	}

	private val UserDto.trackSelectionScope: String
		get() = listOfNotNull(
			serverId?.takeIf(String::isNotBlank),
			id.toString(),
		).joinToString(separator = ":")
}
