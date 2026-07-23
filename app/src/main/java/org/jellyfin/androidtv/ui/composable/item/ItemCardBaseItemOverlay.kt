package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.ui.base.Badge
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Seekbar
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerProgress
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.util.TimeUtils
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
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject
import kotlin.math.roundToLong
import kotlin.time.Duration

@Composable
@Stable
fun ItemCardBaseItemOverlay(
	item: BaseItemDto,
	streamBadgeItem: BaseItemDto = item,
	showRemainingTimeBadge: Boolean = false,
	footer: (@Composable () -> Unit)? = null,
) = Box(
	modifier = Modifier
		.fillMaxSize()
		.padding(Tokens.Space.spaceXs)
) {
	val progress = rememberItemCardProgressInfo(item)

	StateIndicator(
		item = item,
		modifier = Modifier.align(Alignment.TopStart),
	)

	TopEndIndicator(
		item = item,
		progress = progress?.progress,
		duration = progress?.duration,
		showRemainingTimeBadge = showRemainingTimeBadge,
		modifier = Modifier.align(Alignment.TopEnd),
	)

	Column(
		modifier = Modifier
			.align(Alignment.BottomCenter)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.Bottom,
		) {
			Box(
				modifier = Modifier.weight(1f),
				contentAlignment = Alignment.BottomStart,
			) {
				VideoStreamBadges(item = streamBadgeItem)
			}

			Box(
				modifier = Modifier.weight(1f),
				contentAlignment = Alignment.BottomEnd,
			) {
				MediaStreamBadges(item = streamBadgeItem)
			}
		}

		ProgressIndicator(
			progress = progress?.progress,
		)

		if (footer != null) footer()
	}
}

@Composable
@Stable
private fun TopEndIndicator(
	item: BaseItemDto,
	progress: Float?,
	duration: Duration?,
	showRemainingTimeBadge: Boolean,
	modifier: Modifier = Modifier,
) {
	val remainingTimeText = if (showRemainingTimeBadge) item.remainingPlaybackTimeText(progress, duration) else null
	if (remainingTimeText != null) {
		MediaStreamBadge(
			icon = R.drawable.ic_time,
			text = remainingTimeText,
			modifier = modifier,
		)
	} else {
		WatchIndicator(
			item = item,
			modifier = modifier,
		)
	}
}

@Composable
@Stable
private fun StateIndicator(
	item: BaseItemDto,
	modifier: Modifier = Modifier,
) {
	val isFavorited = item.userData?.isFavorite == true
	val recordingItem = item.recordingStateItem()
	val recordingIcon = when {
		recordingItem?.seriesTimerId != null -> R.drawable.ic_record_series
		recordingItem?.timerId != null -> R.drawable.ic_record
		else -> null
	}

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs)
	) {
		if (recordingIcon != null) {
			Icon(
				imageVector = ImageVector.vectorResource(recordingIcon),
				contentDescription = null,
				tint = if (recordingItem?.timerId != null) Tokens.Color.colorRed600 else Tokens.Color.colorGrey100,
				modifier = modifier
					.size(24.dp)
			)
		}

		if (isFavorited) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_heart),
				contentDescription = null,
				tint = Tokens.Color.colorRed500,
				modifier = modifier
					.size(24.dp)
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
				.size(24.dp),
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_watch),
				contentDescription = null,
				modifier = Modifier.size(12.dp)
			)
		}
	} else if (unplayedItems != null) {
		if (watchedIndicatorBehavior == WatchedIndicatorBehavior.HIDE_UNWATCHED) return

		Badge(
			modifier = modifier
				.sizeIn(minWidth = 24.dp, minHeight = 24.dp),
		) {
			Text(
				text = unplayedItems.toString(),
			)
		}
	}
}

@Composable
@Stable
private fun MediaStreamBadges(
	item: BaseItemDto,
	modifier: Modifier = Modifier,
) {
	val liveTvBadges = item.liveTvTrackBadges()
	val badges = if (liveTvBadges != null) {
		liveTvBadges
	} else {
		val hasLanguageBadgeSource = remember(item.type, item.mediaSources) { item.hasLanguageBadgeSource() }
		if (!hasLanguageBadgeSource) return

		val userRepository = koinInject<UserRepository>()
		val user by userRepository.currentUser.collectAsState()
		val configuration = user?.configuration
		val audioLanguagePreference = configuration?.takeUnless { it.playDefaultAudioTrack }?.audioLanguagePreference
		val subtitleLanguagePreference = configuration?.subtitleLanguagePreference
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
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
		badges.audio?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_badge_speaker,
				text = text,
			)
		}

		badges.subtitle?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_badge_subtitles,
				text = text,
			)
		}
	}
}

@Composable
@Stable
private fun VideoStreamBadges(
	item: BaseItemDto,
	modifier: Modifier = Modifier,
) {
	val badges = remember(item.type, item.mediaSources) { item.videoBadges() } ?: return

	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.Start,
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
		badges.resolution?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_select_quality,
				text = text,
			)
		}

		badges.codec?.let { text ->
			MediaStreamBadge(
				icon = R.drawable.ic_movie,
				text = text,
			)
		}
	}
}

@Composable
private fun MediaStreamBadge(
	icon: Int,
	text: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.background(Tokens.Color.colorBluegrey900.copy(alpha = 0.72f), JellyfinTheme.shapes.extraSmall)
			.padding(horizontal = 2.dp, vertical = 1.dp)
			.widthIn(min = 18.dp),
		horizontalArrangement = Arrangement.spacedBy(1.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = null,
			tint = Tokens.Color.colorWhite,
			modifier = Modifier.size(7.dp),
		)
		Text(
			text = text,
			color = Tokens.Color.colorWhite,
			fontSize = 7.sp,
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
	userData?.playedPercentage
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

@Composable
private fun rememberItemCardProgressInfo(
	item: BaseItemDto,
): ItemCardProgress? {
	val playbackManager = koinInject<PlaybackManager>()
	val playState by playbackManager.state.playState.collectAsState()
	val currentQueueEntry by rememberQueueEntry(playbackManager)

	return if (playState.isActivePlayback && currentQueueEntry?.baseItem?.id == item.id) {
		val progress = rememberPlayerProgress(playbackManager).value.validPlaybackProgress() ?: return null
		ItemCardProgress(
			progress = progress,
			duration = playbackManager.state.positionInfo.duration,
		)
	} else {
		item.storedPlayedProgress()?.let { progress ->
			ItemCardProgress(
				progress = progress,
				duration = item.runTimeTicks?.takeIf { it > 0 }?.ticks,
			)
		}
	}
}

private data class ItemCardProgress(
	val progress: Float,
	val duration: Duration?,
)

@Composable
private fun ProgressIndicator(
	progress: Float?,
	modifier: Modifier = Modifier,
) {
	if (progress != null) {
		Box(modifier = modifier.padding(Tokens.Space.spaceXs)) {
			Seekbar(
				progress = progress,
				enabled = false,
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp)
			)
		}
	}
}
