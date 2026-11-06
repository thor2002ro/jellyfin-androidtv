package org.jellyfin.androidtv.ui.player.video

import android.view.KeyEvent
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListControlDefaults
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

private const val GuideTimeStepMinutes = 30L
private const val GuideProgramWindowBeforeMinutes = GuideTimeStepMinutes
private const val GuideProgramWindowAfterMinutes = GuideTimeStepMinutes * 6
private val ChannelIconWidth = 56.dp
private val ChannelIconHeight = 36.dp

@Composable
fun LiveTvGuideOverlay(
	playbackManager: PlaybackManager,
	currentItem: BaseItemDto,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
	liveTvChannelNavigator: LiveTvChannelNavigator = rememberLiveTvChannelNavigator(),
	onRemoteKeyEventHandlerChanged: (((KeyEvent) -> Boolean)?) -> Unit = {},
	api: ApiClient = koinInject(),
	imageHelper: ImageHelper = koinInject(),
) {
	val context = LocalContext.current
	val timeFormatter = remember(context) { context.getTimeFormatter() }
	val focusRequester = remember { FocusRequester() }
	val listState = rememberLazyListState()
	val coroutineScope = rememberCoroutineScope()
	val currentChannelId = currentItem.liveTvChannelId()
	var channels by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var selectedChannelIndex by remember(currentChannelId) { mutableStateOf(0) }
	var switchingChannel by remember { mutableStateOf(false) }
	var guideTime by remember { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0)) }
	var guideProgramsByChannel by remember { mutableStateOf<Map<UUID, List<BaseItemDto>>?>(null) }

	BackHandler(onBack = onDismiss)

	LaunchedEffect(liveTvChannelNavigator) {
		channels = liveTvChannelNavigator.getChannels().sortedByChannelName()
	}

	LaunchedEffect(focusRequester, channels) {
		if (channels != null) focusRequester.requestFocus()
	}

	LaunchedEffect(channels, currentChannelId) {
		val loadedChannels = channels.orEmpty()
		if (loadedChannels.isEmpty()) return@LaunchedEffect
		guideProgramsByChannel = loadedChannels.currentProgramsByChannel()

		selectedChannelIndex = loadedChannels
			.indexOfFirst { channel -> channel.liveTvChannelId() == currentChannelId }
			.takeIf { index -> index >= 0 }
			?: 0
		listState.scrollToItem(selectedChannelIndex)
	}

	LaunchedEffect(api, channels) {
		val loadedChannels = channels.orEmpty()
		val currentProgramsByChannel = loadedChannels.currentProgramsByChannel()
		val channelIds = loadedChannels
			.mapNotNull { channel -> channel.liveTvChannelId() }

		if (channelIds.isEmpty()) {
			guideProgramsByChannel = emptyMap()
			return@LaunchedEffect
		}

		val referenceTime = LocalDateTime.now().withSecond(0).withNano(0)
		val startTime = referenceTime.minusMinutes(GuideProgramWindowBeforeMinutes)
		val endTime = referenceTime.plusMinutes(GuideProgramWindowAfterMinutes)

		guideProgramsByChannel = runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvPrograms(
					channelIds = channelIds,
					enableImages = false,
					sortBy = setOf(ItemSortBy.START_DATE),
					sortOrder = setOf(SortOrder.ASCENDING),
					maxStartDate = endTime,
					minEndDate = startTime,
					enableTotalRecordCount = false,
				).content.items
			}
		}.onFailure { error ->
			Timber.e(error, "Unable to load Live TV guide programs")
		}.getOrDefault(emptyList())
			.groupBy { program -> program.liveTvChannelId() }
			.mapNotNull { (channelId, programs) ->
				if (channelId != null) {
					channelId to programs
				} else {
					null
				}
			}
			.toMap()
			.mergeWith(currentProgramsByChannel)
			.also { programsByChannel ->
				programsByChannel.guideTimeRange()?.let { range ->
					guideTime = guideTime.coerceIn(range.first, range.second)
				}
			}
	}

	LaunchedEffect(selectedChannelIndex, channels) {
		if (channels.isNullOrEmpty()) return@LaunchedEffect

		val visibleItems = listState.layoutInfo.visibleItemsInfo
		if (visibleItems.isEmpty()) return@LaunchedEffect

		val firstVisibleIndex = visibleItems.first().index
		val lastVisibleIndex = visibleItems.last().index
		val targetFirstIndex = when {
			selectedChannelIndex < firstVisibleIndex -> selectedChannelIndex
			selectedChannelIndex > lastVisibleIndex -> selectedChannelIndex - (lastVisibleIndex - firstVisibleIndex)
			else -> return@LaunchedEffect
		}

		listState.scrollToItem(targetFirstIndex.coerceAtLeast(0))
	}

	fun moveSelection(offset: Int) {
		val loadedChannels = channels.orEmpty()
		if (loadedChannels.isEmpty() || switchingChannel) return

		selectedChannelIndex = (selectedChannelIndex + offset).coerceIn(0, loadedChannels.lastIndex)
	}

	fun moveGuideTime(offsetMinutes: Long) {
		val guideRange = guideProgramsByChannel?.guideTimeRange() ?: return
		guideTime = guideTime
			.plusMinutes(offsetMinutes)
			.coerceIn(guideRange.first, guideRange.second)
	}

	fun selectChannel(channel: BaseItemDto) {
		if (switchingChannel) return

		if (channel.liveTvChannelId() == currentChannelId) {
			onDismiss()
			return
		}

		coroutineScope.launch {
			switchingChannel = true
			try {
				if (
					liveTvChannelNavigator.switchToChannel(
						playbackManager = playbackManager,
						currentItem = currentItem,
						targetChannel = channel,
					)
				) {
					onDismiss()
				}
			} finally {
				switchingChannel = false
			}
		}
	}

	fun selectHighlightedChannel() {
		channels
			?.getOrNull(selectedChannelIndex)
			?.let(::selectChannel)
	}

	fun handleKeyEvent(nativeEvent: KeyEvent): Boolean = when (nativeEvent.keyCode) {
		KeyEvent.KEYCODE_BACK,
		KeyEvent.KEYCODE_ESCAPE,
		KeyEvent.KEYCODE_BUTTON_B -> {
			if (nativeEvent.action == KeyEvent.ACTION_DOWN && nativeEvent.repeatCount == 0) onDismiss()
			true
		}

		KeyEvent.KEYCODE_DPAD_UP -> {
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveSelection(-1)
			true
		}

		KeyEvent.KEYCODE_DPAD_DOWN -> {
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveSelection(1)
			true
		}

		KeyEvent.KEYCODE_DPAD_LEFT -> {
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveGuideTime(-GuideTimeStepMinutes)
			true
		}

		KeyEvent.KEYCODE_DPAD_RIGHT -> {
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveGuideTime(GuideTimeStepMinutes)
			true
		}

		KeyEvent.KEYCODE_DPAD_CENTER,
		KeyEvent.KEYCODE_ENTER,
		KeyEvent.KEYCODE_NUMPAD_ENTER,
		KeyEvent.KEYCODE_BUTTON_A -> {
			if (nativeEvent.action == KeyEvent.ACTION_DOWN && nativeEvent.repeatCount == 0) {
				selectHighlightedChannel()
			}
			true
		}

		else -> false
	}

	SideEffect {
		onRemoteKeyEventHandlerChanged { keyEvent -> handleKeyEvent(keyEvent) }
	}

	DisposableEffect(onRemoteKeyEventHandlerChanged) {
		onDispose {
			onRemoteKeyEventHandlerChanged(null)
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.background(Color.Black.copy(alpha = 0.82f))
			.focusRequester(focusRequester)
			.focusable()
			.onPreviewKeyEvent { event ->
				handleKeyEvent(event.nativeKeyEvent)
			}
			.overscan(),
	) {
		Column(
			verticalArrangement = Arrangement.spacedBy(14.dp),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth()
				.fillMaxHeight(0.72f)
				.padding(horizontal = 56.dp, vertical = 36.dp),
		) {
			Text(
				text = "${stringResource(R.string.lbl_live_tv_guide)}  ${timeFormatter.format(guideTime)}",
				style = JellyfinTheme.typography.listHeader.copy(color = Color.White),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			val loadedChannels = channels
			when {
				loadedChannels == null -> Text(
					text = stringResource(R.string.loading),
					style = JellyfinTheme.typography.default.copy(color = Color.White),
				)

				loadedChannels.isEmpty() -> Text(
					text = stringResource(R.string.lbl_no_items),
					style = JellyfinTheme.typography.default.copy(color = Color.White),
				)

				else -> LazyColumn(
					state = listState,
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					itemsIndexed(
						items = loadedChannels,
						key = { _, channel -> channel.id.toString() },
					) { index, channel ->
						LiveTvGuideChannelRow(
							channel = channel,
							program = guideProgramsByChannel
								?.get(channel.liveTvChannelId())
								?.programAt(guideTime),
							loadingProgram = guideProgramsByChannel == null,
							selected = index == selectedChannelIndex,
							current = channel.liveTvChannelId() == currentChannelId,
							enabled = !switchingChannel,
							imageHelper = imageHelper,
							onClick = {
								selectedChannelIndex = index
								selectChannel(channel)
							},
						)
					}
				}
			}
		}
	}
}

@Composable
private fun LiveTvGuideChannelRow(
	channel: BaseItemDto,
	program: BaseItemDto?,
	loadingProgram: Boolean,
	selected: Boolean,
	current: Boolean,
	enabled: Boolean,
	imageHelper: ImageHelper,
	onClick: () -> Unit,
) {
	val heading = listOfNotNull(
		channel.number?.takeUnless { number -> number.isBlank() },
		channel.name,
	).joinToString("  ")

	ListButton(
		onClick = onClick,
		enabled = enabled,
		colors = ListControlDefaults.colors(
			containerColor = if (selected) {
				JellyfinTheme.colorScheme.listButtonFocused
			} else {
				JellyfinTheme.colorScheme.listButton
			},
		),
		headingContent = {
			Text(
				text = heading,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
		overlineContent = if (!current) null else {
			{
				Text(
					text = stringResource(R.string.lbl_now_playing),
					maxLines = 1,
				)
			}
		},
		leadingContent = {
			ChannelIcon(channel, imageHelper)
		},
		captionContent = {
			Text(
				text = if (loadingProgram) stringResource(R.string.loading) else program.guideCaption(),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
	)
}

@Composable
private fun ChannelIcon(
	channel: BaseItemDto,
	imageHelper: ImageHelper,
) {
	val imageUrl = remember(channel.id, channel.imageTags) {
		imageHelper.getPrimaryImageUrl(channel, height = ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
	}

	if (imageUrl != null) {
		AsyncImage(
			url = imageUrl,
			aspectRatio = 16f / 9f,
			scaleType = ImageView.ScaleType.FIT_CENTER,
			modifier = Modifier.size(ChannelIconWidth, ChannelIconHeight),
		)
	} else {
		Box(
			contentAlignment = Alignment.Center,
			modifier = Modifier.size(ChannelIconWidth, ChannelIconHeight),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_tv),
				contentDescription = null,
				tint = Color.White.copy(alpha = 0.72f),
				modifier = Modifier.size(ChannelIconHeight),
			)
		}
	}
}

private fun List<BaseItemDto>.sortedByChannelName(): List<BaseItemDto> = sortedWith(
	compareBy<BaseItemDto, String>(String.CASE_INSENSITIVE_ORDER) { channel -> channel.name.orEmpty() }
		.thenBy(String.CASE_INSENSITIVE_ORDER) { channel -> channel.number.orEmpty() }
)

private fun List<BaseItemDto>.currentProgramsByChannel(): Map<UUID, List<BaseItemDto>> = mapNotNull { channel ->
	val channelId = channel.liveTvChannelId()
	val currentProgram = channel.currentProgram
	if (channelId != null && currentProgram != null) channelId to listOf(currentProgram)
	else null
}.toMap()

private fun Map<UUID, List<BaseItemDto>>.mergeWith(
	other: Map<UUID, List<BaseItemDto>>,
): Map<UUID, List<BaseItemDto>> = (keys + other.keys).associateWith { channelId ->
	(get(channelId).orEmpty() + other[channelId].orEmpty())
		.distinctBy { program -> program.id }
		.sortedBy { program -> program.startDate ?: LocalDateTime.MIN }
}

private fun Collection<BaseItemDto>.programAt(
	guideTime: LocalDateTime,
): BaseItemDto? = sortedBy { program -> program.startDate ?: LocalDateTime.MIN }
	.firstOrNull { program ->
		val start = program.startDate
		val end = program.endDate

		start != null && end != null && !start.isAfter(guideTime) && end.isAfter(guideTime)
	}
	?: firstOrNull { program ->
		program.startDate?.isAfter(guideTime) == true
	}
	?: firstOrNull()

private fun Map<UUID, List<BaseItemDto>>.guideTimeRange(): Pair<LocalDateTime, LocalDateTime>? {
	val programs = values
		.asSequence()
		.flatten()
		.toList()
	if (programs.isEmpty()) return null

	val start = programs.mapNotNull { program -> program.startDate }.minOrNull() ?: return null
	val end = programs.mapNotNull { program -> program.endDate ?: program.startDate }.maxOrNull() ?: return null

	return start to end
}

private fun LocalDateTime.coerceIn(
	minimumValue: LocalDateTime,
	maximumValue: LocalDateTime,
): LocalDateTime = when {
	isBefore(minimumValue) -> minimumValue
	isAfter(maximumValue) -> maximumValue
	else -> this
}

@Composable
private fun BaseItemDto?.guideCaption(): String {
	if (this == null) return stringResource(R.string.no_program_data)

	val context = LocalContext.current
	val timeFormatter = remember(context) { context.getTimeFormatter() }
	val timeRange = startDate?.let { start ->
		endDate?.let { end ->
			"${timeFormatter.format(start)} - ${timeFormatter.format(end)}"
		}
	}

	return listOfNotNull(name, timeRange)
		.takeIf { parts -> parts.isNotEmpty() }
		?.joinToString("  ")
		?: stringResource(R.string.no_program_data)
}
