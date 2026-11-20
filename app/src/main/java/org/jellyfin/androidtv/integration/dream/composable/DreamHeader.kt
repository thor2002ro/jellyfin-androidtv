package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.integration.dream.DreamWeatherReading
import org.jellyfin.androidtv.preference.constant.ScreensaverWeatherUnits
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import kotlin.math.roundToInt

@Composable
internal fun DreamHeader(
	showClock: Boolean,
	weather: DreamWeatherReading?,
	units: ScreensaverWeatherUnits,
) = Box(
	modifier = Modifier
		.fillMaxWidth()
		.overscan(),
) {
	// Clock
	AnimatedVisibility(
		visible = showClock,
		enter = fadeIn(),
		exit = fadeOut(),
		modifier = Modifier
			.align(Alignment.TopEnd),
	) {
		Column(horizontalAlignment = Alignment.End) {
			ToolbarClock()
			if (weather != null) DreamWeatherLine(weather, units)
		}
	}
}

@Composable
private fun DreamWeatherLine(weather: DreamWeatherReading, units: ScreensaverWeatherUnits) {
	val condition = weather.condition.label
	val temperature = when (units) {
		ScreensaverWeatherUnits.CELSIUS -> weather.celsius
		ScreensaverWeatherUnits.FAHRENHEIT -> weather.celsius * 9 / 5 + 32
	}.roundToInt()
	val formatted = stringResource(R.string.screensaver_weather_temperature, temperature, stringResource(units.nameRes))
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
		DreamWeatherIcon(weather.condition, weather.isDay)
		Text(
			text = if (condition == null) formatted else stringResource(R.string.screensaver_weather_conditions, formatted, stringResource(condition)),
			fontSize = 14.sp,
			color = Color.White,
		)
	}
	weather.city?.let { city ->
		Text(
			text = city,
			modifier = Modifier.widthIn(max = 240.dp),
			fontSize = 12.sp,
			color = Color.White,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
