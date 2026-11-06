package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerProgress
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.languageCodesMatch
import org.jellyfin.androidtv.util.sdk.STREAM_BADGE_DISPLAY_LIMIT
import org.jellyfin.androidtv.util.sdk.hasLanguageBadgeStreams
import org.jellyfin.androidtv.util.sdk.streamBadgeItemTypes
import org.jellyfin.androidtv.util.sdk.videoBadgeCodecText
import org.jellyfin.androidtv.util.sdk.videoBadgeResolutionText
import org.jellyfin.androidtv.util.toStreamLanguageBadgeOrNull
import org.jellyfin.design.Tokens
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
@Stable
fun ItemCardBaseItemOverlay(
	item: BaseItemDto,
	streamBadgeItem: BaseItemDto = item,
	showRemainingTimeBadge: Boolean = false,
	showFavoriteIndicator: Boolean = true,
	footer: (@Composable () -> Unit)? = null,
) = BoxWithConstraints(
	modifier = Modifier
		.fillMaxSize()
) {
	val badgeSize = (minOf(maxWidth, maxHeight) * 0.09f).coerceAtMost(9.dp)
	val progress = rememberItemCardProgressInfo(item)
	val context = LocalContext.current
	val progressOpacity = if (item.isLiveTvCard) 0.35f else 0.65f
	val progressColor = remember(context.theme, progressOpacity) {
		Color(Utils.getThemeColor(context, android.R.attr.colorAccent)).copy(alpha = progressOpacity)
	}
	Box(
		Modifier
			.fillMaxSize()
			.drawBehind {
				val fraction = progress.progress.value ?: return@drawBehind
				if (fraction > 0f) {
					val progressWidth = size.width * fraction
					drawRect(
						brush = Brush.horizontalGradient(
							colors = listOf(progressColor, progressColor.copy(alpha = 0f)),
							startX = (progressWidth - 24.dp.toPx()).coerceAtLeast(0f),
							endX = progressWidth,
						),
						size = Size(progressWidth, size.height),
					)
				}
			}
			.padding(badgeSize * 0.5f)
	) {

		StateIndicator(
			item = item,
			badgeSize = badgeSize,
			showFavoriteIndicator = showFavoriteIndicator,
			modifier = Modifier.align(Alignment.TopStart),
		)

		TopEndIndicator(
			item = item,
			badgeSize = badgeSize,
			progress = progress,
			showRemainingTimeBadge = showRemainingTimeBadge,
			modifier = Modifier.align(Alignment.TopEnd),
		)

		Column(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(badgeSize * 0.5f)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.Bottom,
			) {
				Box(
					modifier = Modifier.weight(1f),
					contentAlignment = Alignment.BottomStart,
				) {
					VideoStreamBadges(item = streamBadgeItem, badgeSize = badgeSize)
				}

				Box(
					modifier = Modifier.weight(1f),
					contentAlignment = Alignment.BottomEnd,
				) {
					MediaStreamBadges(item = streamBadgeItem, badgeSize = badgeSize)
				}
			}

			if (footer != null) footer()
		}
	}
}

@Composable
@Stable
private fun TopEndIndicator(
	item: BaseItemDto,
	badgeSize: Dp,
	progress: ItemCardProgress,
	showRemainingTimeBadge: Boolean,
	modifier: Modifier = Modifier,
) {
	val remainingTimeText = if (showRemainingTimeBadge || item.isLiveTvCard) {
		val text by remember(item, progress) {
			derivedStateOf { item.remainingPlaybackTimeText(progress.progress.value, progress.duration) }
		}
		text
	} else null
	if (remainingTimeText != null) {
		MediaStreamBadge(
			icon = R.drawable.ic_time,
			badgeSize = badgeSize,
			text = remainingTimeText,
			modifier = modifier,
		)
	} else {
		WatchIndicator(
			item = item,
			badgeSize = badgeSize,
			modifier = modifier,
		)
	}
}

