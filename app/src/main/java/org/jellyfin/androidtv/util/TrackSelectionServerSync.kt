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

	suspend fun saveAudioSelection(stream: MediaStream?): Boolean {
		val language = stream?.language.toIso2LanguageCodeOrNull() ?: return false
		return savePreferredAudioLanguage(language, playDefaultAudioTrack = false)
	}

	suspend fun saveSubtitleSelection(stream: MediaStream?): Boolean =
		updateConfiguration { configuration ->
			val language = stream?.language.toIso2LanguageCodeOrNull()
			if (language == null) {
				configuration.copy(
					rememberSubtitleSelections = true,
				)
			} else {
				configuration.copy(
					subtitleLanguagePreference = language,
					rememberSubtitleSelections = true,
				)
			}
		}

	suspend fun savePreferredAudioLanguage(
		language: String?,
		playDefaultAudioTrack: Boolean? = null,
	): Boolean =
		updateConfiguration { configuration ->
			configuration.copy(
				audioLanguagePreference = language.toIso2LanguageCodeOrNull(),
				playDefaultAudioTrack = playDefaultAudioTrack ?: configuration.playDefaultAudioTrack,
				rememberAudioSelections = true,
			)
		}

	suspend fun savePlayDefaultAudioTrack(enabled: Boolean): Boolean =
		updateConfiguration { configuration ->
			configuration.copy(
				playDefaultAudioTrack = enabled,
				rememberAudioSelections = true,
			)
		}

	suspend fun savePreferredSubtitleLanguage(language: String?): Boolean =
		updateConfiguration { configuration ->
			configuration.copy(
				subtitleLanguagePreference = language.toIso2LanguageCodeOrNull(),
				rememberSubtitleSelections = true,
			)
		}

	private suspend fun updateConfiguration(
		body: (UserConfiguration) -> UserConfiguration,
	): Boolean = configurationMutex.withLock {
		val user = loadCurrentUser() ?: return@withLock false

		val configuration = user.configuration ?: return@withLock false
		val updatedConfiguration = body(configuration)
		if (updatedConfiguration == configuration) return@withLock true

		runCatching {
			withContext(Dispatchers.IO) {
				api.userApi.updateUserConfiguration(userId = user.id, data = updatedConfiguration)
			}
		}.onSuccess {
			userRepository.setCurrentUser(user.copy(configuration = updatedConfiguration))
		}.onFailure { error ->
			Timber.w(error, "Unable to save track selection to server user configuration")
		}.isSuccess
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
