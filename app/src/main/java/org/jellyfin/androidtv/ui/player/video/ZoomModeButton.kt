package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.popover.Popover

private val ZoomModePopoverVerticalOffset = 5.dp

@Composable
fun ZoomModeButton(
	zoomMode: ZoomMode,
	onZoomModeSelected: (ZoomMode) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }

	Box {
		val tooltip = stringResource(R.string.lbl_zoom)
		IconButton(
			onClick = { expanded = true },
			tooltip = tooltip,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_aspect_ratio),
				contentDescription = tooltip,
			)
		}

		ZoomModePopover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			currentZoomMode = zoomMode,
			onZoomModeSelected = { selectedZoomMode ->
				onZoomModeSelected(selectedZoomMode)
				expanded = false
			},
		)
	}
}

@Composable
private fun ZoomModePopover(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	currentZoomMode: ZoomMode,
	onZoomModeSelected: (ZoomMode) -> Unit,
) {
	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, -ZoomModePopoverVerticalOffset),
	) {
		Column(
			modifier = Modifier
				.padding(horizontal = 6.dp, vertical = 6.dp)
				.widthIn(min = 160.dp, max = 240.dp)
				.heightIn(max = 300.dp)
				.verticalScroll(rememberScrollState())
		) {
			Text(
				text = stringResource(R.string.lbl_zoom),
				style = JellyfinTheme.typography.listHeader.copy(
					color = JellyfinTheme.colorScheme.listHeader
				),
				fontSize = 13.sp,
				modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			)

			ZoomMode.entries.forEach { mode ->
				ZoomModeItem(
					label = stringResource(mode.nameRes),
					isSelected = mode == currentZoomMode,
					onClick = { onZoomModeSelected(mode) },
				)
			}
		}
	}
}

@Composable
private fun ZoomModeItem(
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
