package org.jellyfin.androidtv.integration.dream.composable

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.integration.dream.model.DreamContent
import org.jellyfin.androidtv.ui.base.SeekbarDefaults
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.LyricsDtoBox
import org.jellyfin.androidtv.ui.composable.blurHashPainter
import org.jellyfin.androidtv.ui.composable.modifier.fadingEdges
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.isActivePlayback
import org.jellyfin.playback.jellyfin.lyrics.lyrics
import org.jellyfin.playback.jellyfin.lyrics.lyricsFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import kotlin.time.Duration

@Composable
fun DreamContentNowPlaying(
	content: DreamContent.NowPlaying,
) = Box(
	modifier = Modifier.fillMaxSize(),
) {
	val api = koinInject<ApiClient>()
	val playbackManager = koinInject<PlaybackManager>()
	val lyrics = content.entry.run { lyricsFlow.collectAsState(lyrics) }.value

	val primaryImage = content.item.itemImages[ImageType.PRIMARY]
		?: content.item.albumPrimaryImage
		?: content.item.parentImages[ImageType.PRIMARY]

	// Background
	if (primaryImage?.blurHash != null) {
		Image(
			painter = blurHashPainter(primaryImage.blurHash, IntSize(32, 32)),
			contentDescription = null,
			alignment = Alignment.Center,
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize(),
		)

		DreamContentVignette()
	}

	// Lyrics overlay (on top of background)
	if (lyrics != null) {
		val playState by remember { playbackManager.state.playState }.collectAsState()
		val positionInfo by rememberPlayerPositionInfo(playbackManager)

		LyricsDtoBox(
			lyricDto = lyrics,
			currentTimestamp = positionInfo.active,
			duration = positionInfo.duration,
			paused = !playState.isActivePlayback,
			fontSize = 22.sp,
			color = Color.White,
			modifier = Modifier
				.fillMaxSize()
				.fadingEdges(vertical = 250.dp)
				.padding(horizontal = 50.dp),
		)
	}

	// Metadata overlay (includes title / progress)
	Row(
		verticalAlignment = Alignment.Bottom,
		horizontalArrangement = Arrangement.spacedBy(20.dp),
		modifier = Modifier
			.align(Alignment.BottomStart)
			.overscan(),
	) {
		if (primaryImage != null) {
			AsyncImage(
				url = primaryImage.getUrl(api),
				blurHash = primaryImage.blurHash,
				scaleType = ImageView.ScaleType.CENTER_CROP,
				modifier = Modifier
					.size(128.dp)
					.clip(RoundedCornerShape(5.dp))
			)
		}

		Column(
			modifier = Modifier
				.padding(bottom = 10.dp)
		) {
			Text(
				text = content.item.name.orEmpty(),
				style = TextStyle(
					color = Color.White,
					fontSize = 26.sp,
				),
			)

			Text(
				text = content.item.run {
					val artistNames = artists.orEmpty()
					val albumArtistNames = albumArtists?.mapNotNull { it.name }.orEmpty()

					when {
						artistNames.isNotEmpty() -> artistNames
						albumArtistNames.isNotEmpty() -> albumArtistNames
						else -> listOfNotNull(albumArtist)
					}.joinToString(", ")
				},
				style = TextStyle(
					color = Color(0.8f, 0.8f, 0.8f),
					fontSize = 18.sp,
				),
			)

			Spacer(modifier = Modifier.height(10.dp))

			PlayerSeekbar(
				playbackManager = playbackManager,
				colors = SeekbarDefaults.colors(
					backgroundColor = Color.White.copy(alpha = 0.2f),
					progressColor = Color.White,
					bufferColor = Color.Transparent,
				),
				modifier = Modifier
					.fillMaxWidth()
					.height(4.dp)
			)
		}
	}
}

@Composable
fun BoxScope.DreamPausedNowPlayingBadge(
	content: DreamContent.NowPlaying?,
) {
	if (content == null) return

	val api = koinInject<ApiClient>()
	val playbackManager = koinInject<PlaybackManager>()
	val positionInfo by rememberPlayerPositionInfo(playbackManager)
	val image = content.item.itemImages[ImageType.PRIMARY]
		?: content.item.itemImages[ImageType.THUMB]
		?: content.item.albumPrimaryImage
		?: content.item.parentImages[ImageType.PRIMARY]
		?: content.item.parentImages[ImageType.THUMB]

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(14.dp),
		modifier = Modifier
			.align(Alignment.BottomEnd)
			.overscan()
			.widthIn(max = 440.dp)
			.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(5.dp))
			.padding(12.dp),
	) {
		if (image != null) {
			AsyncImage(
				url = image.getUrl(api),
				blurHash = image.blurHash,
				scaleType = ImageView.ScaleType.CENTER_CROP,
				modifier = Modifier
					.size(88.dp)
					.clip(RoundedCornerShape(5.dp))
			)
		}

		Column(
			verticalArrangement = Arrangement.spacedBy(6.dp),
			modifier = Modifier
				.widthIn(max = 300.dp)
		) {
			Text(
				text = content.item.name.orEmpty(),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				style = TextStyle(
					color = Color.White,
					fontSize = 20.sp,
					fontWeight = FontWeight.SemiBold,
				),
			)

			Text(
				text = positionInfo.formatPausedPosition(),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				style = TextStyle(
					color = Color(0.8f, 0.8f, 0.8f),
					fontSize = 16.sp,
				),
			)
		}
	}
}

private fun org.jellyfin.playback.core.model.PositionInfo.formatPausedPosition(): String {
	val activeFormatted = TimeUtils.formatMillis(active.coerceAtLeast(Duration.ZERO).inWholeMilliseconds)
	if (duration <= Duration.ZERO) return activeFormatted

	return "$activeFormatted / ${TimeUtils.formatMillis(duration.inWholeMilliseconds)}"
}
