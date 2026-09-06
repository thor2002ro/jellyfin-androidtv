package org.jellyfin.androidtv.ui.base.list

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role

val LocalListButtonFocusHandler = compositionLocalOf<((Long, FocusRequester, Boolean) -> Unit)?> { null }

@Composable
fun ListButton(
	onClick: () -> Unit,
	headingContent: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	interactionSource: MutableInteractionSource? = null,
	colors: ListControlColors = ListControlDefaults.colors(),
	overlineContent: (@Composable () -> Unit)? = null,
	captionContent: (@Composable () -> Unit)? = null,
	leadingContent: (@Composable () -> Unit)? = null,
	trailingContent: (@Composable () -> Unit)? = null,
	footerContent: (@Composable () -> Unit)? = null,
) {
	@Suppress("NAME_SHADOWING")
	val interactionSource = interactionSource ?: remember { MutableInteractionSource() }
	val focusKey = currentCompositeKeyHashCode
	val focusRequester = remember { FocusRequester() }
	val focused by interactionSource.collectIsFocusedAsState()
	val focusHandler = LocalListButtonFocusHandler.current

	LaunchedEffect(focusKey, focusRequester, focused, focusHandler) {
		focusHandler?.invoke(focusKey, focusRequester, focused)
	}

	ListControl(
		headingContent = headingContent,
		modifier = modifier
			.focusRequester(focusRequester)
			.combinedClickable(
				interactionSource = interactionSource,
				indication = null,
				enabled = enabled,
				role = Role.Button,
				onClick = onClick,
			),
		enabled = enabled,
		interactionSource = interactionSource,
		colors = colors,
		overlineContent = overlineContent,
		captionContent = captionContent,
		leadingContent = leadingContent,
		trailingContent = trailingContent,
		footerContent = footerContent,
	)
}
