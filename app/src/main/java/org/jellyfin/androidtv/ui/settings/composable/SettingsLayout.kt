package org.jellyfin.androidtv.ui.settings.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalShapes
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.design.Tokens

@Composable
fun SettingsLayout(
	modifier: Modifier = Modifier,
	content: @Composable BoxScope.() -> Unit,
) {
	val context = LocalContext.current
	val surfaceColor = remember(context.theme) {
		val attributes = context.theme.obtainStyledAttributes(intArrayOf(R.attr.popupMenuBackground))
		val color = if (attributes.hasValue(0)) Color(attributes.getColor(0, 0)) else null
		attributes.recycle()
		color
	} ?: JellyfinTheme.colorScheme.surface

	Box(
		modifier = modifier
			.padding(Tokens.Space.spaceMd)
			.clip(LocalShapes.current.large)
			.background(surfaceColor)
			.width(350.dp)
			.fillMaxHeight(),
		content = {
			ProvideTextStyle(
				JellyfinTheme.typography.default.copy(color = JellyfinTheme.colorScheme.onBackground)
			) {
				content()
			}
		}
	)
}