@Composable
@Stable
private fun StateIndicator(
	item: BaseItemDto,
	badgeSize: Dp,
	showFavoriteIndicator: Boolean,
	modifier: Modifier = Modifier,
) {
	val isFavorited = showFavoriteIndicator && item.userData?.isFavorite == true
	val recordingItem = item.recordingStateItem()
	val recordingIcon = when {
		recordingItem?.seriesTimerId != null -> R.drawable.ic_record_series
		recordingItem?.timerId != null -> R.drawable.ic_record
		else -> null
	}
	if (recordingIcon == null && !isFavorited) return

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(badgeSize * 0.5f)
	) {
		if (recordingIcon != null) {
			Icon(
				imageVector = ImageVector.vectorResource(recordingIcon),
				contentDescription = null,
				tint = if (recordingItem?.timerId != null) Tokens.Color.colorRed600 else Tokens.Color.colorGrey100,
				modifier = modifier
					.size(badgeSize * 2f)
			)
		}

		if (isFavorited) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_heart),
				contentDescription = null,
				tint = Tokens.Color.colorRed500,
				modifier = modifier
					.size(badgeSize * 2f)
			)
		}
	}
}

private fun BaseItemDto.recordingStateItem() = when (type) {
	BaseItemKind.TV_CHANNEL,
	BaseItemKind.LIVE_TV_CHANNEL -> currentProgram
	else -> this
}

@Composable
@Stable
private fun WatchIndicator(
	item: BaseItemDto,
	badgeSize: Dp,
	modifier: Modifier = Modifier,
) {
	val userPreferences = koinInject<UserPreferences>()
	val watchedIndicatorBehavior = userPreferences[UserPreferences.watchedIndicatorBehavior]

	if (watchedIndicatorBehavior == WatchedIndicatorBehavior.NEVER) return
	if (watchedIndicatorBehavior == WatchedIndicatorBehavior.EPISODES_ONLY && item.type != BaseItemKind.EPISODE) return

	val isPlayed = item.userData?.played == true
	val unplayedItems = item.userData?.unplayedItemCount?.takeIf { it > 0 }

	if (isPlayed) {
		Badge(
			modifier = modifier
				.size(badgeSize * 2f),
			contentPadding = badgeSize * 0.3f,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_watch),
				contentDescription = null,
				modifier = Modifier.size(badgeSize)
			)
		}
	} else if (unplayedItems != null) {
		if (watchedIndicatorBehavior == WatchedIndicatorBehavior.HIDE_UNWATCHED) return

		Badge(
			modifier = modifier
				.sizeIn(minWidth = badgeSize * 2f, minHeight = badgeSize * 2f),
			contentPadding = badgeSize * 0.3f,
		) {
			Text(
				text = unplayedItems.toString(),
				fontSize = badgeSize.value.sp,
				lineHeight = (badgeSize.value * 1.2f).sp,
			)
		}
	}
}

// Media types that need to show a user progress bar (based on playedPercentage in userData)
private val progressMediaTypes = setOf(MediaType.VIDEO, MediaType.BOOK)

@Composable
@Stable
private fun MediaStreamBadges(
	item: BaseItemDto,
	badgeSize: Dp,
	modifier: Modifier = Modifier,
) {
	val liveTvBadges = item.liveTvTrackBadges()
	val badges = if (liveTvBadges != null) {
		liveTvBadges
	} else {
		val hasLanguageBadgeSource = remember(item.type, item.mediaSources) { item.hasLanguageBadgeSource() }
		if (!hasLanguageBadgeSource) return

		val userRepository = koinInject<UserRepository>()
		val preferences by remember(userRepository) {
			userRepository.currentUser.map { it.streamBadgeLanguagePreferences() }.distinctUntilChanged()
		}.collectAsState(initial = userRepository.currentUser.value.streamBadgeLanguagePreferences())
		val (audioLanguagePreference, subtitleLanguagePreference) = preferences
		remember(item.type, item.mediaSources, audioLanguagePreference, subtitleLanguagePreference) {
			item.languageBadges(
				audioLanguagePreference = audioLanguagePreference,
				subtitleLanguagePreference = subtitleLanguagePreference,
			)
		} ?: return
	}

	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.End,
		verticalArrangement = Arrangement.spacedBy(badgeSize * 0.15f),
	) {
		badges.audio?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_badge_speaker,
				badgeSize = badgeSize,
				text = text,
			)
		}

		badges.subtitle?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_badge_subtitles,
				badgeSize = badgeSize,
				text = text,
			)
		}
	}
}

