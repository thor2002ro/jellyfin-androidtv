package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class ScreensaverWeatherUnits(override val nameRes: Int) : PreferenceEnum {
	CELSIUS(R.string.screensaver_weather_celsius),
	FAHRENHEIT(R.string.screensaver_weather_fahrenheit),
}

enum class ScreensaverBackdropSource(override val nameRes: Int) : PreferenceEnum {
	LIBRARY(R.string.screensaver_source_library),
	SERVER_PROVIDERS(R.string.screensaver_source_server_providers),
}

enum class ScreensaverMotion(override val nameRes: Int) : PreferenceEnum {
	ZOOM_IN(R.string.screensaver_motion_zoom_in),
	ZOOM_OUT(R.string.screensaver_motion_zoom_out),
	PAN(R.string.screensaver_motion_pan),
	PAN_AND_ZOOM(R.string.screensaver_motion_pan_and_zoom),
	DIAGONAL_DRIFT(R.string.screensaver_motion_diagonal_drift),
	RANDOM(R.string.screensaver_effect_random),
}

enum class ScreensaverTransition(override val nameRes: Int) : PreferenceEnum {
	FADE(R.string.screensaver_transition_fade),
	FADE_THROUGH_BLACK(R.string.screensaver_transition_fade_through_black),
	WIPE(R.string.screensaver_transition_wipe),
	SLIDE(R.string.screensaver_transition_slide),
	RANDOM(R.string.screensaver_effect_random),
}
