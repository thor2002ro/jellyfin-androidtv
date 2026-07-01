package org.jellyfin.androidtv.ui.player.video

import android.view.KeyEvent
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListControlDefaults
import org.jellyfin.androidtv.ui.composable.modifier.overscan
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

private const val GuideProgramLimitEachDirection = 5
private const val GuideTimeStepMinutes = 30L
private const val GuideProgramWindowSize = GuideProgramLimitEachDirection * 2 + 1

@Composable
fun LiveTvGuideOverlay(
	playbackManager: PlaybackManager,
	currentItem: BaseItemDto,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
	liveTvChannelNavigator: LiveTvChannelNavigator = rememberLiveTvChannelNavigator(),
	onRemoteKeyEventHandlerChanged: (((KeyEvent) -> Boolean)?) -> Unit = {},
	api: ApiClient = koinInject(),
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
		channels = liveTvChannelNavigator.getChannels()
	}

	LaunchedEffect(focusRequester, channels) {
		if (channels != null) focusRequester.requestFocus()
	}

	LaunchedEffect(channels, currentChannelId) {
		val loadedChannels = channels.orEmpty()
		if (loadedChannels.isEmpty()) return@LaunchedEffect

		selectedChannelIndex = loadedChannels
			.indexOfFirst { channel -> channel.liveTvChannelId() == currentChannelId }
			.takeIf { index -> index >= 0 }
			?: 0
		listState.scrollToItem(selectedChannelIndex)
	}

	LaunchedEffect(api, channels) {
		val channelIds = channels
			.orEmpty()
			.mapNotNull { channel -> channel.liveTvChannelId() }

		if (channelIds.isEmpty()) {
			guideProgramsByChannel = emptyMap()
			return@LaunchedEffect
		}

		val referenceTime = LocalDateTime.now().withSecond(0).withNano(0)
		val queryLimit = channelIds.size * GuideProgramWindowSize

		guideProgramsByChannel = null
		guideProgramsByChannel = runCatching {
			withContext(Dispatchers.IO) {
				// LiveTv/Programs only supports a global limit, so fetch bounded
				// previous/next batches and trim each channel locally below.
				val previousPrograms = api.liveTvApi.getLiveTvPrograms(
					channelIds = channelIds,
					enableImages = false,
					sortBy = setOf(ItemSortBy.START_DATE),
					sortOrder = setOf(SortOrder.DESCENDING),
					maxStartDate = referenceTime,
					limit = queryLimit,
					enableTotalRecordCount = false,
				).content.items
				val nextPrograms = api.liveTvApi.getLiveTvPrograms(
					channelIds = channelIds,
					enableImages = false,
					sortBy = setOf(ItemSortBy.START_DATE),
					sortOrder = setOf(SortOrder.ASCENDING),
					minEndDate = referenceTime,
					limit = queryLimit,
					enableTotalRecordCount = false,
				).content.items

				(previousPrograms + nextPrograms).distinctBy { program -> program.id }
			}
		}.onFailure { error ->
			Timber.e(error, "Unable to load Live TV guide programs")
		}.getOrDefault(emptyList())
			.groupBy { program -> program.liveTvChannelId() }
			.mapNotNull { (channelId, programs) ->
				if (channelId != null) {
					channelId to programs.limitAround(referenceTime)
				} else {
					null
				}
			}
			.toMap()
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
		captionContent = {
			Text(
				text = if (loadingProgram) stringResource(R.string.loading) else program.guideCaption(),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
	)
}

private fun Collection<BaseItemDto>.limitAround(
	referenceTime: LocalDateTime,
): List<BaseItemDto> {
	val sortedPrograms = sortedBy { program -> program.startDate ?: LocalDateTime.MIN }
	if (sortedPrograms.isEmpty()) return emptyList()

	val referenceIndex = sortedPrograms.referenceIndex(referenceTime)
	val startIndex = (referenceIndex - GuideProgramLimitEachDirection).coerceAtLeast(0)
	val endIndex = (referenceIndex + GuideProgramLimitEachDirection + 1).coerceAtMost(sortedPrograms.size)

	return sortedPrograms.subList(startIndex, endIndex)
}

private fun List<BaseItemDto>.referenceIndex(
	referenceTime: LocalDateTime,
): Int {
	val currentProgramIndex = indexOfFirst { program ->
		val start = program.startDate
		val end = program.endDate

		start != null && end != null && !start.isAfter(referenceTime) && end.isAfter(referenceTime)
	}
	if (currentProgramIndex >= 0) return currentProgramIndex

	val nextProgramIndex = indexOfFirst { program ->
		program.startDate?.isAfter(referenceTime) == true
	}
	if (nextProgramIndex >= 0) return nextProgramIndex

	return lastIndex
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
	val startDates = values
		.asSequence()
		.flatten()
		.mapNotNull { program -> program.startDate }
		.toList()
	if (startDates.isEmpty()) return null

	return startDates.minOrNull()!! to startDates.maxOrNull()!!
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
