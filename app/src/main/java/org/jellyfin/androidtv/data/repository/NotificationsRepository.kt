package org.jellyfin.androidtv.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.data.model.AppNotification
import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.androidtv.util.isTvDevice
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.updater.AppUpdate

interface NotificationsRepository {
	val notifications: StateFlow<List<AppNotification>>
	val appUpdate: StateFlow<AppUpdate?>
	val appUpdatePrompt: StateFlow<AppUpdate?>

	fun dismissNotification(item: AppNotification)
	fun addDefaultNotifications()
	fun updateServerNotifications(server: Server?)
	fun updateAppUpdateNotification(update: AppUpdate?, prompt: Boolean = true)
	fun dismissAppUpdatePrompt()
}

class NotificationsRepositoryImpl(
	private val context: Context,
	private val systemPreferences: SystemPreferences,
) : NotificationsRepository {
	override val notifications = MutableStateFlow(emptyList<AppNotification>())
	private val _appUpdate = MutableStateFlow<AppUpdate?>(null)
	override val appUpdate = _appUpdate.asStateFlow()
	private val _appUpdatePrompt = MutableStateFlow<AppUpdate?>(null)
	override val appUpdatePrompt = _appUpdatePrompt.asStateFlow()

	init {
		addDefaultNotifications()
	}

	override fun dismissNotification(item: AppNotification) {
		notifications.value = notifications.value.filter { it != item }
		item.dismiss()
	}

	override fun addDefaultNotifications() {
		addUiModeNotification()
		addBetaNotification()
	}

	private fun addNotification(
		message: String,
		public: Boolean = false,
		dismiss: () -> Unit = {}
	): AppNotification {
		val notification = AppNotification(message, dismiss, public)
		notifications.value += notification
		return notification
	}

	private fun removeNotification(notification: AppNotification) {
		notifications.value -= notification
	}

	private fun addUiModeNotification() {
		val disableUiModeWarning = systemPreferences[SystemPreferences.disableUiModeWarning]

		if (!context.isTvDevice() && !disableUiModeWarning) {
			addNotification(
				context.getString(R.string.app_notification_uimode_invalid),
				public = true
			)
		}
	}

	private fun addBetaNotification() {
		val dismissedVersion = systemPreferences[SystemPreferences.dismissedBetaNotificationVersion]
		val currentVersion = BuildConfig.VERSION_NAME
		val isBeta = currentVersion.lowercase().contains("beta")

		if (isBeta && currentVersion != dismissedVersion) {
			addNotification(context.getString(R.string.app_notification_beta, currentVersion)) {
				systemPreferences[SystemPreferences.dismissedBetaNotificationVersion] =
					currentVersion
			}
		}
	}

	// Update server notification
	private var _updateServerNotification: AppNotification? = null
	override fun updateServerNotifications(server: Server?) {
		// Remove current update notification
		_updateServerNotification?.let(::removeNotification)

		val currentServerVersion = server?.version?.let(ServerVersion::fromString) ?: return
		if (currentServerVersion < ServerRepository.upcomingMinimumServerVersion) {
			_updateServerNotification =
				addNotification(
					message = context.getString(
						R.string.app_notification_update_soon,
						currentServerVersion,
						ServerRepository.upcomingMinimumServerVersion
					),
				)
		}
	}

	private var _appUpdateNotification: AppNotification? = null
	override fun updateAppUpdateNotification(update: AppUpdate?, prompt: Boolean) {
		_appUpdateNotification?.let(::removeNotification)
		_appUpdate.value = update
		_appUpdatePrompt.value = if (prompt) update else null
		_appUpdateNotification = update?.let {
			addNotification("Jellyfin Thor ${it.versionName} is available. Open Settings > App updates to install it.")
		}
	}

	override fun dismissAppUpdatePrompt() {
		_appUpdatePrompt.value = null
	}
}
