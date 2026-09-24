package org.jellyfin.androidtv.ui.base.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalShapes

object ListControlDefaults {
	@Composable
	fun colors(
		containerColor: Color = JellyfinTheme.colorScheme.listButton,
		focusedContainerColor: Color? = null,
	): ListControlColors {
		val context = LocalContext.current
		val themeFocusColor = remember(context.theme) {
			val attributes = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
			val color = if (attributes.hasValue(0)) Color(attributes.getColor(0, 0)).copy(alpha = 0.28f) else null
			attributes.recycle()
			color
		}

		return ListControlColors(
			containerColor = containerColor,
			focusedContainerColor = focusedContainerColor
				?: themeFocusColor
				?: JellyfinTheme.colorScheme.listButtonFocused,
		)
	}
}

@Composable
fun ListControl(
	headingContent: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	interactionSource: InteractionSource? = null,
	colors: ListControlColors = ListControlDefaults.colors(),
	overlineContent: (@Composable () -> Unit)? = null,
	captionContent: (@Composable () -> Unit)? = null,
	leadingContent: (@Composable () -> Unit)? = null,
	trailingContent: (@Composable () -> Unit)? = null,
	footerContent: (@Composable () -> Unit)? = null,
) {
	@Suppress("NAME_SHADOWING")
	val interactionSource = interactionSource ?: remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()
	val pressed by interactionSource.collectIsPressedAsState()

	val targetBackgroundColor = when {
		!enabled -> colors.containerColor
		pressed -> colors.focusedContainerColor
		focused -> colors.focusedContainerColor
		else -> colors.containerColor
	}
	val backgroundColor by animateColorAsState(
		targetValue = targetBackgroundColor,
		animationSpec = tween(durationMillis = 160),
		label = "List control background",
	)
	val borderColor by animateColorAsState(
		targetValue = if (enabled && (focused || pressed)) {
			colors.focusedContainerColor.copy(alpha = 0.8f)
		} else {
			Color.Transparent
		},
		animationSpec = tween(durationMillis = 160),
		label = "List control border",
	)

	Box(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.background(backgroundColor, LocalShapes.current.medium)
			.border(1.dp, borderColor, LocalShapes.current.medium)
			.clip(LocalShapes.current.medium)
			.alpha(if (enabled) 1f else 0.4f),
	) {
		ListItemContent(
			headingContent = headingContent,
			overlineContent = overlineContent,
			captionContent = captionContent,
			leadingContent = leadingContent,
			trailingContent = trailingContent,
			footerContent = footerContent,
			headingStyle = JellyfinTheme.typography.listHeadline
				.copy(color = JellyfinTheme.colorScheme.listHeadline),
		)
	}
}
