package org.jellyfin.androidtv.ui.settings.composable

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jellyfin.androidtv.ui.base.dialog.DialogBase

@Composable
fun SettingsDialog(
	visible: Boolean,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	screen: @Composable BoxScope.() -> Unit,
) {
	DialogBase(
		visible = visible,
		onDismissRequest = onDismissRequest,
		modifier = modifier,
		contentAlignment = Alignment.TopEnd,
		enterTransition = slideInHorizontally(
			initialOffsetX = { it },
			animationSpec = spring(
				dampingRatio = Spring.DampingRatioNoBouncy,
				stiffness = Spring.StiffnessMediumLow,
			)
		) + fadeIn(
			animationSpec = tween(240)
		),
		exitTransition = slideOutHorizontally(
			targetOffsetX = { it },
			animationSpec = tween(250, easing = FastOutSlowInEasing)
		) + fadeOut(
			animationSpec = tween(200)
		),
	) {
		SettingsLayout {
			screen()
		}
	}
}
