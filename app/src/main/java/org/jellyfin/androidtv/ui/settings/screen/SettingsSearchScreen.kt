package org.jellyfin.androidtv.ui.settings.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.filterSettingsSearchResults
import org.jellyfin.androidtv.ui.settings.resolveSettingsSearchResults

@Composable
fun SettingsSearchScreen() {
	val context = LocalContext.current
	val focusManager = LocalFocusManager.current
	val router = LocalRouter.current
	val inputFocusRequester = remember { FocusRequester() }
	val settings = remember(context) { resolveSettingsSearchResults(context) }
	var query by rememberSaveable { mutableStateOf("") }
	val results = remember(settings, query) { filterSettingsSearchResults(settings, query) }

	LaunchedEffect(Unit) {
		inputFocusRequester.requestFocus()
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.app_name).uppercase()) },
				headingContent = { Text(stringResource(R.string.settings_search)) },
			)
		}

		item {
			SearchTextInput(
				query = query,
				onQueryChange = { query = it },
				onQuerySubmit = { focusManager.moveFocus(FocusDirection.Down) },
				modifier = Modifier
					.fillMaxWidth()
					.focusRequester(inputFocusRequester),
			)
		}

		items(
			count = results.size,
			key = {
				val result = results[it]
				"${result.route}:${result.focusKey}:${result.label}"
			},
		) { index ->
			val result = results[index]
			ListButton(
				headingContent = { Text(result.label) },
				captionContent = { Text(result.path) },
				onClick = {
					router.push(
						route = result.route,
						initialFocusKey = result.focusKey,
						initialFocusIndex = result.initialFocusIndex,
					)
				},
			)
		}
	}
}
