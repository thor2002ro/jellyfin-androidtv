package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.livetv.LiveTvTrackCache
import org.jellyfin.androidtv.ui.player.base.PlayerSubtitles
import org.jellyfin.androidtv.ui.player.base.PlayerSurface
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.video.toast.rememberPlaybackManagerMediaToastEmitter
import org.jellyfin.androidtv.util.apiclient.getTrickplayTileSheets
import org.jellyfin.playback.jellyfin.livetv.liveTvChannelId
import org.jellyfin.androidtv.util.toIso2LanguageDisplayOrSelf
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.playback.jellyfin.recovery.NetworkPlaybackRecoveryService
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.compose.koinInject
import timber.log.Timber

private const val DefaultVideoAspectRatio = 16f / 9f
private const val BufferingBlockCount = 5

@Composable
fun VideoPlayerScreen(
	onRemoteKeyEventHandlerChanged: (((keyCode: Int, event: android.view.KeyEvent?) -> Boolean)?) -> Unit = {},
	onClosePlayer: () -> Unit = {},
	openLiveTvGuideOnStart: Boolean = false,
) {
	val playbackManager = koinInject<PlaybackManager>()
	val userPreferences = koinInject<UserPreferences>()
	var zoomMode by remember { mutableStateOf(userPreferences[UserPreferences.playerZoomMode]) }

	val backgroundService = koinInject<BackgroundService>()
	LaunchedEffect(backgroundService) {
		backgroundService.clearBackgrounds()
	}

	val playState by playbackManager.state.playState.collectAsState()
	val playing = playState.isActivePlayback
	TrickplayTileSheetPrefetcher(
		playbackManager = playbackManager,
		enabled = userPreferences[UserPreferences.trickPlayEnabled],
	)
	ChapterThumbnailPrefetcher(playbackManager = playbackManager)
	ScreensaverLock(
		enabled = playing,
	)

	val videoSize by playbackManager.state.videoSize.collectAsState()
	val aspectRatio = videoSize.aspectRatio.takeIf { !it.isNaN() && it > 0f } ?: DefaultVideoAspectRatio

	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	rememberPlaybackManagerMediaToastEmitter(playbackManager, mediaToastRegistry)

	BoxWithConstraints(
		modifier = Modifier
			.background(Color.Black)
			.fillMaxSize()
			.clipToBounds()
	) {
		val viewportSize = remember(maxWidth, maxHeight, aspectRatio, zoomMode) {
			calculateVideoViewportSize(maxWidth, maxHeight, aspectRatio, zoomMode)
		}
		val videoModifier = Modifier
			.requiredSize(viewportSize.width, viewportSize.height)
			.align(Alignment.Center)

		PlayerVideoOutput(
			playbackManager = playbackManager,
			modifier = videoModifier,
		)

		VideoPlayerOverlay(
			playbackManager = playbackManager,
			mediaToastRegistry = mediaToastRegistry,
			zoomMode = zoomMode,
			onZoomModeSelected = { zoomMode = it },
			onRemoteKeyEventHandlerChanged = onRemoteKeyEventHandlerChanged,
			onClosePlayer = onClosePlayer,
			openLiveTvGuideOnStart = openLiveTvGuideOnStart,
		)
	}
}

@Composable
internal fun PlayerVideoOutput(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
	showBufferingIndicator: Boolean = true,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val networkRecoveryService = remember(playbackManager) {
		playbackManager.getService<NetworkPlaybackRecoveryService>()
	}
	val networkRecovering by networkRecoveryService?.recovering?.collectAsState()
		?: remember { mutableStateOf(false) }

	LiveTvTrackCacheUpdater(playbackManager)

	Box(modifier = modifier) {
		PlayerSurface(
			playbackManager = playbackManager,
			modifier = Modifier.fillMaxSize()
		)

		PlayerSubtitles(
			playbackManager = playbackManager,
			modifier = Modifier.fillMaxSize()
		)

		VideoBufferingIndicator(
			visible = showBufferingIndicator && (playState == PlayState.BUFFERING || networkRecovering),
			modifier = Modifier.align(Alignment.Center),
		)
	}
}

