package org.jellyfin.androidtv.ui.playback.segment

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.composable.FixedMotionDurationScale

@Composable
internal fun rememberSkipPromptProgress(promptKey: Any?, timeoutSeconds: Int): Float {
	val progress = remember(promptKey, timeoutSeconds) { Animatable(0f) }

	LaunchedEffect(promptKey, timeoutSeconds) {
		if (promptKey == null || timeoutSeconds <= 0) return@LaunchedEffect
		withContext(FixedMotionDurationScale) {
			progress.animateTo(
				targetValue = 1f,
				animationSpec = tween(
					durationMillis = timeoutSeconds * 1000,
					easing = LinearEasing,
				),
			)
		}
	}

	return progress.value
}
