package org.jellyfin.androidtv.ui.player.video

import android.content.Context
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup
import org.jellyfin.androidtv.ui.RecordingIndicatorView
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.livetv.liveTvChannelFields
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.sdk.liveTvChannelId
import org.jellyfin.design.Tokens
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

private const val GuideTimeStepMinutes = 30L
private const val GuideProgramWindowBeforeMinutes = GuideTimeStepMinutes
private const val GuideProgramWindowAfterMinutes = GuideTimeStepMinutes * 6
private const val GuideVisibleTimeSlots = 5
private val GuideChannelColumnWidth = 188.dp
private val GuideRowHeight = 54.dp
private val GuidePreviewWidth = 332.dp
private val GuidePreviewHeight = 186.dp
private val GuideSummaryCompactHeight = 160.dp
private val ChannelIconWidth = 56.dp
private val ChannelIconHeight = 36.dp

private fun guideBrandBrush(start: Color, end: Color, alpha: Float) =
	Brush.linearGradient(listOf(start.copy(alpha = alpha), Color.Transparent, end.copy(alpha = alpha)))

internal fun guideStartTime(time: LocalDateTime): LocalDateTime = time
	.minusMinutes((time.minute % GuideTimeStepMinutes.toInt()).toLong())
	.withSecond(0)
	.withNano(0)

