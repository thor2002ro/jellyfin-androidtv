package org.jellyfin.androidtv.ui.settings.screen

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.LocalListButtonFocusHandler
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsAsyncActionListButton
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.AppLogging
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.koin.compose.koinInject

@Composable
fun SettingsMainScreen() {
	val router = LocalRouter.current
	val context = LocalContext.current
	val searchFocusRequester = remember { FocusRequester() }
	var searchFocused by remember { mutableStateOf(false) }
	val searchWidth by animateFloatAsState(if (searchFocused) 1f else 0.25f, label = "settingsSearchWidth")
	val searchColors = if (searchFocused) {
		JellyfinTheme.colorScheme.inputFocused to JellyfinTheme.colorScheme.onInputFocused
	} else {
		JellyfinTheme.colorScheme.input to JellyfinTheme.colorScheme.onInput
	}

	SettingsColumn {
		item {
			val searchFocusKey = currentCompositeKeyHashCode
			val searchFocusHandler = LocalListButtonFocusHandler.current
			LaunchedEffect(searchFocusKey, searchFocusRequester, searchFocused, searchFocusHandler) {
				searchFocusHandler?.invoke(searchFocusKey, searchFocusRequester, searchFocused)
			}

			Box(Modifier.fillMaxWidth()) {
				ListSection(
					overlineContent = { Text(stringResource(R.string.app_name).uppercase()) },
					headingContent = { Text(stringResource(R.string.settings)) },
					captionContent = { Text(stringResource(R.string.settings_description)) },
				)
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.align(Alignment.CenterEnd)
						.fillMaxWidth(searchWidth)
						.background(JellyfinTheme.colorScheme.surface, RoundedCornerShape(percent = 30))
						.border(2.dp, searchColors.first, RoundedCornerShape(percent = 30))
						.padding(12.dp)
						.onFocusChanged { searchFocused = it.isFocused }
						.focusKey(Routes.SEARCH, searchFocusRequester)
						.clickable { router.push(Routes.SEARCH) },
				) {
					Icon(
						painterResource(R.drawable.ic_search),
						contentDescription = stringResource(R.string.settings_search),
						tint = searchColors.second,
					)
					Spacer(Modifier.width(12.dp))
				}
			}
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_users), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_login)) },
				onClick = { router.push(Routes.AUTHENTICATION) },
				modifier = Modifier.focusKey(Routes.AUTHENTICATION),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_adjust), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_customization)) },
				onClick = { router.push(Routes.CUSTOMIZATION) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION),
			)
		}

		// TODO: Temporarily added to root - should be accessed via customization screen instead
		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_photos), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_screensaver)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_next), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_playback)) },
				onClick = { router.push(Routes.PLAYBACK) },
				modifier = Modifier.focusKey(Routes.PLAYBACK),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_error), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_telemetry_category)) },
				onClick = { router.push(Routes.TELEMETRY) },
				modifier = Modifier.focusKey(Routes.TELEMETRY),
			)

		}

		item {
			val api = koinInject<ApiClient>()
			SettingsAsyncActionListButton(
				headingContent = { Text(stringResource(R.string.pref_upload_logs_title)) },
				captionContent = { Text(stringResource(R.string.pref_upload_logs_summary)) },
				action = {
					val memoryLogs = AppLogging.dumpMemoryLogs()
					val logs = ProcessBuilder("logcat", "-d", "-v", "threadtime")
						.redirectErrorStream(true)
						.start()
						.inputStream.bufferedReader().use { it.readText() }
					val response by api.systemApi.logFile("=== App RAM logs ===\n$memoryLogs\n=== Logcat ===\n$logs")
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
				headingContent = { Text(stringResource(R.string.app_updates)) },
				onClick = { router.push(Routes.APP_UPDATES) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_jellyfin), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
				onClick = { router.push(Routes.ABOUT) },
				modifier = Modifier.focusKey(Routes.ABOUT),
			)
		}
	}
}
