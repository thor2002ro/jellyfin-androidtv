package org.jellyfin.androidtv.ui.settings.screen

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsAsyncActionListButton
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.koin.compose.koinInject

@Composable
fun SettingsMainScreen() {
	val router = LocalRouter.current
	val context = LocalContext.current

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.app_name).uppercase()) },
				headingContent = { Text(stringResource(R.string.settings)) },
				captionContent = { Text(stringResource(R.string.settings_description)) },
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_users), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_login)) },
				onClick = { router.push(Routes.AUTHENTICATION) },
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_adjust), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_customization)) },
				onClick = { router.push(Routes.CUSTOMIZATION) }
			)
		}

		// TODO: Temporarily added to root - should be accessed via customization screen instead
		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_photos), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_screensaver)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_next), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback)) },
				onClick = { router.push(Routes.PLAYBACK) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_error), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_telemetry_category)) },
				onClick = { router.push(Routes.TELEMETRY) }
			)

		}

		item {
			val api = koinInject<ApiClient>()
			SettingsAsyncActionListButton(
				headingContent = { Text(stringResource(R.string.pref_upload_logs_title)) },
				captionContent = { Text(stringResource(R.string.pref_upload_logs_summary)) },
				action = {
					val logs = ProcessBuilder("logcat", "-d", "-v", "threadtime")
						.redirectErrorStream(true)
						.start()
						.inputStream.bufferedReader().use { it.readText() }
					val response by api.clientLogApi.logFile(logs)
					response
				},
				onSuccess = { result ->
					Toast.makeText(context, context.getString(R.string.pref_upload_logs_success, result.fileName), Toast.LENGTH_LONG).show()
				},
				onFailure = {
					Toast.makeText(context, R.string.pref_upload_logs_failure, Toast.LENGTH_LONG).show()
				},
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
				headingContent = { Text("App updates") },
				onClick = { router.push(Routes.APP_UPDATES) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_jellyfin), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
				onClick = { router.push(Routes.ABOUT) }
			)
		}
	}
}
