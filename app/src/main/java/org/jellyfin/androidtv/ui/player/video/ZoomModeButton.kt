package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.button.IconButton

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
	PlayerSelectionPopover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		title = stringResource(R.string.lbl_zoom),
	) {
		ZoomMode.entries.forEach { mode ->
			PlayerSelectionItem(
				label = stringResource(mode.nameRes),
				isSelected = mode == currentZoomMode,
				onClick = { onZoomModeSelected(mode) },
			)
		}
	}
}
