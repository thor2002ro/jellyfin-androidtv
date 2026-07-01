package org.jellyfin.playback.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import org.jellyfin.playback.core.PlaybackManager

data class PlayerSubtitleStyle(
	val textColor: Int = 0xFFFFFFFF.toInt(),
	val backgroundColor: Int = 0x00FFFFFF,
	val edgeColor: Int = 0xFF000000.toInt(),
	val textWeight: Int = 400,
	val textSizeDp: Float = 24f,
	val bottomPaddingFraction: Float = 0.08f,
)

/**
 * A view that is used to display the subtitle output of the playing media.
 * The [playbackManager] must be set when the view is initialized.
 */
class PlayerSubtitleView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
	defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
	private var _playbackManager: PlaybackManager? = null
	var onSubtitleStyleChanged: ((PlayerSubtitleStyle) -> Unit)? = null
	var subtitleStyle: PlayerSubtitleStyle = PlayerSubtitleStyle()
		set(value) {
			if (field == value) return
			field = value
			onSubtitleStyleChanged?.invoke(value)
		}

	var playbackManager: PlaybackManager
		get() = requireNotNull(_playbackManager) { "PlaybackManager must be set before using PlayerSubtitleView" }
		set(value) {
			if (_playbackManager == value) return
			_playbackManager = value
			if (isAttachedToWindow && !isInEditMode) {
				value.backendService.attachSubtitleView(this)
			}
		}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()

		if (!isInEditMode) {
			_playbackManager?.backendService?.attachSubtitleView(this)
		}
	}
}
