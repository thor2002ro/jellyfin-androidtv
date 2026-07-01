package org.jellyfin.androidtv.ui.base

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuPositionProvider

private const val TooltipDelayMillis = 650L

@Composable
fun Tooltip(
	text: String?,
	interactionSource: MutableInteractionSource,
	enabled: Boolean = true,
	content: @Composable BoxScope.() -> Unit,
) {
	val focused by interactionSource.collectIsFocusedAsState()
	val hovered by interactionSource.collectIsHoveredAsState()
	val pressed by interactionSource.collectIsPressedAsState()
	val active = enabled && !text.isNullOrBlank() && (focused || hovered)
	var visible by remember { mutableStateOf(false) }
	var suppressed by remember { mutableStateOf(false) }

	LaunchedEffect(active, pressed, suppressed, text) {
		when {
			pressed -> {
				suppressed = true
				visible = false
			}

			!active -> {
				suppressed = false
				visible = false
			}

			suppressed -> {
				visible = false
			}

			else -> {
				delay(TooltipDelayMillis)
				visible = true
			}
		}
	}

	Box {
		content()

		if (visible && !text.isNullOrBlank()) {
			val density = LocalDensity.current
			val popupPositionProvider = remember(density) {
				PopoverMenuPositionProvider(
					alignment = Alignment.TopCenter,
					offset = IntOffset(
						x = 0,
						y = with(density) { (-8).dp.roundToPx() },
					)
				)
			}

			Popup(
				properties = PopupProperties(
					focusable = false,
					dismissOnBackPress = false,
					dismissOnClickOutside = false,
				),
				popupPositionProvider = popupPositionProvider,
			) {
				Box(
					modifier = Modifier
						.graphicsLayer(
							shape = TooltipShape,
							clip = true,
							shadowElevation = with(density) { 4.dp.toPx() },
							ambientShadowColor = Color.Black,
							spotShadowColor = Color.Black,
						)
						.background(JellyfinTheme.colorScheme.surface, TooltipShape)
						.wrapContentSize()
						.padding(horizontal = 12.dp, vertical = 8.dp)
				) {
					Text(
						text = text,
						style = JellyfinTheme.typography.default.copy(
							color = Color.White
						),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

private val TooltipShape = RoundedCornerShape(4.dp)