@Composable
fun LiveTvGuideOverlay(
	playbackManager: PlaybackManager,
	currentItem: BaseItemDto,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
	showPreview: Boolean = true,
	fullScreenGuide: Boolean = false,
	previewOnChannelSelect: Boolean = true,
	onCurrentChannelSelected: (UUID?) -> Unit = { onDismiss() },
	miniPlayerContent: (@Composable () -> Unit)? = null,
	liveTvChannelNavigator: LiveTvChannelNavigator = rememberLiveTvChannelNavigator(),
	onRemoteKeyEventHandlerChanged: (((KeyEvent) -> Boolean)?) -> Unit = {},
	api: ApiClient = koinInject(),
	imageHelper: ImageHelper = koinInject(),
) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val rootView = LocalView.current
	val timeFormatter = remember(context) { context.getTimeFormatter() }
	val focusRequester = remember { FocusRequester() }
	val listState = rememberLazyListState()
	val coroutineScope = rememberCoroutineScope()
	val currentChannelId = currentItem.liveTvChannelId()
	var playingChannelId by remember(currentChannelId) { mutableStateOf(currentChannelId) }
	var channels by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var selectedChannelIndex by remember(currentChannelId) { mutableStateOf(0) }
	var switchingChannel by remember { mutableStateOf(false) }
	var expandingPreview by remember { mutableStateOf(false) }
	var centerKeyCode by remember { mutableStateOf<Int?>(null) }
	var centerLongPressTriggered by remember { mutableStateOf(false) }
	var centerLongPressJob by remember { mutableStateOf<Job?>(null) }
	var programDetailPopup by remember { mutableStateOf<LiveProgramDetailPopup?>(null) }
	var openingChannelId by remember { mutableStateOf<UUID?>(null) }
	var guideTime by remember { mutableStateOf(guideStartTime(LocalDateTime.now())) }
	var currentTime by remember { mutableStateOf(LocalDateTime.now()) }
	var guideLoadedAt by remember { mutableStateOf<LocalDateTime?>(null) }
	var guideProgramsByChannel by remember { mutableStateOf<Map<UUID, List<BaseItemDto>>?>(null) }
	var refreshVersion by remember { mutableStateOf(0) }
	val previewExpansion by animateFloatAsState(
		targetValue = if (showPreview && expandingPreview) 1f else 0f,
		animationSpec = tween(durationMillis = 260),
		finishedListener = { value ->
			if (value == 1f) onCurrentChannelSelected(openingChannelId)
		},
		label = "live-tv-guide-preview-expansion",
	)
	val guideFillsScreen = !showPreview || fullScreenGuide

	BackHandler(onBack = onDismiss)

	LaunchedEffect(liveTvChannelNavigator, refreshVersion) {
		channels = liveTvChannelNavigator.getChannels(forceRefresh = true)
	}

	LaunchedEffect(api) {
		api.webSocket.subscribe<LibraryChangedMessage>().collectLatest {
			refreshVersion++
		}
	}

	LaunchedEffect(Unit) {
		while (true) {
			currentTime = LocalDateTime.now()
			delay(5_000)
		}
	}

	LaunchedEffect(focusRequester, channels) {
		if (channels != null) focusRequester.requestFocus()
	}

	LaunchedEffect(channels, currentChannelId) {
		val loadedChannels = channels.orEmpty()
		if (loadedChannels.isEmpty()) return@LaunchedEffect
		guideProgramsByChannel = loadedChannels.currentProgramsByChannel()
		guideLoadedAt = LocalDateTime.now()

		selectedChannelIndex = loadedChannels
			.indexOfFirst { channel -> channel.liveTvChannelId() == currentChannelId }
			.takeIf { index -> index >= 0 }
			?: 0
		listState.scrollToItem(selectedChannelIndex)
	}

	LaunchedEffect(api, channels, refreshVersion) {
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
		guideTime = guideStartTime(referenceTime)

		guideProgramsByChannel = runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvPrograms(
					channelIds = channelIds,
					enableImages = false,
					fields = liveTvChannelFields,
					sortBy = setOf(ItemSortBy.START_DATE),
					sortOrder = setOf(SortOrder.ASCENDING),
					maxStartDate = endTime,
					minEndDate = startTime,
					enableTotalRecordCount = false,
				).content.items
			}
		}.onFailure { error ->
			Timber.e(error, "Unable to load Live TV guide programs")
		}.onSuccess {
			guideLoadedAt = LocalDateTime.now()
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

	fun finishChannelSelection() {
		openingChannelId = channels?.getOrNull(selectedChannelIndex)?.liveTvChannelId() ?: playingChannelId
		if (showPreview && !fullScreenGuide) expandingPreview = true
		else onCurrentChannelSelected(openingChannelId)
	}

	fun selectChannel(channel: BaseItemDto) {
		if (switchingChannel || expandingPreview) return

		val targetChannelId = channel.liveTvChannelId()
		if (targetChannelId != null && targetChannelId == playingChannelId) {
			finishChannelSelection()
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
						currentChannelId = playingChannelId,
					)
				) {
					playingChannelId = targetChannelId
					if (!previewOnChannelSelect) {
						finishChannelSelection()
					}
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

	fun clearCenterLongPress() {
		centerLongPressJob?.cancel()
		centerLongPressJob = null
		centerKeyCode = null
	}

	fun updateProgram(programId: UUID, transform: (BaseItemDto) -> BaseItemDto) {
		guideProgramsByChannel = guideProgramsByChannel?.mapValues { (_, programs) ->
			programs.map { program -> if (program.id == programId) transform(program) else program }
		}
		channels = channels?.map { channel ->
			channel.copy(
				currentProgram = channel.currentProgram?.let { program ->
					if (program.id == programId) transform(program) else program
				},
			)
		}
	}

	fun refreshChannelFavorite(channelId: UUID, isFavorite: Boolean) {
		val updatedChannel = TvManager.getAllChannels()?.firstOrNull { channel -> channel.id == channelId }
		channels = channels?.map { channel ->
			val userData = channel.userData
			when {
				channel.id != channelId -> channel
				updatedChannel != null -> updatedChannel
				userData != null -> channel.copy(userData = userData.copy(isFavorite = isFavorite))
				else -> channel
			}
		}
	}

	fun playChannelFromPopup(channel: BaseItemDto) {
		val targetChannelId = channel.liveTvChannelId() ?: return
		if (targetChannelId == playingChannelId || switchingChannel) return

		coroutineScope.launch {
			switchingChannel = true
			try {
				if (
					liveTvChannelNavigator.switchToChannel(
						playbackManager = playbackManager,
						currentItem = currentItem,
						targetChannel = channel,
						currentChannelId = playingChannelId,
					)
				) {
					playingChannelId = targetChannelId
				}
			} finally {
				switchingChannel = false
			}
		}
	}

	fun showSelectedProgramOptions() {
		val channel = channels?.getOrNull(selectedChannelIndex) ?: return
		val program = channel
			.liveTvChannelId()
			?.let { channelId -> guideProgramsByChannel?.get(channelId) }
			?.programAt(guideTime)
			?: channel.currentProgram
		val popupProgram = program ?: channel

		programDetailPopup?.dismiss()
		programDetailPopup = LiveProgramDetailPopup(
			context = context,
			lifecycleOwner = lifecycleOwner,
			tvGuide = object : LiveTvGuide {
				override fun displayChannels(start: Int, max: Int) = Unit
				override fun getCurrentLocalStartDate(): LocalDateTime = guideTime
				override fun showProgramOptions() = Unit
				override fun setSelectedProgram(programView: RelativeLayout) = Unit
				override fun refreshFavorite(channelId: UUID, isFavorite: Boolean) {
					refreshChannelFavorite(channelId, isFavorite)
				}
			},
			width = liveTvProgramPopupWidth(context),
			tuneAction = object : EmptyResponse(lifecycleOwner.lifecycle) {
				override fun onResponse() {
					if (isActive) playChannelFromPopup(channel)
				}
			},
		).also { popup ->
			popup.setContent(
				program = popupProgram,
				selectedGridView = object : RecordingIndicatorView {
					override fun setRecTimer(id: String?) {
						updateProgram(popupProgram.id) { program -> program.copy(timerId = id) }
					}

					override fun setRecSeriesTimer(id: String?) {
						updateProgram(popupProgram.id) { program -> program.copy(seriesTimerId = id) }
					}
				},
				favoriteChannel = channel,
			)
			popup.show(rootView, 0, 0)
		}
	}

	fun handleKeyEvent(nativeEvent: KeyEvent): Boolean = when (nativeEvent.keyCode) {
		KeyEvent.KEYCODE_BACK,
		KeyEvent.KEYCODE_ESCAPE,
		KeyEvent.KEYCODE_BUTTON_B -> {
			clearCenterLongPress()
			if (nativeEvent.action == KeyEvent.ACTION_DOWN && nativeEvent.repeatCount == 0) onDismiss()
			true
		}

		KeyEvent.KEYCODE_DPAD_UP -> {
			clearCenterLongPress()
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveSelection(-1)
			true
		}

		KeyEvent.KEYCODE_DPAD_DOWN -> {
			clearCenterLongPress()
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveSelection(1)
			true
		}

		KeyEvent.KEYCODE_DPAD_LEFT -> {
			clearCenterLongPress()
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveGuideTime(-GuideTimeStepMinutes)
			true
		}

		KeyEvent.KEYCODE_DPAD_RIGHT -> {
			clearCenterLongPress()
			if (nativeEvent.action == KeyEvent.ACTION_DOWN) moveGuideTime(GuideTimeStepMinutes)
			true
		}

		KeyEvent.KEYCODE_DPAD_CENTER,
		KeyEvent.KEYCODE_ENTER,
		KeyEvent.KEYCODE_NUMPAD_ENTER,
		KeyEvent.KEYCODE_BUTTON_A -> {
			when (nativeEvent.action) {
				KeyEvent.ACTION_DOWN -> {
					if (nativeEvent.repeatCount == 0) {
						centerKeyCode = nativeEvent.keyCode
						centerLongPressTriggered = false
						centerLongPressJob?.cancel()
						centerLongPressJob = coroutineScope.launch {
							delay(ViewConfiguration.getLongPressTimeout().toLong())
							centerLongPressTriggered = true
							showSelectedProgramOptions()
						}
					}
				}

				KeyEvent.ACTION_UP -> {
					if (centerKeyCode == nativeEvent.keyCode) {
						val shouldSelect = !centerLongPressTriggered
						clearCenterLongPress()
						if (shouldSelect) selectHighlightedChannel()
					}
				}
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
			centerLongPressJob?.cancel()
			programDetailPopup?.dismiss()
			onRemoteKeyEventHandlerChanged(null)
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.liveTvGuideScrim(showPreview, previewExpansion, fullScreenGuide)
			.focusRequester(focusRequester)
			.focusable()
			.then(if (showPreview && !fullScreenGuide) Modifier.overscan() else Modifier),
	) {
		Column(
			verticalArrangement = Arrangement.spacedBy(2.dp),
			modifier = Modifier
				.align(if (guideFillsScreen) Alignment.Center else Alignment.BottomCenter)
				.fillMaxWidth()
				.fillMaxHeight(if (guideFillsScreen) 1f else 0.82f)
				.graphicsLayer { alpha = 1f - previewExpansion }
				.padding(
					horizontal = when {
						showPreview && !fullScreenGuide -> 56.dp
						!fullScreenGuide -> 32.dp
						else -> 0.dp
					},
					vertical = when {
						showPreview && !fullScreenGuide -> 36.dp
						!fullScreenGuide -> 32.dp
						else -> 0.dp
					},
				),
		) {
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

				else -> {
					val brandStart = colorResource(R.color.card_focus_gradient_start)
					val brandEnd = colorResource(R.color.card_focus_gradient_end)
					val brandAlpha = integerResource(R.integer.card_focus_overlay_alpha_percent) / 100f
					val selectedChannel = loadedChannels.getOrNull(selectedChannelIndex)
					val selectedProgram = selectedChannel
						?.liveTvChannelId()
						?.let { channelId -> guideProgramsByChannel?.get(channelId) }
						?.programAt(guideTime)
						?: selectedChannel?.currentProgram
					val timeSlots = remember(guideTime) {
						List(GuideVisibleTimeSlots) { slot -> guideTime.plusMinutes(GuideTimeStepMinutes * slot) }
					}
					val guideTextSecondary = colorResource(R.color.guide_text_secondary)

					GuideSummary(
						channel = selectedChannel,
						program = selectedProgram,
						guideTime = guideTime,
						timeFormatter = timeFormatter,
						showPreview = showPreview,
						textSecondary = guideTextSecondary,
						miniPlayerContent = miniPlayerContent,
					)
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.weight(1f)
							.currentTimeIndicator(guideTime, currentTime, brandStart, brandEnd),
					) {
						Column {
							GuideTimeHeader(timeSlots, timeFormatter, guideLoadedAt, currentTime, guideTextSecondary)

							LazyColumn(
								state = listState,
								verticalArrangement = Arrangement.spacedBy(1.dp),
							) {
								itemsIndexed(
									items = loadedChannels,
									key = { _, channel -> channel.id.toString() },
								) { index, channel ->
									val channelPrograms = guideProgramsByChannel?.get(channel.liveTvChannelId())

									LiveTvGuideChannelRow(
										channel = channel,
										programs = channelPrograms,
										channelRecordingProgram = channelPrograms.recordingProgram() ?: channel.currentProgram?.takeIf { program ->
											program.hasRecordingIndicator()
										},
										loadingProgram = guideProgramsByChannel == null,
										selected = index == selectedChannelIndex,
										current = channel.liveTvChannelId() == playingChannelId,
										enabled = !switchingChannel,
										timeSlots = timeSlots,
										brandStart = brandStart,
										brandEnd = brandEnd,
										brandAlpha = brandAlpha,
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
	}
}
}

@Composable
private fun LiveTvGuideChannelRow(
	channel: BaseItemDto,
	programs: List<BaseItemDto>?,
	channelRecordingProgram: BaseItemDto?,
	loadingProgram: Boolean,
	selected: Boolean,
	current: Boolean,
	enabled: Boolean,
	timeSlots: List<LocalDateTime>,
	brandStart: Color,
	brandEnd: Color,
	brandAlpha: Float,
	imageHelper: ImageHelper,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(GuideRowHeight)
			.pointerInput(enabled) {
				if (enabled) detectTapGestures(onTap = { onClick() })
			},
	)
	{
		GuideChannelCell(
			channel = channel,
			channelRecordingProgram = channelRecordingProgram,
			current = current,
			brandStart = brandStart,
			brandEnd = brandEnd,
			brandAlpha = brandAlpha,
			imageHelper = imageHelper,
		)

		if (loadingProgram) {
			GuideProgramCell(
				program = null,
				selected = selected,
				loading = true,
				modifier = Modifier.weight(GuideVisibleTimeSlots.toFloat()),
			)
		} else {
			programs.programBlocks(timeSlots).forEach { block ->
				GuideProgramCell(
					program = block.program,
					selected = selected && block.includesGuideTime,
					loading = false,
					brandStart = brandStart,
					brandEnd = brandEnd,
					brandAlpha = brandAlpha,
					modifier = Modifier.weight(block.slots.toFloat()),
				)
			}
		}
	}
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

@Composable
private fun GuideSummary(
	channel: BaseItemDto?,
	program: BaseItemDto?,
	guideTime: LocalDateTime,
	timeFormatter: java.time.format.DateTimeFormatter,
	showPreview: Boolean,
	textSecondary: Color,
	miniPlayerContent: (@Composable () -> Unit)?,
) {
	val panelColor = colorResource(R.color.guide_panel_bg)
	val panelBorderColor = colorResource(R.color.guide_panel_border)
	val brandStart = colorResource(R.color.card_focus_gradient_start)
	val brandEnd = colorResource(R.color.card_focus_gradient_end)
	val brandAlpha = integerResource(R.integer.card_focus_overlay_alpha_percent) / 100f
	val hasPreview = showPreview || miniPlayerContent != null
	val overview = program.guideDescription()
		?: channel
			?.currentProgram
			?.takeIf { currentProgram -> program == null || currentProgram.id == program.id }
			.guideDescription()
		?: channel
			?.overview
			?.takeIf { overview -> overview.any(Char::isLetterOrDigit) }
		?: ""
	val programTime = program
		?.guideTimeRange(timeFormatter)
		?.takeIf { timeRange -> timeRange.isNotBlank() }
		?: timeFormatter.format(guideTime)
	val programRating = program?.officialRating?.takeIf { rating -> rating.isNotBlank() }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(if (hasPreview) GuidePreviewHeight else GuideSummaryCompactHeight),
		horizontalArrangement = Arrangement.spacedBy(2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (hasPreview) {
			Box(
				modifier = Modifier
					.size(GuidePreviewWidth, GuidePreviewHeight)
					.clip(JellyfinTheme.shapes.small)
					.border(1.dp, panelBorderColor, JellyfinTheme.shapes.small),
			) {
				miniPlayerContent?.invoke()
			}
		}

		Column(
			modifier = Modifier
				.fillMaxHeight()
				.weight(1f)
				.background(panelColor, JellyfinTheme.shapes.small)
				.background(guideBrandBrush(brandStart, brandEnd, brandAlpha / 3f), JellyfinTheme.shapes.small)
				.padding(14.dp),
			verticalArrangement = if (hasPreview) Arrangement.spacedBy(4.dp, Alignment.CenterVertically) else Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = program?.name ?: channel?.name ?: stringResource(R.string.lbl_live_tv_guide),
				style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = listOfNotNull(programTime, programRating).joinToString(" · "),
				style = JellyfinTheme.typography.listCaption.copy(color = textSecondary),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = overview,
				style = JellyfinTheme.typography.listCaption.copy(color = textSecondary),
				maxLines = 5,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

private fun Modifier.currentTimeIndicator(
	guideTime: LocalDateTime,
	currentTime: LocalDateTime,
	brandStart: Color,
	brandEnd: Color,
) = drawWithContent {
	drawContent()
	val fraction = currentTimeFraction(guideTime, currentTime) ?: return@drawWithContent
	val channelWidth = GuideChannelColumnWidth.toPx()
	val x = channelWidth + (size.width - channelWidth) * fraction
	drawLine(
		brush = Brush.verticalGradient(listOf(brandStart, brandEnd)),
		start = Offset(x, 0f),
		end = Offset(x, size.height),
		strokeWidth = 2.dp.toPx(),
	)
	drawCircle(brandStart, radius = 4.dp.toPx(), center = Offset(x, 4.dp.toPx()))
}

internal fun currentTimeFraction(
	guideTime: LocalDateTime,
	currentTime: LocalDateTime,
): Float? = (Duration.between(guideTime, currentTime).toMillis().toFloat() /
	Duration.ofMinutes(GuideTimeStepMinutes * GuideVisibleTimeSlots).toMillis())
	.takeIf { it in 0f..1f }

@Composable
private fun GuideTimeHeader(
	timeSlots: List<LocalDateTime>,
	timeFormatter: java.time.format.DateTimeFormatter,
	guideLoadedAt: LocalDateTime?,
	currentTime: LocalDateTime,
	textSecondary: Color,
) {
	val timelineColor = colorResource(R.color.guide_timeline_bg)
	val brandStart = colorResource(R.color.card_focus_gradient_start)
	val brandEnd = colorResource(R.color.card_focus_gradient_end)
	val brandAlpha = integerResource(R.integer.card_focus_overlay_alpha_percent) / 100f

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(28.dp)
			.background(timelineColor)
			.background(guideBrandBrush(brandStart, brandEnd, brandAlpha / 3f)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = guideLoadedAt?.let {
				"${stringResource(R.string.lbl_live_tv_guide)} · ${stringResource(R.string.lbl_epg_age_minutes, guideAgeMinutes(it, currentTime))}"
			} ?: stringResource(R.string.lbl_live_tv_guide),
			style = JellyfinTheme.typography.listCaption.copy(color = textSecondary),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.width(GuideChannelColumnWidth)
				.padding(horizontal = 10.dp),
		)

		timeSlots.forEach { time ->
			Text(
				text = timeFormatter.format(time),
				style = JellyfinTheme.typography.listCaption.copy(color = textSecondary),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier
					.weight(1f)
					.padding(horizontal = 8.dp),
			)
		}
	}
}

internal fun guideAgeMinutes(loadedAt: LocalDateTime, currentTime: LocalDateTime): Long =
	Duration.between(loadedAt, currentTime).toMinutes().coerceAtLeast(0)

@Composable
private fun GuideChannelCell(
	channel: BaseItemDto,
	channelRecordingProgram: BaseItemDto?,
	current: Boolean,
	brandStart: Color,
	brandEnd: Color,
	brandAlpha: Float,
	imageHelper: ImageHelper,
) {
	val channelColor = colorResource(R.color.guide_channel_cell_bg)
	val currentChannelColor = colorResource(R.color.guide_panel_bg)
	val textSecondary = colorResource(R.color.guide_text_secondary)

	Row(
		modifier = Modifier
			.width(GuideChannelColumnWidth)
			.fillMaxHeight()
			.background(if (current) currentChannelColor else channelColor)
			.background(guideBrandBrush(brandStart, brandEnd, if (current) brandAlpha else brandAlpha / 3f))
			.padding(horizontal = 10.dp, vertical = 6.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(
			modifier = Modifier.weight(1f),
		) {
			Text(
				text = channel.name.orEmpty(),
				style = JellyfinTheme.typography.listHeadline.copy(color = JellyfinTheme.colorScheme.listHeadline),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = channel.number.orEmpty(),
				style = JellyfinTheme.typography.listCaption.copy(color = textSecondary),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		GuideIndicators(
			channel = channel,
			channelRecordingProgram = channelRecordingProgram,
			program = null,
		)
		ChannelIcon(channel, imageHelper)
	}
}

@Composable
private fun Modifier.liveTvGuideScrim(
	showPreview: Boolean,
	expansion: Float,
	fullScreenGuide: Boolean,
): Modifier {
	val gridColor = colorResource(R.color.guide_grid_bg)
	val brandStart = colorResource(R.color.card_focus_gradient_start)
	val brandEnd = colorResource(R.color.card_focus_gradient_end)
	val brandAlpha = integerResource(R.integer.card_focus_overlay_alpha_percent) / 100f
	val brandBrush = guideBrandBrush(brandStart, brandEnd, brandAlpha / 3f)
	if (!showPreview) return background(gridColor).background(brandBrush)

	val density = LocalDensity.current
	val previewLeft = if (fullScreenGuide) 0f else with(density) { 56.dp.toPx() }
	val previewTopPadding = with(density) { 36.dp.toPx() }
	val previewWidth = with(density) { GuidePreviewWidth.toPx() }
	val previewHeight = with(density) { GuidePreviewHeight.toPx() }
	val cornerRadius = with(density) { 8.dp.toPx() }

	// Preview uses the existing player behind this cutout; add a second player only if this needs independent playback.
	return this.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
		.drawWithContent {
			val progress = expansion.coerceIn(0f, 1f)
			val startTop = if (fullScreenGuide) 0f else size.height * 0.18f + previewTopPadding
			val left = previewLeft * (1f - progress)
			val top = startTop * (1f - progress)
			val width = previewWidth + (size.width - previewWidth) * progress
			val height = previewHeight + (size.height - previewHeight) * progress
			val radius = cornerRadius * (1f - progress)

			drawRect(gridColor.copy(alpha = gridColor.alpha * (1f - progress)))
			drawRect(brush = brandBrush, alpha = 1f - progress)
			drawRoundRect(
				color = Color.Transparent,
				topLeft = Offset(
					x = left,
					y = top,
				),
				size = Size(width, height),
				cornerRadius = CornerRadius(radius, radius),
				blendMode = BlendMode.Clear,
			)
			drawContent()
		}
}

@Composable
private fun GuideProgramCell(
	program: BaseItemDto?,
	selected: Boolean,
	loading: Boolean,
	brandStart: Color? = null,
	brandEnd: Color? = null,
	brandAlpha: Float = 0f,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val timeFormatter = remember(context) { context.getTimeFormatter() }
	val programColor = colorResource(R.color.guide_program_cell_bg)
	val focusStroke = colorResource(R.color.card_focus_stroke)
	val focusStrokeWidth = dimensionResource(R.dimen.card_focus_stroke_width)
	val description = program.guideDescription()

	Box(
		modifier = modifier
			.fillMaxHeight()
			.padding(start = 1.dp)
			.background(programColor)
			.then(
				if (brandStart != null && brandEnd != null) Modifier.background(
					guideBrandBrush(brandStart, brandEnd, if (selected) brandAlpha else brandAlpha / 3f)
				) else Modifier
			)
			.border(
				width = if (selected) focusStrokeWidth else 0.dp,
				color = if (selected) focusStroke else Color.Transparent,
			)
			.padding(horizontal = 8.dp, vertical = 6.dp),
	) {
		Column {
			Row(
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = when {
						loading -> stringResource(R.string.loading)
						program != null -> program.name.orEmpty()
						else -> stringResource(R.string.no_program_data)
					},
					style = JellyfinTheme.typography.listCaption.copy(color = JellyfinTheme.colorScheme.listHeadline),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (program.hasRecordingIndicator()) RecordingIndicator(requireNotNull(program))
			}
			program?.guideTimeRange(timeFormatter)?.takeIf(String::isNotBlank)?.let { timeRange ->
				Text(timeRange, style = JellyfinTheme.typography.listCaption.copy(color = JellyfinTheme.colorScheme.listCaption), maxLines = 1)
			}
			description?.let {
				Text(it, style = JellyfinTheme.typography.listCaption.copy(color = JellyfinTheme.colorScheme.listCaption), maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

@Composable
private fun RecordingIndicator(
	program: BaseItemDto,
) {
	val icon = when {
		program.seriesTimerId != null -> R.drawable.ic_record_series
		program.timerId != null -> R.drawable.ic_record
		else -> return
	}

	Icon(
		imageVector = ImageVector.vectorResource(icon),
		contentDescription = null,
		tint = if (program.timerId != null) Tokens.Color.colorRed600 else Tokens.Color.colorGrey100,
		modifier = Modifier.size(20.dp),
	)
}

@Composable
private fun GuideIndicators(
	channel: BaseItemDto,
	channelRecordingProgram: BaseItemDto?,
	program: BaseItemDto?,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (channel.userData?.isFavorite == true) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_heart),
				contentDescription = null,
				tint = Tokens.Color.colorRed500,
				modifier = Modifier.size(20.dp),
			)
		}

		listOfNotNull(channelRecordingProgram, program)
			.filter { item -> item.hasRecordingIndicator() }
			.distinctBy { item -> item.id }
			.forEach { item -> RecordingIndicator(item) }
	}
}

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

private fun Collection<BaseItemDto>?.recordingProgram(): BaseItemDto? =
	this?.firstOrNull { program -> program.hasRecordingIndicator() }

private fun BaseItemDto?.hasRecordingIndicator(): Boolean =
	this?.timerId != null || this?.seriesTimerId != null

private data class GuideProgramBlock(
	val program: BaseItemDto?,
	val slots: Int,
	val includesGuideTime: Boolean,
)

private fun Collection<BaseItemDto>?.programBlocks(timeSlots: List<LocalDateTime>): List<GuideProgramBlock> {
	val programs = this
	if (programs == null) return listOf(GuideProgramBlock(null, GuideVisibleTimeSlots, true))

	return timeSlots
		.mapIndexed { index, slot -> index to programs.programOverlapping(slot) }
		.fold(emptyList()) { blocks, (index, program) ->
			val previous = blocks.lastOrNull()
			if (previous != null && previous.program?.id == program?.id) {
				blocks.dropLast(1) + previous.copy(slots = previous.slots + 1)
			} else {
				blocks + GuideProgramBlock(program, 1, index == 0)
			}
		}
}

private fun Collection<BaseItemDto>.programOverlapping(slotStart: LocalDateTime): BaseItemDto? {
	val slotEnd = slotStart.plusMinutes(GuideTimeStepMinutes)

	return sortedBy { program -> program.startDate ?: LocalDateTime.MAX }
		.firstOrNull { program ->
			val start = program.startDate
			val end = program.endDate

			start != null && end != null && start.isBefore(slotEnd) && end.isAfter(slotStart)
		}
}

private fun BaseItemDto.guideTimeRange(
	timeFormatter: java.time.format.DateTimeFormatter,
): String = startDate?.let { start ->
	endDate?.let { end ->
		"${timeFormatter.format(start)} - ${timeFormatter.format(end)}"
	}
}.orEmpty()

private fun BaseItemDto?.guideDescription(): String? =
	this?.overview?.takeIf { it.any(Char::isLetterOrDigit) }
		?: this?.episodeTitle?.takeIf { it != name && it.any(Char::isLetterOrDigit) }

private fun liveTvProgramPopupWidth(context: Context): Int {
	val horizontalMargin = Utils.convertDpToPixel(context, 48)
	return minOf(
		Utils.convertDpToPixel(context, 600),
		(context.resources.displayMetrics.widthPixels - horizontalMargin * 2).coerceAtLeast(horizontalMargin),
	)
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
