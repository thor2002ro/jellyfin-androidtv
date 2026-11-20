package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jellyfin.androidtv.integration.dream.DreamViewModel
import org.jellyfin.androidtv.integration.dream.DreamWeatherLocation
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ClockBehavior
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun DreamHost() {
	val viewModel = koinViewModel<DreamViewModel>()
	val userPreferences = koinInject<UserPreferences>()
	val bouncingLogo = userPreferences[UserPreferences.screensaverBouncingLogo]
	val weatherEnabled = userPreferences[UserPreferences.screensaverWeatherEnabled]
	val content = if (bouncingLogo) DreamContent.Logo else viewModel.content.collectAsState().value
	val pausedContent by viewModel.pausedContent.collectAsState()

	DreamView(
		content = content,
		pausedContent = pausedContent,
		motion = userPreferences[UserPreferences.screensaverMotion],
		slideTransition = userPreferences[UserPreferences.screensaverTransition],
		bouncingLogo = bouncingLogo,
		showSnow = userPreferences[UserPreferences.screensaverSnow],
		showWeather = weatherEnabled && userPreferences[UserPreferences.screensaverWeather],
		showWeatherEffects = weatherEnabled && userPreferences[UserPreferences.screensaverWeatherEffects],
		weatherUnits = userPreferences[UserPreferences.screensaverWeatherUnits],
		weatherLocation = DreamWeatherLocation.decode(userPreferences[UserPreferences.screensaverWeatherLocation]),
		showClock = when (userPreferences[UserPreferences.clockBehavior]) {
			ClockBehavior.ALWAYS, ClockBehavior.IN_MENUS -> true
			else -> false
		}
	)
}
