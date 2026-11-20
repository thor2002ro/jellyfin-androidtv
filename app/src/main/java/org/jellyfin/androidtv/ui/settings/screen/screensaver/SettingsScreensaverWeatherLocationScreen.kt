package org.jellyfin.androidtv.ui.settings.screen.screensaver

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.dream.DreamWeather
import org.jellyfin.androidtv.integration.dream.DreamWeatherLocation
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.LocalRouteContext
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.commitPreferenceChange
import org.jellyfin.androidtv.ui.settings.compat.showPreferenceSaveFailure
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
internal fun SettingsScreensaverWeatherLocationScreen() {
	val router = LocalRouter.current
	val route = LocalRouteContext.current
	val context = LocalContext.current
	val preferences = koinInject<UserPreferences>()
	val scope = rememberCoroutineScope()
	val currentLocation = remember(preferences) {
		DreamWeatherLocation.decode(preferences[UserPreferences.screensaverWeatherLocation])
	}
	var query by remember { mutableStateOf("") }
	var searching by remember { mutableStateOf(false) }
	var locations by remember { mutableStateOf<List<DreamWeatherLocation>?>(null) }
	var searchJob by remember { mutableStateOf<Job?>(null) }
	var saving by remember { mutableStateOf(false) }
	val cities = (listOfNotNull(currentLocation) + locations.orEmpty()).distinctBy { it.latitude to it.longitude }

	fun select(location: DreamWeatherLocation?) {
		if (saving) return
		saving = true
		scope.launch {
			val result = commitPreferenceChange(preferences, UserPreferences.screensaverWeatherLocation, location?.encode().orEmpty())
			if (result.succeeded) {
				if (router.backStack.lastOrNull() == route) router.back()
			} else {
				saving = false
				showPreferenceSaveFailure(context)
			}
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_screensaver_weather).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_screensaver_weather_location)) },
			)
		}
		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.screensaver_weather_location_automatic)) },
				captionContent = { Text(stringResource(R.string.screensaver_weather_location_ip)) },
				trailingContent = { RadioButton(checked = currentLocation == null) },
				onClick = { select(null) },
				modifier = Modifier.focusKey("weather_location_automatic", initialFocus = currentLocation == null),
			)
		}
		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.screensaver_weather_search_city)) },
				onClick = {
					showCitySearch(context, query) { submitted ->
						searchJob?.cancel()
						query = submitted
						searching = true
						locations = null
						searchJob = scope.launch {
							locations = DreamWeather.searchLocations(submitted)
							searching = false
						}
					}
				},
				modifier = Modifier.focusKey("weather_location_search"),
			)
		}
		if (query.isNotEmpty()) item {
			val status = when {
				searching -> R.string.screensaver_weather_searching
				locations == null -> R.string.screensaver_weather_search_failed
				locations?.isEmpty() == true -> R.string.screensaver_weather_search_empty
				else -> null
			}
			if (status != null) Text(stringResource(status))
		}
		items(cities) { city ->
			val selected = city == currentLocation
			ListButton(
				headingContent = { Text(city.name) },
				captionContent = { Text(city.region) },
				trailingContent = { RadioButton(checked = selected) },
				onClick = { select(city) },
				modifier = Modifier.focusKey("weather_city_${city.latitude}_${city.longitude}_${city.name}", initialFocus = selected),
			)
		}
	}
}

private fun showCitySearch(context: Context, query: String, onSearch: (String) -> Unit) {
	val input = EditText(context).apply {
		setSingleLine(true)
		inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
		imeOptions = EditorInfo.IME_ACTION_SEARCH
		setHint(R.string.screensaver_weather_city_hint)
		setText(query)
		setSelection(text.length)
	}
	val dialog = AlertDialog.Builder(context)
		.setTitle(R.string.screensaver_weather_search_city)
		.setView(input)
		.setNegativeButton(android.R.string.cancel, null)
		.setPositiveButton(android.R.string.search_go, null)
		.create()
	fun submit() {
		val city = input.text.toString().trim()
		if (city.length < 2) {
			input.error = context.getString(R.string.screensaver_weather_search_short)
			return
		}
		dialog.dismiss()
		onSearch(city)
	}
	input.setOnEditorActionListener { _, action, _ ->
		if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
		submit()
		true
	}
	dialog.setOnShowListener {
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
		input.requestFocus()
		dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
	}
	dialog.show()
}
