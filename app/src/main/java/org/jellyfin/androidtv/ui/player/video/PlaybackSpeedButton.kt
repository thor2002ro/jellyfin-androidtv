package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.Locale
import kotlin.math.abs

private val PlaybackSpeedButtonContentHeight = 20.dp
private val PlaybackSpeedButtonContentMinWidth = 28.dp
private val PlaybackSpeedButtonTextVerticalOffset = 2.dp
private const val PlaybackSpeedSelectionTolerance = 0.001f

@Composable
fun PlaybackSpeedButton(
	playbackManager: PlaybackManager,
	item: BaseItemDto?,
) {
	val speed by playbackManager.state.speed.collectAsState()
	val isLiveTv = item?.isLiveTv() == true

	LaunchedEffect(isLiveTv, speed) {
		if (isLiveTv && !PlaybackSpeedOption.Default.matches(speed)) {
			playbackManager.state.setSpeed(PlaybackSpeedOption.Default.speed)
		}
	}

	if (item == null || isLiveTv) return

	var expanded by remember { mutableStateOf(false) }
	val currentSpeedLabel = PlaybackSpeedOption.buttonLabelFor(speed)

	Box {
		val tooltip = stringResource(R.string.lbl_playback_speed)
		IconButton(
			onClick = { expanded = true },
			tooltip = tooltip,
		) {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier
					.height(PlaybackSpeedButtonContentHeight)
					.widthIn(min = PlaybackSpeedButtonContentMinWidth),
			) {
				Text(
					text = currentSpeedLabel,
					modifier = Modifier.offset(y = PlaybackSpeedButtonTextVerticalOffset),
					style = LocalTextStyle.current.copy(
						fontSize = 12.sp,
						fontWeight = FontWeight.W700,
						textAlign = TextAlign.Center,
					),
					softWrap = false,
					maxLines = 1,
					overflow = TextOverflow.Clip,
				)
			}
		}

		PlaybackSpeedPopover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			currentSpeed = speed,
			onSpeedSelected = { selectedSpeed ->
				playbackManager.state.setSpeed(selectedSpeed)
				expanded = false
			},
		)
	}
}

@Composable
private fun PlaybackSpeedPopover(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	currentSpeed: Float,
	onSpeedSelected: (Float) -> Unit,
) {
	val popupOffsetY = dimensionResource(R.dimen.player_popup_menu_offset_y)
	val popupPaddingHorizontal = dimensionResource(R.dimen.player_popup_menu_padding_horizontal)
	val popupPaddingVertical = dimensionResource(R.dimen.player_popup_menu_padding_vertical)
	val popupMinWidth = dimensionResource(R.dimen.player_popup_menu_compact_min_width)
	val popupMaxWidth = dimensionResource(R.dimen.player_popup_menu_compact_max_width)
	val popupMaxHeight = dimensionResource(R.dimen.player_popup_menu_max_height)

	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, -popupOffsetY),
	) {
		Column(
			modifier = Modifier
				.padding(horizontal = popupPaddingHorizontal, vertical = popupPaddingVertical)
				.widthIn(min = popupMinWidth, max = popupMaxWidth)
				.heightIn(max = popupMaxHeight)
				.verticalScroll(rememberScrollState())
		) {
			Text(
				text = stringResource(R.string.lbl_playback_speed),
				style = JellyfinTheme.typography.listHeader.copy(
					color = JellyfinTheme.colorScheme.listHeader
				),
				fontSize = 13.sp,
				modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			)

			PlaybackSpeedOption.entries.forEach { option ->
				PlaybackSpeedItem(
					label = option.label,
					isSelected = option.matches(currentSpeed),
					onClick = { onSpeedSelected(option.speed) },
				)
			}
		}
	}
}

@Composable
private fun PlaybackSpeedItem(
	label: String,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	Button(
		onClick = onClick,
		contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
		) {
			Box(modifier = Modifier.size(18.dp)) {
				if (isSelected) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_check),
						contentDescription = null,
						modifier = Modifier.size(18.dp),
					)
				}
			}
			ProvideTextStyle(JellyfinTheme.typography.listHeadline.copy(fontSize = 13.sp)) {
				Text(
					text = label,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

private enum class PlaybackSpeedOption(val speed: Float) {
	SPEED_0_25(speed = 0.25f),
	SPEED_0_50(speed = 0.50f),
	SPEED_0_75(speed = 0.75f),
	SPEED_1_00(speed = 1.00f),
	SPEED_1_25(speed = 1.25f),
	SPEED_1_50(speed = 1.50f),
	SPEED_1_75(speed = 1.75f),
	SPEED_2_00(speed = 2.00f);

	val label: String
		get() = String.format(Locale.US, "%.2fx", speed)

	fun matches(currentSpeed: Float) = abs(speed - currentSpeed) < PlaybackSpeedSelectionTolerance

	companion object {
		val Default = SPEED_1_00

		fun buttonLabelFor(speed: Float): String =
			(entries.firstOrNull { it.matches(speed) }?.speed ?: speed).formatCompactSpeed()
	}
}

private fun Float.formatCompactSpeed(): String =
	String.format(Locale.US, "%.2f", this)
		.trimEnd('0')
		.trimEnd('.') + "x"