private fun UserDto?.streamBadgeLanguagePreferences(): Pair<String?, String?> {
	val configuration = this?.configuration
	return configuration?.takeUnless { it.playDefaultAudioTrack }?.audioLanguagePreference to configuration?.subtitleLanguagePreference
}

@Composable
@Stable
private fun VideoStreamBadges(
	item: BaseItemDto,
	badgeSize: Dp,
	modifier: Modifier = Modifier,
) {
	val badges = remember(item.type, item.mediaSources) { item.videoBadges() } ?: return

	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.Start,
		verticalArrangement = Arrangement.spacedBy(badgeSize * 0.15f),
	) {
		badges.resolution?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_select_quality,
				badgeSize = badgeSize,
				text = text,
			)
		}

		badges.codec?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_movie,
				badgeSize = badgeSize,
				text = text,
			)
		}
	}
}

@Composable
private fun MediaStreamBadge(
	icon: Int,
	badgeSize: Dp,
	text: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.background(Tokens.Color.colorBluegrey900.copy(alpha = 0.72f), JellyfinTheme.shapes.extraSmall)
			.padding(horizontal = badgeSize * 0.3f, vertical = badgeSize * 0.15f)
			.widthIn(min = badgeSize * 2.5f),
		horizontalArrangement = Arrangement.spacedBy(badgeSize * 0.15f),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = null,
			tint = Tokens.Color.colorWhite,
			modifier = Modifier.size(badgeSize),
		)
		Text(
			text = text,
			color = Tokens.Color.colorWhite,
			fontSize = badgeSize.value.sp,
			lineHeight = (badgeSize.value * 1.2f).sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

internal data class LanguageBadges(
	val audio: String?,
	val subtitle: String?,
)

internal data class VideoBadges(
	val resolution: String?,
	val codec: String?,
)

internal fun BaseItemDto.languageBadges(
	audioLanguagePreference: String?,
	subtitleLanguagePreference: String?,
): LanguageBadges? {
	if (type !in streamBadgeItemTypes) return null

	val sources = mediaSources.orEmpty()
	val audio = sources.badgeText(
		type = MediaStreamType.AUDIO,
		languagePreference = audioLanguagePreference,
	)
	val subtitle = sources.badgeText(
		type = MediaStreamType.SUBTITLE,
		languagePreference = subtitleLanguagePreference,
	)

	return LanguageBadges(audio, subtitle).takeIf { badges -> badges.audio != null || badges.subtitle != null }
}

private fun BaseItemDto.hasLanguageBadgeSource() =
	type in streamBadgeItemTypes && mediaSources.orEmpty().any { source -> source.hasLanguageBadgeStreams() }

internal fun BaseItemDto.videoBadges(): VideoBadges? {
	if (type !in streamBadgeItemTypes) return null

	val resolutions = linkedSetOf<String>()
	val codecs = linkedSetOf<String>()
	for (stream in mediaSources
		.orEmpty()
		.asSequence()
		.flatMap { source -> source.mediaStreams.orEmpty().asSequence() }
		.filter { stream -> stream.type == MediaStreamType.VIDEO }) {
		if (resolutions.size < STREAM_BADGE_DISPLAY_LIMIT) {
			stream.videoBadgeResolutionText()?.let(resolutions::add)
		}
		if (codecs.size < STREAM_BADGE_DISPLAY_LIMIT) {
			stream.videoBadgeCodecText()?.let(codecs::add)
		}
		if (resolutions.size >= STREAM_BADGE_DISPLAY_LIMIT && codecs.size >= STREAM_BADGE_DISPLAY_LIMIT) break
	}

	val resolution = resolutions
		.joinToString("/")
		.takeIf { it.isNotBlank() }
	val codec = codecs
		.joinToString("/")
		.takeIf { it.isNotBlank() }

	return VideoBadges(resolution, codec).takeIf { badges -> badges.resolution != null || badges.codec != null }
}

private fun BaseItemDto.liveTvTrackBadges(): LanguageBadges? {
	when (type) {
		BaseItemKind.TV_CHANNEL,
		BaseItemKind.LIVE_TV_CHANNEL,
		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.LIVE_TV_PROGRAM -> Unit
		else -> return null
	}

	val tracks = LiveTvTrackCache.get(this) ?: currentProgram?.let(LiveTvTrackCache::get) ?: return null
	val audio = tracks.audio.selectedTrack(tracks.selectedAudioTrackIndex)?.language.toStreamLanguageBadgeOrNull()
	val subtitle = tracks.subtitles
		.takeUnless { tracks.selectedSubtitleTrackIndex == -1 }
		?.selectedTrack(tracks.selectedSubtitleTrackIndex)
		?.language.toStreamLanguageBadgeOrNull()

	return LanguageBadges(audio, subtitle).takeIf { badges -> badges.audio != null || badges.subtitle != null }
}

private fun List<LiveTvTrackCache.Track>.selectedTrack(index: Int?) =
	index?.takeIf { it >= 0 }?.let { selected -> firstOrNull { track -> track.index == selected } }
		?: firstOrNull { track -> track.isDefault }
		?: firstOrNull()

private data class BadgeCandidate(
	val stream: MediaStream,
	val isSelectedByIndex: Boolean,
	val canUseDefault: Boolean,
)

private fun List<MediaSourceInfo>.badgeText(
	type: MediaStreamType,
	languagePreference: String?,
): String? {
	val candidates = flatMap { source ->
		val streams = source.mediaStreams.orEmpty().filter { stream -> stream.type == type }
		if (streams.isEmpty()) return@flatMap emptyList()

		val defaultIndex = when (type) {
			MediaStreamType.AUDIO -> source.defaultAudioStreamIndex
			MediaStreamType.SUBTITLE -> source.defaultSubtitleStreamIndex
			else -> null
		}
		val canUseDefault = type != MediaStreamType.SUBTITLE || source.defaultSubtitleStreamIndex != -1
		streams.map { stream ->
			BadgeCandidate(
				stream = stream,
				isSelectedByIndex = canUseDefault && defaultIndex != null && stream.index == defaultIndex,
				canUseDefault = canUseDefault,
			)
		}
	}
	if (candidates.isEmpty()) return null

	val badges = linkedSetOf<String>()
	fun add(candidate: BadgeCandidate) {
		val badge = candidate.stream.badgeText() ?: return
		badges.add(badge)
	}

	languagePreference?.takeIf { it.isNotBlank() }?.let { preferredLanguage ->
		candidates
			.filter { candidate -> languageCodesMatch(candidate.stream.language, preferredLanguage) }
			.forEach(::add)
	}
	candidates.filter { candidate -> candidate.isSelectedByIndex }.forEach(::add)
	candidates.filter { candidate -> candidate.canUseDefault && candidate.stream.isDefault }.forEach(::add)
	candidates.forEach(::add)

	val visibleBadges = badges.take(STREAM_BADGE_DISPLAY_LIMIT)
	val hiddenCount = badges.size - visibleBadges.size
	return (visibleBadges + listOfNotNull(hiddenCount.takeIf { it > 0 }?.let { "+$it" }))
		.joinToString(" ")
		.takeIf { it.isNotBlank() }
}

private fun MediaStream.badgeText(): String? =
	language.toStreamLanguageBadgeOrNull()

internal fun BaseItemDto.storedPlayedProgress(): Float? =
	userData?.takeIf { mediaType in progressMediaTypes }?.playedPercentage
		?.toFloat()
		?.div(100f)
		?.validPlaybackProgress()
		?.takeIf { it > 0f && it < 1f }

internal fun BaseItemDto.remainingPlaybackTimeText(progress: Float?, duration: Duration? = null): String? {
	val playedProgress = progress?.validPlaybackProgress() ?: return null
	val runtime = duration?.takeIf { it > Duration.ZERO }
		?: runTimeTicks?.takeIf { it > 0 }?.ticks
		?: return null
	val remainingMillis = (runtime.inWholeMilliseconds * (1f - playedProgress))
		.roundToLong()
		.takeIf { it > 0 } ?: return null

	return "-${TimeUtils.formatMillis(remainingMillis)}"
}

private fun Float.validPlaybackProgress() =
	takeIf { it.isFinite() && it >= 0f && it <= 1f }

private val BaseItemDto.isLiveTvCard: Boolean
	get() = when (type) {
		BaseItemKind.TV_CHANNEL, BaseItemKind.LIVE_TV_CHANNEL,
		BaseItemKind.PROGRAM, BaseItemKind.TV_PROGRAM, BaseItemKind.LIVE_TV_PROGRAM -> true
		else -> false
	}

@Composable
private fun rememberLiveTvCardProgress(item: BaseItemDto): ItemCardProgress {
	// Use the program already loaded with the card; ticking the countdown needs no server requests.
	val program = when (item.type) {
		BaseItemKind.TV_CHANNEL, BaseItemKind.LIVE_TV_CHANNEL -> item.currentProgram
		else -> item
	}
	val start = program?.startDate
	val end = program?.endDate
	val duration = remember(start, end) {
		if (start != null && end != null && end.isAfter(start)) {
			ChronoUnit.MILLIS.between(start, end).takeIf { it > 0 }?.milliseconds
		} else null
	}
	val owner = LocalLifecycleOwner.current
	val progress = produceState<Float?>(null, start, end, owner) {
		value = null
		if (start == null || end == null || duration == null) return@produceState
		// Pause the clock while the screen is inactive and read the current time when it returns.
		owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			while (true) {
				val now = LocalDateTime.now()
				value = if (!now.isBefore(start) && now.isBefore(end)) {
					ChronoUnit.MILLIS.between(start, now).toFloat() / duration.inWholeMilliseconds
				} else null
				// Hide ended programs until the normal card refresh supplies the next program.
				if (!now.isBefore(end)) break
				delay(1000)
			}
		}
	}
	return remember(progress, duration) { ItemCardProgress(progress, duration) }
}

@Composable
private fun rememberItemCardProgressInfo(
	item: BaseItemDto,
): ItemCardProgress {
	// Live TV follows the broadcast schedule, even when playback is paused or behind live.
	if (item.isLiveTvCard) return rememberLiveTvCardProgress(item)

	val playbackManager = koinInject<PlaybackManager>()
	val playState by playbackManager.state.playState.collectAsState()
	val currentQueueEntry by rememberQueueEntry(playbackManager)

	return if (playState.isActivePlayback && currentQueueEntry?.baseItem?.id == item.id) {
		val animatedProgress = rememberPlayerProgress(playbackManager)
		val progress = remember(animatedProgress) { derivedStateOf { animatedProgress.value.validPlaybackProgress() } }
		val duration = playbackManager.state.positionInfo.duration
		remember(progress, duration) { ItemCardProgress(progress, duration) }
	} else {
		remember(item) {
			ItemCardProgress(
				progress = mutableStateOf(item.storedPlayedProgress()),
				duration = item.runTimeTicks?.takeIf { it > 0 }?.ticks,
			)
		}
	}
}

private data class ItemCardProgress(
	val progress: State<Float?>,
	val duration: Duration?,
)
