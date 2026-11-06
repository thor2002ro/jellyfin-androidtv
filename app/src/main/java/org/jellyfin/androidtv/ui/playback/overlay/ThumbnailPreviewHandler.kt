package org.jellyfin.androidtv.ui.playback.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.player.video.VideoPlayerTrickplayThumbnail
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private data class ThumbnailPreviewState(
	val item: BaseItemDto,
	val mediaSourceId: String?,
	val position: Duration,
)

class ThumbnailPreviewHandler(
	private val videoPlayerAdapter: VideoPlayerAdapter,
	private val thumbnailComposeView: ComposeView?,
) {
	private var preview by mutableStateOf<ThumbnailPreviewState?>(null)

	init {
		thumbnailComposeView?.setContent {
			val userPreferences = koinInject<UserPreferences>()
			preview?.let { preview ->
				VideoPlayerTrickplayThumbnail(
					item = preview.item,
					mediaSourceId = preview.mediaSourceId,
					position = preview.position,
					enabled = userPreferences[UserPreferences.trickPlayEnabled],
				)
			}
		}
	}

	fun updateThumbnailPreview(previewSeekPosition: Long) {
		val item = videoPlayerAdapter.currentlyPlayingItem ?: return hideThumbnailPreview()
		preview = ThumbnailPreviewState(
			item = item,
			mediaSourceId = videoPlayerAdapter.currentMediaSource?.id,
			position = previewSeekPosition.milliseconds,
		)
	}

	fun hideThumbnailPreview() {
		preview = null
	}

	fun isAvailable(): Boolean = thumbnailComposeView != null

	fun dispose() {
		preview = null
		thumbnailComposeView?.disposeComposition()
	}
}
