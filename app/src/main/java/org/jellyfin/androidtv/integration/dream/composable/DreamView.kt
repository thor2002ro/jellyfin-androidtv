package org.jellyfin.androidtv.integration.dream.composable

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jellyfin.androidtv.integration.dream.DreamWeather
import org.jellyfin.androidtv.integration.dream.DreamWeatherCondition
import org.jellyfin.androidtv.integration.dream.DreamWeatherLocation
import org.jellyfin.androidtv.integration.dream.DreamWeatherReading
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.preference.constant.ScreensaverMotion
import org.jellyfin.androidtv.preference.constant.ScreensaverTransition
import org.jellyfin.androidtv.preference.constant.ScreensaverWeatherUnits
import kotlin.time.Duration.Companion.minutes

@Composable
internal fun DreamView(
	content: DreamContent,
	pausedContent: DreamContent.NowPlaying?,
	showClock: Boolean,
	motion: ScreensaverMotion,
	slideTransition: ScreensaverTransition,
	bouncingLogo: Boolean,
	showSnow: Boolean,
	showWeather: Boolean,
	showWeatherEffects: Boolean,
	weatherUnits: ScreensaverWeatherUnits,
	weatherLocation: DreamWeatherLocation?,
) = Box(
	modifier = Modifier.fillMaxSize().background(Color.Black),
) {
	val selectedTransition = remember(content, slideTransition) {
		if (slideTransition == ScreensaverTransition.RANDOM) ScreensaverTransition.entries.filter { it != ScreensaverTransition.RANDOM }.random()
		else slideTransition
	}

	AnimatedContent(
		targetState = content,
		modifier = Modifier.clipToBounds(),
		transitionSpec = {
			val effect = if (initialState is DreamContent.LibraryShowcase && targetState is DreamContent.LibraryShowcase) {
				selectedTransition
			} else ScreensaverTransition.FADE

			when (effect) {
				ScreensaverTransition.FADE, ScreensaverTransition.RANDOM ->
					fadeIn(tween(durationMillis = 1_000)) togetherWith fadeOut(snap(delayMillis = 1_000))
				ScreensaverTransition.FADE_THROUGH_BLACK ->
					fadeIn(tween(durationMillis = 500, delayMillis = 500)) togetherWith fadeOut(tween(durationMillis = 500))
				ScreensaverTransition.WIPE ->
					expandHorizontally(tween(durationMillis = 1_000, easing = LinearEasing), expandFrom = Alignment.Start) togetherWith
						fadeOut(snap(delayMillis = 1_000))
				ScreensaverTransition.SLIDE ->
					slideInHorizontally(tween(durationMillis = 1_000)) { width -> width } togetherWith
						slideOutHorizontally(tween(durationMillis = 1_000)) { width -> -width }
			}
		},
		label = "DreamContentTransition"
	) { content ->
		when (content) {
			DreamContent.Logo -> if (bouncingLogo) DreamBouncingLogo() else DreamContentLogo()
			is DreamContent.LibraryShowcase -> DreamContentLibraryShowcase(content, motion)
			is DreamContent.NowPlaying -> DreamContentNowPlaying(content)
		}
	}

	val drawWeatherEffects = showWeatherEffects && content !is DreamContent.NowPlaying
	val weather = if ((showClock && showWeather) || drawWeatherEffects) {
		key(weatherLocation) {
			produceState<DreamWeatherReading?>(null) {
				while (isActive) {
					value = DreamWeather.current(weatherLocation)
					delay(5.minutes)
				}
			}.value
		}
	} else null
	if (content !is DreamContent.NowPlaying) {
		val condition = weather?.condition?.takeIf { drawWeatherEffects }
		if (showSnow || condition == DreamWeatherCondition.SNOW) DreamSnow()
		if (condition != null) DreamWeatherOverlay(condition)
	}

	// Header overlay
	DreamHeader(
		showClock = showClock,
		weather = weather.takeIf { showClock && showWeather },
		units = weatherUnits,
	)

	DreamPausedNowPlayingBadge(
		content = pausedContent,
	)
}