@Composable
private fun ChapterThumbnailPrefetcher(
	playbackManager: PlaybackManager,
	api: ApiClient = koinInject(),
	imageLoader: ImageLoader = koinInject(),
) {
	val context = LocalContext.current
	val density = LocalDensity.current
	val thumbnailWidth = with(density) { ChapterThumbnailWidth.roundToPx() }
	val thumbnailHeight = with(density) { ChapterThumbnailHeight.roundToPx() }
	val entry by playbackManager.queue.entry.collectAsState()
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val thumbnailUrls = remember(item?.id, item?.chapters, api.accessToken, thumbnailWidth, thumbnailHeight) {
		item?.getChapterThumbnailUrls(api, thumbnailWidth, thumbnailHeight).orEmpty()
	}

	DisposableEffect(thumbnailUrls) {
		onDispose {
			val stats = ChapterThumbnailMemoryCache.clear(thumbnailUrls)
			if (stats.count > 0) {
				Timber.i("Cleared chapter thumbnail memory cache: ${stats.count} thumbnails, ${"%.1f".format(stats.mib)} MiB")
			}
		}
	}

	LaunchedEffect(thumbnailUrls) {
		withContext(Dispatchers.IO) {
			thumbnailUrls.forEach { url ->
				val bitmap = imageLoader.execute(
					ImageRequest.Builder(context)
						.data(url)
						.memoryCachePolicy(CachePolicy.DISABLED)
						.diskCachePolicy(CachePolicy.DISABLED)
						.build()
				).image?.toBitmap()
				if (bitmap != null) ChapterThumbnailMemoryCache.put(url, bitmap)
			}
		}
		val stats = ChapterThumbnailMemoryCache.stats(thumbnailUrls)
		if (stats.count > 0) {
			Timber.i("Prefetched chapter thumbnail memory cache: ${stats.count}/${thumbnailUrls.size} thumbnails, ${"%.1f".format(stats.mib)} MiB")
		}
	}
}

@Composable
private fun TrickplayTileSheetPrefetcher(
	playbackManager: PlaybackManager,
	enabled: Boolean,
	api: ApiClient = koinInject(),
	imageLoader: ImageLoader = koinInject(),
) {
	val context = LocalContext.current
	val entry by playbackManager.queue.entry.collectAsState()
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val mediaSourceId = entry?.mediaSourceId
	val sheets = remember(enabled, item?.id, item?.trickplay, mediaSourceId, api.accessToken) {
		if (enabled && item != null) item.getTrickplayTileSheets(api, mediaSourceId)
		else emptyList()
	}

	DisposableEffect(sheets) {
		onDispose {
			val stats = TrickplayTileSheetMemoryCache.clear(sheets.map { sheet -> sheet.url })
			if (stats.count > 0) {
				Timber.i("Cleared trickplay memory cache: ${stats.count} sheets, ${"%.1f".format(stats.mib)} MiB")
			}
		}
	}

	LaunchedEffect(sheets) {
		withContext(Dispatchers.IO) {
			sheets.forEach { sheet ->
				val bitmap = imageLoader.execute(
					ImageRequest.Builder(context)
						.data(sheet.url)
						.httpHeaders(sheet.headers)
						.memoryCachePolicy(CachePolicy.DISABLED)
						.diskCachePolicy(CachePolicy.DISABLED)
						.build()
				).image?.toBitmap()
				if (bitmap != null) TrickplayTileSheetMemoryCache.put(sheet.url, bitmap)
			}
		}
		val stats = TrickplayTileSheetMemoryCache.stats(sheets.map { sheet -> sheet.url })
		if (stats.count > 0) {
			Timber.i("Prefetched trickplay memory cache: ${stats.count}/${sheets.size} sheets, ${"%.1f".format(stats.mib)} MiB")
		}
	}
}

