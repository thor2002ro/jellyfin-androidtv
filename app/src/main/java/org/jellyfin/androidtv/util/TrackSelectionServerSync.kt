package org.jellyfin.androidtv.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.UserConfiguration
import timber.log.Timber

class TrackSelectionServerSync(
	private val api: ApiClient,
	private val userRepository: UserRepository,
) {
	private val configurationMutex = Mutex()

	suspend fun saveAudioSelection(stream: MediaStream?) {
		val language = stream?.language?.takeIf { it.isNotBlank() } ?: return
		updateConfiguration { configuration ->
			configuration.copy(
				audioLanguagePreference = language,
				playDefaultAudioTrack = false,
				rememberAudioSelections = true,
			)
		}
	}

	suspend fun saveSubtitleSelection(stream: MediaStream?) {
		updateConfiguration { configuration ->
			if (stream?.language.isNullOrBlank()) {
				configuration.copy(
					rememberSubtitleSelections = true,
				)
			} else {
				configuration.copy(
					subtitleLanguagePreference = stream.language,
					rememberSubtitleSelections = true,
				)
			}
		}
	}

	private suspend fun updateConfiguration(
		body: (UserConfiguration) -> UserConfiguration,
	) = configurationMutex.withLock {
		val user = loadCurrentUser() ?: return@withLock

		val configuration = user.configuration ?: return@withLock
		val updatedConfiguration = body(configuration)
		if (updatedConfiguration == configuration) return@withLock

		runCatching {
			withContext(Dispatchers.IO) {
				api.userApi.updateUserConfiguration(userId = user.id, data = updatedConfiguration)
			}
		}.onSuccess {
			userRepository.setCurrentUser(user.copy(configuration = updatedConfiguration))
		}.onFailure { error ->
			Timber.w(error, "Unable to save track selection to server user configuration")
		}
	}

	private suspend fun loadCurrentUser() = runCatching {
		withContext(Dispatchers.IO) {
			api.userApi.getCurrentUser().content
		}
	}.getOrElse { error ->
		userRepository.currentUser.value ?: run {
			Timber.w(error, "Unable to load current user to save track selection")
			null
		}
	}
}
