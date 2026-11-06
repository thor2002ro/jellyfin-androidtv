package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.PlayerHeaderLayout
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.player.base.PlayerHeader
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.sdk.getHighHeaderTitle
import org.jellyfin.androidtv.util.sdk.getLowHeaderTitle
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject

@Composable
@Stable
fun VideoPlayerHeader(
	item: BaseItemDto?,
	liveTvProgramName: String? = null,
	liveTvNextProgram: LiveTvProgramDetails? = null,
) {
	val imageHelper = koinInject<ImageHelper>()
	val userPreferences = koinInject<UserPreferences>()
	val playerHeaderLayout = userPreferences[UserPreferences.playerHeaderLayout]

	PlayerHeader {
		if (item != null) {
			val context = LocalContext.current
			val timeFormatter = remember(context) { context.getTimeFormatter() }
			val highHeaderTitle = item.getHighHeaderTitle(context)
			val lowHeaderTitle = item.getLowHeaderTitle(context, liveTvProgramName).orEmpty()
			// Text-only mode and unavailable logos share the normal title fallback.
			val logo = if (playerHeaderLayout == PlayerHeaderLayout.TEXT_ONLY) {
				null
			} else {
				imageHelper.getLogoImage(item)
			}
			val logoBesideDetails = logo != null && playerHeaderLayout == PlayerHeaderLayout.LOGO_BESIDE_DETAILS

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (logoBesideDetails) PlayerHeaderLogo(logo, highHeaderTitle)

				Column {
					if (logo != null && !logoBesideDetails) PlayerHeaderLogo(logo, highHeaderTitle)

					if (logo == null) {
						Text(
							text = highHeaderTitle,
							overflow = TextOverflow.Ellipsis,
							maxLines = 1,
							style = LocalTextStyle.current.copy(
								color = Color.White,
								fontSize = 22.sp
							)
						)
					}

					Text(
						text = lowHeaderTitle,
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
	}
}

@Composable
private fun PlayerHeaderLogo(logo: JellyfinImage, title: String) {
	// Keep the header compact and let the server resize the image instead of downloading full artwork.
	AsyncImage(
		image = logo,
		aspectRatio = logo.aspectRatio ?: 1f,
		maxWidth = 440,
		modifier = Modifier
			.height(60.dp)
			.widthIn(max = 440.dp)
			.semantics { contentDescription = title },
	)
}
