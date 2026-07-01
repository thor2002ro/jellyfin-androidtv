package org.jellyfin.androidtv.ui.player.photo

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.koin.androidx.compose.koinViewModel

@Composable
fun PhotoPlayerControls() {
	val viewModel = koinViewModel<PhotoPlayerViewModel>()
	val presentationActive by viewModel.presentationActive.collectAsState()

	Row(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		modifier = Modifier
			.focusRestorer()
			.focusGroup()
	) {
		PreviousButton(
			onClick = { viewModel.showPrevious() },
		)

		PlayPauseButton(
			presentationActive = presentationActive,
			onSetPresentationActive = { presentationActive ->
				if (presentationActive) viewModel.startPresentation()
				else viewModel.stopPresentation()
			}
		)

		NextButton(
			onClick = { viewModel.showNext() },
		)
	}
}

@Composable
private fun PreviousButton(
	onClick: () -> Unit,
) {
	val tooltip = stringResource(R.string.lbl_prev_item)
	IconButton(
		onClick = onClick,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_previous),
			contentDescription = tooltip,
		)
	}
}

@Composable
private fun NextButton(
	onClick: () -> Unit,
) {
	val tooltip = stringResource(R.string.lbl_next_item)
	IconButton(
		onClick = onClick,
		tooltip = tooltip,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_next),
			contentDescription = tooltip,
		)
	}
}

@Composable
private fun PlayPauseButton(
	presentationActive: Boolean,
	onSetPresentationActive: (presentationActive: Boolean) -> Unit,
) {
	val focusRequester = remember { FocusRequester() }
	val tooltip = stringResource(if (presentationActive) R.string.lbl_pause else R.string.lbl_play)
	IconButton(
		onClick = {
			onSetPresentationActive(!presentationActive)
		},
		modifier = Modifier
			.focusRequester(focusRequester)
			.onVisibilityChanged {
				focusRequester.requestFocus()
			},
		tooltip = tooltip,
	) {
		AnimatedContent(presentationActive) { presentationActive ->
			if (presentationActive) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_pause),
					contentDescription = tooltip,
				)
			} else {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_play),
					contentDescription = tooltip,
				)
			}
		}
	}
}
