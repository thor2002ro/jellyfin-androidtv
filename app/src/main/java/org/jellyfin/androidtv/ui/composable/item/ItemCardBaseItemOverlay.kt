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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
import org.jellyfin.androidtv.util.languageCodesMatch
import org.jellyfin.androidtv.util.toIso2LanguageBadgeOrNull
import org.jellyfin.design.Tokens
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.compose.koinInject

@Composable
@Stable
fun ItemCardBaseItemOverlay(
	item: BaseItemDto,
	streamBadgeItem: BaseItemDto = item,
	footer: (@Composable () -> Unit)? = null,
) = Box(
	modifier = Modifier
		.fillMaxSize()
		.padding(Tokens.Space.spaceXs)
) {
	StateIndicator(
		item = item,
		modifier = Modifier.align(Alignment.TopStart),
	)

	WatchIndicator(
		item = item,
		modifier = Modifier.align(Alignment.TopEnd),
	)

	Column(
		modifier = Modifier
			.align(Alignment.BottomCenter)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceXs)
	) {
		MediaStreamBadges(
			item = streamBadgeItem,
			modifier = Modifier.align(Alignment.End),
		)

		ProgressIndicator(
			item = item,
		)

		if (footer != null) footer()
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
	val userRepository = koinInject<UserRepository>()
	val user by userRepository.currentUser.collectAsState()
	val configuration = user?.configuration
	val badges = item.streamBadges(
		audioLanguagePreference = configuration?.takeUnless { it.playDefaultAudioTrack }?.audioLanguagePreference,
		subtitleLanguagePreference = configuration?.subtitleLanguagePreference,
	) ?: item.liveTvTrackBadges() ?: return

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
private fun MediaStreamBadge(
	icon: Int,
	text: String,
) {
	Row(
		modifier = Modifier
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
		)
	}
}

internal data class StreamBadges(
	val audio: String?,
	val subtitle: String?,
)

internal fun BaseItemDto.streamBadges(
	audioLanguagePreference: String?,
	subtitleLanguagePreference: String?,
): StreamBadges? {
	when (type) {
		BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.SERIES, BaseItemKind.SEASON -> Unit
		else -> return null
	}

	val mediaSource = mediaSources?.firstOrNull { source -> source.mediaStreams?.isNotEmpty() == true } ?: return null
	val streams = mediaSource.mediaStreams.orEmpty()
	val audio = streams.badgeText(
		type = MediaStreamType.AUDIO,
		languagePreference = audioLanguagePreference,
		defaultIndex = mediaSource.defaultAudioStreamIndex,
	)
	val subtitle = streams.badgeText(
		type = MediaStreamType.SUBTITLE,
		languagePreference = subtitleLanguagePreference,
		defaultIndex = mediaSource.defaultSubtitleStreamIndex,
	)

	return StreamBadges(audio, subtitle).takeIf { badges -> badges.audio != null || badges.subtitle != null }
}

private fun BaseItemDto.liveTvTrackBadges(): StreamBadges? {
	when (type) {
		BaseItemKind.TV_CHANNEL,
		BaseItemKind.LIVE_TV_CHANNEL,
		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.LIVE_TV_PROGRAM -> Unit
		else -> return null
	}

	val tracks = LiveTvTrackCache.get(this) ?: currentProgram?.let(LiveTvTrackCache::get) ?: return null
	val audio = tracks.audio.selectedTrack(tracks.selectedAudioTrackIndex)?.language.toIso2LanguageBadgeOrNull()
	val subtitle = tracks.subtitles
		.takeUnless { tracks.selectedSubtitleTrackIndex == -1 }
		?.selectedTrack(tracks.selectedSubtitleTrackIndex)
		?.language.toIso2LanguageBadgeOrNull()

	return StreamBadges(audio, subtitle).takeIf { badges -> badges.audio != null || badges.subtitle != null }
}

private fun List<LiveTvTrackCache.Track>.selectedTrack(index: Int?) =
	index?.takeIf { it >= 0 }?.let { selected -> firstOrNull { track -> track.index == selected } }
		?: firstOrNull { track -> track.isDefault }
		?: firstOrNull()

private fun List<MediaStream>.badgeText(
	type: MediaStreamType,
	languagePreference: String?,
	defaultIndex: Int?,
): String? {
	val streams = filter { stream -> stream.type == type }
	if (streams.isEmpty()) return null

	val badges = buildList {
		fun add(stream: MediaStream) {
			val badge = stream.badgeText() ?: return
			if (none { it == badge }) add(badge)
		}

		languagePreference?.takeIf { it.isNotBlank() }?.let { preferredLanguage ->
			streams.filter { stream -> languageCodesMatch(stream.language, preferredLanguage) }.forEach(::add)
		}
		if (defaultIndex != -1) {
			defaultIndex?.let { index -> streams.filter { stream -> stream.index == index }.forEach(::add) }
			streams.filter { stream -> stream.isDefault }.forEach(::add)
		}
		streams.forEach(::add)
	}

	val visibleBadges = badges.take(MAX_STREAM_BADGE_LANGUAGES)
	val hiddenCount = badges.size - visibleBadges.size
	return (visibleBadges + listOfNotNull(hiddenCount.takeIf { it > 0 }?.let { "+$it" }))
		.joinToString(" ")
		.takeIf { it.isNotBlank() }
}

private fun MediaStream.badgeText(): String? =
	language.toIso2LanguageBadgeOrNull()

private const val MAX_STREAM_BADGE_LANGUAGES = 3

@Composable
private fun ProgressIndicator(
	item: BaseItemDto,
	modifier: Modifier = Modifier,
) {
	val playbackManager = koinInject<org.jellyfin.playback.core.PlaybackManager>()
	val playState by playbackManager.state.playState.collectAsState()
	val currentQueueEntry by rememberQueueEntry(playbackManager)

	val playedPercentage = if (playState.isActivePlayback && currentQueueEntry?.baseItem?.id == item.id) {
		rememberPlayerProgress(playbackManager).value
	} else {
		item.userData?.playedPercentage?.toFloat()?.div(100f)?.coerceIn(0f, 1f)?.takeIf { it > 0f && it < 1f }
	}

	if (playedPercentage != null) {
		Box(modifier = modifier.padding(Tokens.Space.spaceXs)) {
			Seekbar(
				progress = playedPercentage,
				enabled = false,
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp)
			)
		}
	}
}
