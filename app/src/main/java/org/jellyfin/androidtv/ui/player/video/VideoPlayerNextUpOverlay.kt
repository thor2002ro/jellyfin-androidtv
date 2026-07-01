package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.androidtv.util.sdk.isLiveTv
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val NextUpMinimumItemDuration = 10.minutes
private val NextUpLongItemDuration = 75.minutes
private val NextUpLongItemLeadTime = 3.minutes
private val NextUpShortItemLeadTime = 30.seconds
internal val NextUpProgressCheckInterval = 3.seconds

@Composable
fun VideoPlayerNextUpOverlay(
	nextItem: BaseItemDto?,
	nextUpBehavior: NextUpBehavior,
	visible: Boolean,
	controlsVisible: Boolean,
	seekOverlayVisible: Boolean,
	modifier: Modifier = Modifier,
) {
	val api = koinInject<ApiClient>()
	val showThumbnail = nextUpBehavior == NextUpBehavior.EXTENDED

	AnimatedVisibility(
		visible = visible,
		enter = fadeIn(),
		exit = fadeOut(),
		modifier = modifier,
	) {
		val context = LocalContext.current
		val thumbnail = nextItem
			?.itemImages
			?.get(ImageType.PRIMARY)
			?.takeIf { showThumbnail }
		val title = nextItem?.getDisplayName(context).orEmpty()
		val bottomAlpha = when {
			controlsVisible -> 0.78f
			seekOverlayVisible -> 0.68f
			else -> 0.58f
		}
		val shape = RoundedCornerShape(8.dp)

		Row(
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier
				.widthIn(max = 390.dp)
				.clip(shape)
				.background(colorResource(R.color.popup_menu_background).copy(alpha = bottomAlpha))
				.border(1.dp, Color.White.copy(alpha = 0.08f), shape)
				.padding(horizontal = 10.dp, vertical = 9.dp),
		) {
			if (thumbnail != null) {
				AsyncImage(
					url = thumbnail.getUrl(api, fillHeight = 64),
					blurHash = thumbnail.blurHash,
					aspectRatio = thumbnail.aspectRatio ?: 2f / 3f,
					modifier = Modifier
						.height(64.dp)
						.widthIn(max = 96.dp)
						.aspectRatio(thumbnail.aspectRatio ?: 2f / 3f)
						.clip(JellyfinTheme.shapes.extraSmall),
				)
			}

			Column(
				verticalArrangement = Arrangement.Center,
				modifier = Modifier.widthIn(max = if (thumbnail != null) 280.dp else 340.dp),
			) {
				Text(
					text = stringResource(R.string.lbl_next_up),
					color = Color.White.copy(alpha = 0.58f),
					fontSize = 12.sp,
					maxLines = 1,
				)

				Spacer(Modifier.height(2.dp))

				Text(
					text = title,
					color = colorResource(R.color.button_default_normal_text),
					fontSize = 16.sp,
					maxLines = 3,
					overflow = TextOverflow.Ellipsis,
				)

				Spacer(Modifier.height(7.dp))

				Row(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Box(
						contentAlignment = Alignment.Center,
						modifier = Modifier
							.clip(RoundedCornerShape(4.dp))
							.background(Color.White.copy(alpha = 0.10f))
							.padding(horizontal = 5.dp, vertical = 2.dp),
					) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_control_select),
							contentDescription = null,
							tint = Color.White.copy(alpha = 0.86f),
							modifier = Modifier.size(15.dp),
						)
					}

					Text(
						text = stringResource(R.string.lbl_start_now),
						color = Color.White.copy(alpha = 0.82f),
						fontSize = 13.sp,
						maxLines = 1,
					)
				}
			}
		}
	}
}

internal fun BaseItemDto?.isNextUpPromptVisible(
	nextItem: BaseItemDto?,
	nextUpBehavior: NextUpBehavior,
	positionInfo: PositionInfo,
): Boolean {
	val currentItem = this ?: return false
	val threshold = positionInfo.duration.nextUpThreshold() ?: return false

	return nextItem != null &&
		nextUpBehavior != NextUpBehavior.DISABLED &&
		!currentItem.isLiveTv() &&
		positionInfo.active >= threshold
}

private fun Duration.nextUpThreshold(): Duration? {
	if (this <= NextUpMinimumItemDuration) return null

	val leadTime = if (this > NextUpLongItemDuration) {
		NextUpLongItemLeadTime
	} else {
		NextUpShortItemLeadTime
	}

	return this - leadTime
}
