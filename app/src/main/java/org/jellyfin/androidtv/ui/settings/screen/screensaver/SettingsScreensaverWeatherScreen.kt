package org.jellyfin.androidtv.ui.settings.screen.screensaver

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.dream.DreamWeatherLocation
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ScreensaverWeatherUnits
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
internal fun SettingsScreensaverWeatherScreen() {
	val router = LocalRouter.current
	val preferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_screensaver).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_screensaver_weather)) },
				captionContent = { Text(stringResource(R.string.screensaver_weather_attribution)) },
			)
		}
		item {
			var enabled by rememberPreference(preferences, UserPreferences.screensaverWeatherEnabled)
			WeatherToggle(R.string.pref_screensaver_weather_enabled, enabled, "screensaver_weather_enabled") { enabled = !enabled }
		}
		item {
			var clockWeather by rememberPreference(preferences, UserPreferences.screensaverWeather)
			WeatherToggle(R.string.pref_screensaver_weather_clock, clockWeather, "screensaver_weather") { clockWeather = !clockWeather }
		}
		item {
			var effects by rememberPreference(preferences, UserPreferences.screensaverWeatherEffects)
			WeatherToggle(R.string.pref_screensaver_weather_effects, effects, "screensaver_weather_effects") { effects = !effects }
		}
		item {
			var units by rememberPreference(preferences, UserPreferences.screensaverWeatherUnits)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_weather_units)) },
				captionContent = { Text(stringResource(units.nameRes)) },
				onClick = {
					units = if (units == ScreensaverWeatherUnits.CELSIUS) ScreensaverWeatherUnits.FAHRENHEIT else ScreensaverWeatherUnits.CELSIUS
				},
				modifier = Modifier.focusKey("screensaver_weather_units"),
			)
		}
		item {
			val location = DreamWeatherLocation.decode(preferences[UserPreferences.screensaverWeatherLocation])
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_weather_location)) },
				captionContent = { Text(location?.name ?: stringResource(R.string.screensaver_weather_location_automatic)) },
				onClick = { router.push(Routes.CUSTOMIZATION_SCREENSAVER_WEATHER_LOCATION) },
				modifier = Modifier.focusKey(Routes.CUSTOMIZATION_SCREENSAVER_WEATHER_LOCATION),
			)
		}
	}
}

@Composable
private fun WeatherToggle(@StringRes title: Int, checked: Boolean, key: String, onClick: () -> Unit) {
	ListButton(
		headingContent = { Text(stringResource(title)) },
		trailingContent = { Checkbox(checked = checked) },
		onClick = onClick,
		modifier = Modifier.focusKey(key),
	)
}
