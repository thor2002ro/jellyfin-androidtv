package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.player.base.PlayerHeader
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.sdk.model.api.BaseItemDto

@Composable
@Stable
fun VideoPlayerHeader(
	item: BaseItemDto?,
	liveTvProgramName: String? = null,
	liveTvNextProgram: LiveTvProgramDetails? = null,
) {
	PlayerHeader {
		if (item != null) {
			val context = LocalContext.current
			val timeFormatter = remember(context) { context.getTimeFormatter() }

			Text(
				text = item.name.orEmpty(),
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
				style = LocalTextStyle.current.copy(
					color = Color.White,
					fontSize = 22.sp
				)
			)

			Text(
				text = liveTvProgramName ?: item.seriesName.orEmpty(),
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
				style = LocalTextStyle.current.copy(
					color = Color.White,
					fontSize = 18.sp
				)
			)

			if (liveTvNextProgram != null) {
				val timeRange = "${timeFormatter.format(liveTvNextProgram.start)} - ${timeFormatter.format(liveTvNextProgram.end)}"
				Text(
					text = "${stringResource(R.string.lbl_next_up)}: ${liveTvNextProgram.name}  $timeRange",
					overflow = TextOverflow.Ellipsis,
					maxLines = 1,
					style = LocalTextStyle.current.copy(
						color = Color.White.copy(alpha = 0.78f),
						fontSize = 15.sp
					)
				)
			}
		}
	}
}
