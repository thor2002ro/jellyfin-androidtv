package org.jellyfin.androidtv.ui.playback.overlay

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.ProgressButton
import org.jellyfin.androidtv.ui.playback.segment.rememberSkipPromptProgress
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SkipOverlayComposable(
	visible: Boolean,
	playerUiVisible: Boolean,
	onClick: () -> Unit,
	progress: Float = 0f,
) {
	val bottomPadding = if (playerUiVisible) 220.dp else 48.dp
	val shape = RoundedCornerShape(6.dp)
	val selected = visible && !playerUiVisible

	Box(
		contentAlignment = Alignment.BottomEnd,
		modifier = Modifier
			.padding(start = 48.dp, top = 48.dp, end = 48.dp, bottom = bottomPadding)
	) {
		AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
			ProgressButton(
				progress = progress,
				onClick = onClick,
				enabled = visible,
				shape = shape,
				colors = ButtonDefaults.colors(
					containerColor = colorResource(R.color.popup_menu_background).copy(alpha = 0.6f),
				),
				contentPadding = PaddingValues(10.dp),
				modifier = if (selected) Modifier.border(2.dp, colorResource(R.color.button_default_normal_text), shape)
				else Modifier,
			) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_control_select),
					contentDescription = null,
				)
				Spacer(Modifier.width(8.dp))

				Text(
					text = stringResource(R.string.segment_action_skip),
					color = colorResource(R.color.button_default_normal_text),
					fontSize = 18.sp,
				)
			}
		}
	}
}

class SkipOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyle: Int = 0
) : AbstractComposeView(context, attrs, defStyle) {
	private val _currentPosition = MutableStateFlow(Duration.ZERO)
	private val _targetPosition = MutableStateFlow<Duration?>(null)
	private val _skipUiEnabled = MutableStateFlow(true)
	private val _playerUiVisible = MutableStateFlow(false)
	private var onSkipClick: (() -> Unit)? = null
	private var progress = 0f

	var currentPosition: Duration
		get() = _currentPosition.value
		set(value) {
			_currentPosition.value = value
		}

	var currentPositionMs: Long
		get() = _currentPosition.value.inWholeMilliseconds
		set(value) {
			_currentPosition.value = value.milliseconds
		}

	var targetPosition: Duration?
		get() = _targetPosition.value
		set(value) {
			if (_targetPosition.value != value) progress = 0f
			_targetPosition.value = value
		}

	var targetPositionMs: Long?
		get() = _targetPosition.value?.inWholeMilliseconds
		set(value) {
			targetPosition = value?.milliseconds
		}

	var skipUiEnabled: Boolean
		get() = _skipUiEnabled.value
		set(value) {
			_skipUiEnabled.value = value
		}

	var playerUiVisible: Boolean
		get() = _playerUiVisible.value
		set(value) {
			_playerUiVisible.value = value
		}

	fun setOnSkipClickListener(listener: Runnable?) {
		onSkipClick = { listener?.run() }
	}

	val visible: Boolean
		get() {
			val enabled = _skipUiEnabled.value
			val targetPosition = _targetPosition.value
			val currentPosition = _currentPosition.value

			return enabled && targetPosition != null && currentPosition < targetPosition && progress < 1f
		}

	@Composable
	override fun Content() {
		val skipUiEnabled by _skipUiEnabled.collectAsState()
		val currentPosition by _currentPosition.collectAsState()
		val targetPosition by _targetPosition.collectAsState()
		val playerUiVisible by _playerUiVisible.collectAsState()
		val userPreferences = koinInject<UserPreferences>()
		val promptActive = targetPosition?.let { currentPosition < it } == true
		val countdown = rememberSkipPromptProgress(
			promptKey = targetPosition.takeIf { promptActive },
			timeoutSeconds = userPreferences[UserPreferences.skipPromptTimeout],
		)

		SideEffect {
			progress = countdown
			if (countdown >= 1f) {
				isClickable = false
				isFocusable = false
				isFocusableInTouchMode = false
				clearFocus()
			}
		}

		SkipOverlayComposable(
			visible = skipUiEnabled && promptActive && countdown < 1f,
			playerUiVisible = playerUiVisible,
			onClick = { onSkipClick?.invoke() },
			progress = countdown,
		)
	}
}
