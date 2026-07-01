package org.jellyfin.androidtv.ui.player.video

import android.view.KeyEvent
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.ChapterItemInfo
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.getTrickplayImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val ChapterListVerticalOffset = 8.dp
private val ChapterThumbnailWidth = 132.dp
private val ChapterThumbnailHeight = 74.dp
private val ChapterListHeight = 90.dp
private val ChapterThumbnailShape = RoundedCornerShape(4.dp)
private const val ChapterThumbnailAspectRatio = 16f / 9f

@Composable
internal fun ChapterListPopover(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	chapters: List<ChapterItemInfo>,
	item: BaseItemDto?,
	mediaSourceId: String?,
	trickPlayEnabled: Boolean,
	width: Dp,
	playbackManager: PlaybackManager,
) {
	if (!expanded || chapters.isEmpty()) return

	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)
	val currentChapterIndex = remember(chapters, positionInfo.active) {
		getCurrentChapterIndex(chapters, positionInfo.active)
	}
	val listState = rememberLazyListState()

	LaunchedEffect(expanded, chapters) {
		val targetIndex = currentChapterIndex.coerceAtLeast(0)
		listState.scrollToItem(targetIndex)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		modifier = Modifier.onPreviewKeyEvent { event ->
			val nativeEvent = event.nativeKeyEvent
			if (
				nativeEvent.action == KeyEvent.ACTION_DOWN &&
				nativeEvent.repeatCount == 0 &&
				nativeEvent.isChapterListDismissKey()
			) {
				onDismissRequest()
				true
			} else {
				false
			}
		},
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, -ChapterListVerticalOffset),
	) {
		LazyRow(
			state = listState,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			contentPadding = PaddingValues(6.dp),
			modifier = Modifier
				.width(width)
				.height(ChapterListHeight),
		) {
			itemsIndexed(
				items = chapters,
				key = { _, chapter -> chapter.startPositionTicks },
			) { index, chapter ->
				ChapterListItem(
					chapter = chapter,
					item = item,
					mediaSourceId = mediaSourceId,
					trickPlayEnabled = trickPlayEnabled,
					index = index,
					isSelected = index == currentChapterIndex,
					onClick = {
						playbackManager.state.seek(chapter.startPositionTicks.ticks)
						onDismissRequest()
					},
				)
			}
		}
	}
}

private fun KeyEvent.isChapterListDismissKey() = when (keyCode) {
	KeyEvent.KEYCODE_BACK,
	KeyEvent.KEYCODE_BUTTON_B,
	KeyEvent.KEYCODE_DPAD_DOWN,
	KeyEvent.KEYCODE_ESCAPE -> true

	else -> false
}

internal fun getCurrentChapterIndex(
	chapters: List<ChapterItemInfo>,
	position: Duration,
): Int {
	if (chapters.isEmpty()) return -1

	chapters.forEachIndexed { index, chapter ->
		if (chapter.startPositionTicks.ticks > position) return index - 1
	}

	return chapters.lastIndex
}

@Composable
private fun ChapterListItem(
	chapter: ChapterItemInfo,
	item: BaseItemDto?,
	mediaSourceId: String?,
	trickPlayEnabled: Boolean,
	index: Int,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	Button(
		onClick = onClick,
		shape = ChapterThumbnailShape,
		contentPadding = PaddingValues(2.dp),
	) {
		Box(
			modifier = Modifier
				.width(ChapterThumbnailWidth)
				.aspectRatio(ChapterThumbnailAspectRatio)
				.clip(ChapterThumbnailShape),
		) {
			ChapterThumbnail(
				chapter = chapter,
				item = item,
				mediaSourceId = mediaSourceId,
				trickPlayEnabled = trickPlayEnabled,
				modifier = Modifier.matchParentSize(),
			)

			Box(
				modifier = Modifier
					.matchParentSize()
					.background(
						Brush.verticalGradient(
							0f to Color.Black.copy(alpha = 0.72f),
							0.38f to Color.Transparent,
							0.62f to Color.Transparent,
							1f to Color.Black.copy(alpha = 0.78f),
						)
					)
			)

			if (isSelected) {
				Box(
					modifier = Modifier
						.matchParentSize()
						.border(2.dp, JellyfinTheme.colorScheme.buttonFocused, ChapterThumbnailShape),
				)
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_check),
					contentDescription = null,
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(5.dp)
						.size(16.dp),
				)
			}

			Text(
				text = chapter.name?.takeIf { it.isNotBlank() }
					?: stringResource(R.string.lbl_chapter_number, index + 1),
				style = JellyfinTheme.typography.listCaption.copy(
					color = Color.White,
					fontSize = 11.sp,
				),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(
						start = 6.dp,
						top = 5.dp,
						end = if (isSelected) 26.dp else 6.dp,
					)
					.fillMaxWidth(),
			)

			Text(
				text = TimeUtils.formatMillis(chapter.startPositionTicks.ticks.inWholeMilliseconds),
				style = JellyfinTheme.typography.listCaption.copy(
					color = Color.White,
					fontSize = 11.sp,
				),
				maxLines = 1,
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(start = 6.dp, end = 6.dp, bottom = 5.dp),
			)
		}
	}
}

@Composable
private fun ChapterThumbnail(
	chapter: ChapterItemInfo,
	item: BaseItemDto?,
	mediaSourceId: String?,
	trickPlayEnabled: Boolean,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
) {
	Box(
		modifier = modifier
			.background(Color.Black.copy(alpha = 0.35f)),
	) {
		val image = chapter.image
		if (image != null) {
			val density = LocalDensity.current
			AsyncImage(
				url = image.getUrl(
					api = api,
					fillWidth = with(density) { ChapterThumbnailWidth.roundToPx() },
					fillHeight = with(density) { ChapterThumbnailHeight.roundToPx() },
				),
				blurHash = image.blurHash,
				aspectRatio = ChapterThumbnailAspectRatio,
				scaleType = ImageView.ScaleType.CENTER_CROP,
				modifier = Modifier.fillMaxSize(),
			)
		} else if (trickPlayEnabled && item != null) {
			val timeMs = chapter.startPositionTicks.ticks.inWholeMilliseconds
			val trickplayImage = remember(item.id, item.trickplay, mediaSourceId, timeMs, api.accessToken) {
				item.getTrickplayImage(api, mediaSourceId, timeMs)
			}
			if (trickplayImage != null) {
				VideoPlayerTrickplayImage(
					request = rememberVideoPlayerTrickplayImageRequest(trickplayImage),
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}
}