@Composable
private fun LiveTvTrackCacheUpdater(playbackManager: PlaybackManager) {
	val entry by playbackManager.queue.entry.collectAsState()
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val stream = entry?.run { mediaStreamFlow.collectAsState(mediaStream) }?.value
	val channelId = item?.liveTvChannelId()
	val audio = stream?.tracks
		?.filterIsInstance<MediaStreamAudioTrack>()
		?.map { track ->
			LiveTvTrackCache.Track(
				index = track.index,
				language = track.language.toIso2LanguageDisplayOrSelf(),
				title = track.title,
				codec = track.codec,
			)
		}
		.orEmpty()
	val subtitles = stream?.tracks
		?.filterIsInstance<MediaStreamSubtitleTrack>()
		?.map { track ->
			LiveTvTrackCache.Track(
				index = track.index,
				language = track.language.toIso2LanguageDisplayOrSelf(),
				title = track.title,
				codec = track.codec,
			)
		}
		.orEmpty()

	LaunchedEffect(channelId, audio, subtitles) {
		LiveTvTrackCache.update(
			channelId = channelId,
			audio = audio,
			subtitles = subtitles,
			selectedAudioTrackIndex = stream?.selectedAudioStreamIndex,
			selectedSubtitleTrackIndex = stream?.selectedSubtitleStreamIndex,
		)
	}
}

@Composable
private fun VideoBufferingIndicator(
	visible: Boolean,
	modifier: Modifier = Modifier,
) {
	val description = stringResource(R.string.loading)

	AnimatedVisibility(
		visible = visible,
		modifier = modifier.semantics { contentDescription = description },
		enter = fadeIn(animationSpec = tween(durationMillis = 150)),
		exit = fadeOut(animationSpec = tween(durationMillis = 150)),
	) {
		Box(
			contentAlignment = Alignment.Center,
			modifier = Modifier.size(width = 192.dp, height = 64.dp),
		) {
			val transition = rememberInfiniteTransition(label = "video-buffering")
			val phase by transition.animateFloat(
				initialValue = 0f,
				targetValue = 1f,
				animationSpec = infiniteRepeatable(
					animation = tween(
						durationMillis = 1_400,
						easing = LinearEasing,
					)
				),
				label = "video-buffering-phase",
			)
			val colors = listOf(
				Color(0xFFAA5CC3),
				Color.White,
				Color(0xFF00A4DC),
			)

			Canvas(modifier = Modifier.fillMaxSize()) {
				drawRoundRect(
					color = Color.Black.copy(alpha = 0.42f),
					cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
				)

				val animationWidth = minOf(144.dp.toPx(), size.width)
				val animationHeight = minOf(40.dp.toPx(), size.height)
				val animationLeft = (size.width - animationWidth) / 2f
				val blockWidth = animationWidth * 0.16f
				val blockHeight = animationHeight * 0.16f
				val cornerRadius = CornerRadius(blockHeight / 2f, blockHeight / 2f)
				val travelWidth = animationWidth + blockWidth
				val top = (size.height - blockHeight) / 2f

				repeat(BufferingBlockCount) { index ->
					val blockPhase = (phase + (index.toFloat() / BufferingBlockCount)) % 1f
					val x = animationLeft + blockPhase * travelWidth - blockWidth
					val edgeFade = minOf(blockPhase, 1f - blockPhase).coerceIn(0f, 0.16f) / 0.16f
					val alpha = 0.28f + edgeFade * 0.72f

					drawRoundRect(
						color = colors[index % colors.size].copy(alpha = alpha),
						topLeft = Offset(x = x, y = top),
						size = Size(
							width = blockWidth,
							height = blockHeight,
						),
						cornerRadius = cornerRadius,
					)
				}
			}
		}
	}
}

private data class VideoViewportSize(
	val width: Dp,
	val height: Dp,
)

private fun calculateVideoViewportSize(
	containerWidth: Dp,
	containerHeight: Dp,
	videoAspectRatio: Float,
	zoomMode: ZoomMode,
): VideoViewportSize {
	if (containerWidth <= 0.dp || containerHeight <= 0.dp || videoAspectRatio <= 0f) {
		return VideoViewportSize(containerWidth, containerHeight)
	}

	if (zoomMode == ZoomMode.STRETCH) {
		return VideoViewportSize(containerWidth, containerHeight)
	}

	val containerAspectRatio = containerWidth.value / containerHeight.value
	val matchContainerWidth = when (zoomMode) {
		ZoomMode.FIT -> videoAspectRatio >= containerAspectRatio
		ZoomMode.AUTO_CROP -> videoAspectRatio < containerAspectRatio
		ZoomMode.STRETCH -> true
	}

	val width = if (matchContainerWidth) containerWidth else (containerHeight.value * videoAspectRatio).dp
	val height = if (matchContainerWidth) (containerWidth.value / videoAspectRatio).dp else containerHeight

	return VideoViewportSize(width, height)
}
