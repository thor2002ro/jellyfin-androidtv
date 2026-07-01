package org.jellyfin.androidtv.ui.player.base

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun PlayerOverlayLayout(
	modifier: Modifier = Modifier,
	visibilityState: PlayerOverlayVisibilityState = rememberPlayerOverlayVisibility(),
	header: (@Composable () -> Unit)? = null,
	controls: (@Composable () -> Unit)? = null,
	onCenterClick: (() -> Boolean)? = null,
	onCenterLongClick: (() -> Boolean)? = null,
) {
	val focusRequester = remember { FocusRequester() }
	val controlsFocusRequester = remember { FocusRequester() }
	val scope = rememberCoroutineScope()
	val windowInfo = LocalWindowInfo.current
	var controlsHaveFocus by remember { mutableStateOf(false) }
	var centerShortcutArmed by remember { mutableStateOf(false) }
	var centerLongPressHandled by remember { mutableStateOf(false) }
	var centerLongPressJob by remember { mutableStateOf<Job?>(null) }

	fun clearCenterShortcut() {
		centerLongPressJob?.cancel()
		centerLongPressJob = null
		centerShortcutArmed = false
		centerLongPressHandled = false
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.focusRequester(focusRequester)
			.focusable()
			.onPreviewKeyEvent {
				val nativeEvent = it.nativeKeyEvent
				if (nativeEvent.isCenterKey()) {
					when (nativeEvent.action) {
						AndroidKeyEvent.ACTION_DOWN -> {
							if (centerShortcutArmed) return@onPreviewKeyEvent true
							if (visibilityState.visible) return@onPreviewKeyEvent false

							centerShortcutArmed = true
							centerLongPressHandled = false
							centerLongPressJob?.cancel()
							centerLongPressJob = scope.launch {
								delay(ViewConfiguration.getLongPressTimeout().toLong())
								if (centerShortcutArmed) {
									centerLongPressHandled = true
									onCenterLongClick?.invoke()
								}
							}
							return@onPreviewKeyEvent true
						}

						AndroidKeyEvent.ACTION_UP -> {
							if (!centerShortcutArmed) return@onPreviewKeyEvent false

							centerLongPressJob?.cancel()
							centerLongPressJob = null
							val handled = if (centerLongPressHandled || nativeEvent.flags and AndroidKeyEvent.FLAG_CANCELED_LONG_PRESS != 0) {
								true
							} else {
								onCenterClick?.invoke() == true
							}
							val shouldShowOverlay = handled && !centerLongPressHandled
							clearCenterShortcut()
							if (shouldShowOverlay) visibilityState.show()
							return@onPreviewKeyEvent true
						}
					}
				}

				if (nativeEvent.shouldShowHiddenOverlay(visibilityState.visible)) {
					visibilityState.show()
					return@onPreviewKeyEvent true
				}

				if (visibilityState.visible) visibilityState.show()
				false
			}
			.onKeyEvent {
				if (it.key == Key.Back && visibilityState.visible) {
					visibilityState.hide()
					true
				} else if (it.nativeKeyEvent.shouldShowHiddenOverlay(visibilityState.visible)) {
					visibilityState.show()
					true
				} else {
					false
				}
			}
	) {
		LaunchedEffect(visibilityState.visible, windowInfo.isWindowFocused, controls != null, controlsHaveFocus) {
			when {
				visibilityState.visible && windowInfo.isWindowFocused && controls != null && !controlsHaveFocus ->
					controlsFocusRequester.requestFocus()

				!visibilityState.visible -> focusRequester.requestFocus()
			}
		}

	if (header != null) {
		AnimatedVisibility(
			visible = visibilityState.visible,
			modifier = Modifier
				.align(Alignment.TopCenter),
			enter = slideInVertically() + fadeIn(),
			exit = slideOutVertically() + fadeOut(),
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.fillMaxHeight(1f / 3)
					.background(
						brush = Brush.verticalGradient(
							colors = listOf(
								Color.Black.copy(alpha = 0.8f),
								Color.Transparent,
							)
						)
					)
					.overscan()
			) {
				header()
			}
		}
	}

	if (controls != null) {
		AnimatedVisibility(
			visible = visibilityState.visible,
			modifier = Modifier
				.align(Alignment.BottomCenter),
			enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
			exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
		) {
			Box(
				contentAlignment = Alignment.BottomCenter,
				modifier = Modifier
					.fillMaxWidth()
					.fillMaxHeight(1f / 3)
					.focusRequester(controlsFocusRequester)
					.focusRestorer()
					.focusGroup()
					.onFocusChanged { controlsHaveFocus = it.hasFocus }
					.background(
						brush = Brush.verticalGradient(
							colors = listOf(
								Color.Transparent,
								Color.Black.copy(alpha = 0.8f),
							)
						)
					)
					.overscan(),
			) {
				JellyfinTheme(
					colorScheme = JellyfinTheme.colorScheme.copy(
						button = Color.Transparent
					)
				) {
					controls()
				}
			}
		}
	}
}
}

data class PlayerOverlayVisibilityState(
	val visible: Boolean,

	val toggle: () -> Unit,
	val show: () -> Unit,
	val hide: () -> Unit
)

@Composable
fun rememberPlayerOverlayVisibility(
	timeout: Duration = 5.seconds,
	keepVisible: Boolean = false,
): PlayerOverlayVisibilityState {
	val scope = rememberCoroutineScope()
	var timerVisible by remember { mutableStateOf(false) }
	var timerJob by remember { mutableStateOf<Job?>(null) }
	var visible = timerVisible || keepVisible

	fun show() {
		timerVisible = true
		timerJob?.cancel()
		timerJob = null

		if (!keepVisible) {
			timerJob = scope.launch {
				delay(timeout)
				timerVisible = false
			}
		}
	}

	fun hide() {
		timerVisible = false
		timerJob?.cancel()
		timerJob = null
	}

	fun toggle() {
		if (timerVisible || keepVisible) hide()
		else show()
	}

	LaunchedEffect(keepVisible) {
		if (keepVisible) {
			timerVisible = true
			timerJob?.cancel()
			timerJob = null
		} else if (timerVisible) {
			show()
		}
	}

	// Force visibility when not the active window, reset timer when it changes
	// to make sure popups keep the overlay visible
	val windowInfo = LocalWindowInfo.current
	visible = visible || !windowInfo.isWindowFocused

	var previousIsWindowFocused by remember { mutableStateOf(windowInfo.isWindowFocused) }
	LaunchedEffect(windowInfo.isWindowFocused) {
		if (windowInfo.isWindowFocused != previousIsWindowFocused) show()
		previousIsWindowFocused = windowInfo.isWindowFocused
	}

	return PlayerOverlayVisibilityState(
		visible = visible,
		toggle = ::toggle,
		show = ::show,
		hide = ::hide
	)
}

private fun AndroidKeyEvent.isCenterKey() = when (keyCode) {
	AndroidKeyEvent.KEYCODE_DPAD_CENTER,
	AndroidKeyEvent.KEYCODE_ENTER,
	AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
	AndroidKeyEvent.KEYCODE_BUTTON_A -> true

	else -> false
}

private fun AndroidKeyEvent.shouldShowHiddenOverlay(visible: Boolean) =
	action == AndroidKeyEvent.ACTION_DOWN && !isSystem && !isSeekKey() && !visible

private fun AndroidKeyEvent.isSeekKey() = when (keyCode) {
	AndroidKeyEvent.KEYCODE_DPAD_LEFT,
	AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true

	else -> false
}
